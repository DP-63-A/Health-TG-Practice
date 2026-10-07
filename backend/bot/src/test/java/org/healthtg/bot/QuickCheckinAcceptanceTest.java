package org.healthtg.bot;

import org.healthtg.bot.checkin.CheckinCategory;
import org.healthtg.bot.checkin.CheckinSelector;
import org.healthtg.bot.text.TextInputParser;
import org.healthtg.core.dialog.*;
import org.healthtg.core.entry.*;
import org.healthtg.user.UserService;
import org.junit.jupiter.api.*;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;
import org.junit.jupiter.params.provider.Arguments;
import org.springframework.boot.test.util.TestPropertyValues;
import org.springframework.context.annotation.AnnotationConfigApplicationContext;
import org.springframework.data.mongodb.core.MongoTemplate;
import org.springframework.dao.DataAccessResourceFailureException;
import org.testcontainers.containers.MongoDBContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.utility.DockerImageName;
import java.time.*;
import java.util.*;
import java.util.stream.Stream;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;
import static org.mockito.AdditionalAnswers.delegatesTo;

/** Independent requirement tests against real Mongo services, without Telegram transport. */
@Testcontainers
class QuickCheckinAcceptanceTest {
    @Container static final MongoDBContainer MONGO = new MongoDBContainer(
            DockerImageName.parse("mongodb/mongodb-community-server:8.0-ubi9-slim").asCompatibleSubstituteFor("mongo"));
    static final Instant NOW = Instant.parse("2026-10-07T22:30:00Z");
    AnnotationConfigApplicationContext context;
    String database;
    UserService users;
    EntryCoreService entries;
    DialogStateService dialogs;
    TextInputParser parser;
    @BeforeEach void open() { database = "checkin8_" + UUID.randomUUID().toString().replace("-", ""); reopen(); }
    void reopen() {
        context = new AnnotationConfigApplicationContext();
        TestPropertyValues.of("test.mongo.uri=" + MONGO.getReplicaSetUrl(), "test.mongo.database=" + database).applyTo(context);
        context.register(BotCoreStorageTestConfiguration.class); context.refresh();
        users = context.getBean(UserService.class); entries = context.getBean(EntryCoreService.class);
        dialogs = context.getBean(DialogStateService.class); parser = mock(TextInputParser.class);
    }
    @AfterEach void close() { context.getBean(MongoTemplate.class).getDb().drop(); context.close(); }
    OwnerContext owner(long id) { return new OwnerContext(users.findOrCreate(id).id()); }
    CoreBotFlow flow() { return flow(entries, NOW); }
    CoreBotFlow flow(EntryCoreService service, Instant now) {
        return new CoreBotFlow(users, service, dialogs, parser, new CheckinSelector(), Clock.fixed(now, ZoneOffset.UTC));
    }
    static BotUpdate message(long id, long update) { return Pr76StateRegressionTest.msg(id, update, "/state"); }
    static BotUpdate callback(long id, long update, String data) { return Pr76StateRegressionTest.cb(id, update, data); }
    static BotAction.SendInlineMessage card(List<BotAction> actions) { return Pr76StateRegressionTest.keyboard(actions); }
    static String button(List<BotAction> actions) { return Pr76StateRegressionTest.button(actions); }
    static String answer(List<BotAction> actions) { return ((BotAction.AnswerCallback) actions.getFirst()).text(); }
    record Saved(Entry entry, String scoreButton, String cancelButton, String receipt) {}
    Saved save(CoreBotFlow f, long user, long start, int category, int score) {
        var categories = card(f.beginCheckin(message(user, start))).rows().stream().flatMap(List::stream).toList();
        var scores = card(f.handleCallback(callback(user, start + 1, categories.get(category).callbackData()))).rows().stream().flatMap(List::stream).toList();
        String scoreButton = scores.get(score - 1).callbackData();
        var result = f.handleCallback(callback(user, start + 2, scoreButton));
        String cancel = button(result);
        return new Saved(entries.requireEntry(owner(user), UUID.fromString(cancel.split(":")[1])), scoreButton, cancel, card(result).text());
    }
    static Stream<Arguments> combinations() {
        return java.util.stream.IntStream.range(0, 4).boxed().flatMap(c -> java.util.stream.IntStream.rangeClosed(1, 5).mapToObj(s -> Arguments.of(c, s)));
    }
    List<Entry> facts(long user) {
        return entries.listConfirmedEntries(new ListConfirmedEntriesQuery(owner(user), LocalDate.of(2026, 10, 8), LocalDate.of(2026, 10, 8), ZoneId.of("Europe/Warsaw"), Set.of()));
    }
    @ParameterizedTest @MethodSource("combinations")
    void allCategoriesAndScoresHaveExactPersistedReceiptAndNoParser(int category, int score) {
        Saved saved = save(flow(), 1, 10, category, score);
        assertEquals(CheckinCategory.values()[category].code(), saved.entry.payload().get("category"));
        assertEquals(score, saved.entry.payload().get("score"));
        assertEquals(NOW, saved.entry.occurredAt());
        assertTrue(saved.receipt.contains(CheckinCategory.values()[category].label() + ": " + score + " из 5."));
        assertTrue(saved.receipt.contains("08.10.2026 00:30 +02:00"));
        assertTrue(saved.cancelButton.getBytes(java.nio.charset.StandardCharsets.UTF_8).length <= 64);
        assertEquals(1, facts(1).size());
        assertEquals("Отметка отменена", answer(flow().handleCallback(callback(1, 13, saved.cancelButton))));
        assertTrue(facts(1).isEmpty());
        assertEquals("Отметка уже отменена", answer(flow().handleCallback(callback(1, 14, saved.cancelButton))));
        verifyNoInteractions(parser);
    }
    @Test void deletionIsPersistedAndExcludedFromBothReadPathsAndRepeatDoesNotMutate() {
        Saved saved = save(flow(), 1, 10, 0, 5);
        assertEquals("Отметка отменена", answer(flow().handleCallback(callback(1, 13, saved.cancelButton))));
        Entry deleted = entries.requireEntry(owner(1), saved.entry.id());
        assertEquals(EntryStatus.DELETED, deleted.status());
        assertEquals(saved.entry.revision() + 1, deleted.revision());
        assertTrue(facts(1).isEmpty());
        assertTrue(entries.listEntries(new ListEntriesQuery(owner(1), EntryStatus.CONFIRMED, EntryType.CHECKIN, null, null, ZoneOffset.UTC)).isEmpty());
        var mongo = context.getBean(MongoTemplate.class);
        var rawBefore = mongo.getCollection("entries").find(new org.bson.Document("_id", deleted.id().toString())).first();
        assertNotNull(rawBefore);
        Map<String, Long> counts = new HashMap<>();
        mongo.getCollectionNames().forEach(name -> counts.put(name, mongo.getCollection(name).countDocuments()));
        assertEquals("Отметка уже отменена", answer(flow().handleCallback(callback(1, 14, saved.cancelButton))));
        assertEquals(deleted, entries.requireEntry(owner(1), deleted.id()));
        assertEquals(rawBefore, mongo.getCollection("entries").find(new org.bson.Document("_id", deleted.id().toString())).first(), "Including raw mutation history");
        counts.forEach((name, count) -> assertEquals(count.longValue(), mongo.getCollection(name).countDocuments()));
        assertEquals("Отметка уже отменена", answer(flow().handleCallback(callback(1, 15, saved.scoreButton))));
    }
    @Test void restartReplayUsesSavedTimeAndLatestEditedPayloadButStaleCancelCannotDelete() {
        Saved saved = save(flow(), 1, 10, 3, 1);
        Entry edited = entries.patch(new PatchEntryCommand(owner(1), saved.entry.id(), saved.entry.revision(), null,
                Map.of("category", "mood", "score", 5), null));
        context.close(); reopen();
        var replay = flow(entries, NOW.plusSeconds(86400)).handleCallback(callback(1, 20, saved.scoreButton));
        assertTrue(card(replay).text().contains("🙂 Настроение: 5 из 5."));
        assertTrue(card(replay).text().contains("08.10.2026 00:30 +02:00"));
        assertEquals("qc:" + edited.id() + ":" + edited.revision(), button(replay));
        assertEquals("Отметка уже изменена", answer(flow().handleCallback(callback(1, 21, saved.cancelButton))));
        assertEquals(edited, entries.requireEntry(owner(1), edited.id()));
        assertEquals(1, facts(1).size());
    }
    @Test void foreignOwnerAndMalformedCallbacksCannotMutateEntry() {
        Saved saved = save(flow(), 1, 10, 2, 4);
        assertEquals("Отметка недоступна", answer(flow().handleCallback(callback(2, 11, saved.cancelButton))));
        for (String data : List.of("qc:", "qc:not-uuid:1", "qc:" + saved.entry.id() + ":0", "qc:" + saved.entry.id() + ":-1",
                "qc:" + saved.entry.id() + ":9223372036854775808", "qc:" + saved.entry.id() + ":01", "qc:" + saved.entry.id() + ":1:extra")) {
            assertEquals("Отметка недоступна", answer(flow().handleCallback(callback(1, 30, data))), data);
        }
        assertEquals(saved.entry, entries.requireEntry(owner(1), saved.entry.id()));
    }
    @Test void lostDeleteResponseCanRecoverAfterRestartWithoutTouchingNewDialog() {
        Saved saved = save(flow(), 1, 10, 1, 3);
        flow().beginCheckin(message(1, 20));
        DialogState active = dialogs.find(owner(1)).orElseThrow();
        EntryCoreService fault = mock(EntryCoreService.class, delegatesTo(entries));
        doAnswer(inv -> { entries.delete(inv.getArgument(0), inv.getArgument(1), inv.getArgument(2));
            throw new DataAccessResourceFailureException("simulated lost database acknowledgement");
        }).when(fault).delete(any(), any(), anyLong());
        assertThrows(DataAccessResourceFailureException.class, () -> flow(fault, NOW).handleCallback(callback(1, 21, saved.cancelButton)));
        context.close(); reopen();
        assertEquals("Отметка уже отменена", answer(flow().handleCallback(callback(1, 22, saved.cancelButton))));
        assertEquals(active, dialogs.find(owner(1)).orElseThrow());
        assertTrue(facts(1).isEmpty());
    }
    @Test void concurrentDeleteBetweenReadAndWriteIsReportedAsAlreadyDeleted() {
        Saved saved = save(flow(), 1, 10, 0, 1);
        EntryCoreService race = mock(EntryCoreService.class, delegatesTo(entries));
        doAnswer(inv -> { entries.delete(inv.getArgument(0), inv.getArgument(1), inv.getArgument(2));
            return entries.delete(inv.getArgument(0), inv.getArgument(1), inv.getArgument(2));
        }).when(race).delete(any(), any(), anyLong());
        assertTrue(answer(flow(race, NOW).handleCallback(callback(1, 21, saved.cancelButton))).contains("отменена"));
        assertTrue(facts(1).isEmpty());
    }
    @Test void stateCommandPreservesPendingTextClarification() {
        parser = spy(new TextInputParser());
        EntryCoreService observed = mock(EntryCoreService.class, delegatesTo(entries));
        CoreBotFlow f = flow(observed, NOW);
        String input = "Прошёл за день 8000 шагов";
        f.handleMessage(Pr76StateRegressionTest.msg(1, 10, input));
        var existing = dialogs.find(owner(1)).orElseThrow();
        assertEquals("text_clarification", existing.step());
        assertEquals(input, existing.context().get("original_text"));
        verify(parser).parse(input);
        clearInvocations(parser);

        var reply = card(f.beginCheckin(message(1, 11)));
        assertTrue(reply.text().contains("Сначала завершите или отмените текстовый ввод."));
        assertTrue(reply.text().contains("Введите календарную дату"));
        assertEquals(List.of("Ввести заново", "Отменить ввод"), reply.rows().stream()
                .flatMap(List::stream).map(BotAction.InlineButton::text).toList());
        assertEquals(existing, dialogs.find(owner(1)).orElseThrow());
        assertTrue(entries.findActiveDraft(owner(1)).isEmpty());
        assertTrue(facts(1).isEmpty());
        verify(observed, never()).createCheckin(any());
        verifyNoInteractions(parser);
    }

    @Test void stateCommandSafelyResetsDamagedTextClarificationWithoutCreatingCheckin() {
        dialogs.save(new SaveDialogStateCommand(owner(1), null, "text_clarification",
                Map.of("original_text", "Прошёл 8000 шагов 🙂"), new TelegramUpdateKey("main", 10)));
        EntryCoreService observed = mock(EntryCoreService.class, delegatesTo(entries));
        var reply = card(flow(observed, NOW).beginCheckin(message(1, 11)));
        assertEquals("Не удалось восстановить текстовый ввод. Запись не подтверждена. Отправьте сообщение заново.", reply.text());
        assertTrue(reply.rows().isEmpty());
        assertEquals("idle", dialogs.find(owner(1)).orElseThrow().step());
        assertTrue(entries.findActiveDraft(owner(1)).isEmpty());
        assertTrue(facts(1).isEmpty());
        verify(observed, never()).createCheckin(any());
        verifyNoInteractions(parser);
    }

    @Test void concurrentEditBetweenReadAndDeleteIsNotLost() {
        Saved saved = save(flow(), 1, 10, 3, 1);
        EntryCoreService race = mock(EntryCoreService.class, delegatesTo(entries));
        doAnswer(inv -> {
            entries.patch(new PatchEntryCommand(owner(1), saved.entry.id(), saved.entry.revision(), null,
                    Map.of("category", "mood", "score", 5), null));
            return entries.delete(inv.getArgument(0), inv.getArgument(1), inv.getArgument(2));
        }).when(race).delete(any(), any(), anyLong());
        assertEquals("Отметка уже изменена", answer(flow(race, NOW).handleCallback(callback(1, 21, saved.cancelButton))));
        assertEquals(5, facts(1).getFirst().payload().get("score"));
    }

    @Test void failureBeforeDeleteDoesNotClaimSuccessAndRetryWorks() {
        Saved saved = save(flow(), 1, 10, 2, 2);
        EntryCoreService fault = mock(EntryCoreService.class, delegatesTo(entries));
        doThrow(new DataAccessResourceFailureException("synthetic unavailable")).when(fault).delete(any(), any(), anyLong());
        assertThrows(DataAccessResourceFailureException.class, () -> flow(fault, NOW).handleCallback(callback(1, 13, saved.cancelButton)));
        assertEquals(saved.entry, entries.requireEntry(owner(1), saved.entry.id()));
        assertEquals("Отметка отменена", answer(flow().handleCallback(callback(1, 13, saved.cancelButton))));
    }

    @Test void cancelCannotDeleteOtherEntryTypesOrNonQuickCheckinSources() {
        for (EntryType type : List.of(EntryType.NOTE, EntryType.CHECKIN)) {
            Map<String, Object> payload = type == EntryType.NOTE ? Map.of("text", "Заметка с ё 🙂") : Map.of("category", "mood", "score", 3);
            Entry draft = entries.createDraft(new CreateDraftCommand(owner(1), type, SourceKind.TEXT, Map.of(), NOW,
                    payload, Map.of(), new TelegramUpdateKey("main", type.ordinal() + 30))).entry();
            Entry confirmed = entries.confirm(new ConfirmEntryCommand(owner(1), draft.id(), UUID.randomUUID().toString(), draft.revision()));
            String cancel = "qc:" + confirmed.id() + ":" + confirmed.revision();
            assertEquals("Отметка недоступна", answer(flow().handleCallback(callback(1, 50, cancel))));
            assertEquals(confirmed, entries.requireEntry(owner(1), confirmed.id()));
        }
    }

    @Test void cancellingOldReceiptPreservesNewDraftOrTextClarification() {
        Saved saved = save(flow(), 1, 10, 0, 1);
        Entry draft = entries.createDraft(new CreateDraftCommand(owner(1), EntryType.NOTE, SourceKind.TEXT, Map.of(), NOW,
                Map.of("text", "Прошёл 🙂"), Map.of(), new TelegramUpdateKey("main", 20))).entry();
        DialogState pending = dialogs.save(new SaveDialogStateCommand(owner(1), draft.id(), "draft_review", Map.of(), new TelegramUpdateKey("main", 21)));
        flow().handleCallback(callback(1, 22, saved.cancelButton));
        assertEquals(pending, dialogs.find(owner(1)).orElseThrow());
        assertEquals(draft, entries.requireEntry(owner(1), draft.id()));
        DialogState clarification = dialogs.save(new SaveDialogStateCommand(owner(1), null, "text_clarification", Map.of("text", "ё 🙂"), new TelegramUpdateKey("main", 23)));
        flow().handleCallback(callback(1, 24, saved.cancelButton));
        assertEquals(clarification, dialogs.find(owner(1)).orElseThrow());
    }
}
