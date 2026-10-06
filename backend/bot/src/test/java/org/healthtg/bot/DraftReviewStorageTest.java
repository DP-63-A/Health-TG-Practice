package org.healthtg.bot;

import org.healthtg.core.dialog.DialogStateService;
import org.healthtg.core.entry.*;
import org.healthtg.user.UserService;
import org.junit.jupiter.api.*;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.boot.test.util.TestPropertyValues;
import org.springframework.context.annotation.AnnotationConfigApplicationContext;
import org.springframework.dao.DataAccessResourceFailureException;
import org.springframework.data.mongodb.core.MongoTemplate;
import org.testcontainers.containers.MongoDBContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.utility.DockerImageName;

import java.math.BigDecimal;
import java.net.URI;
import java.time.*;
import java.util.*;
import java.util.concurrent.atomic.AtomicBoolean;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;
import static org.mockito.AdditionalAnswers.delegatesTo;

@Testcontainers
class DraftReviewStorageTest {
    @ParameterizedTest @ValueSource(booleans = {false, true})
    void mealDecimalEditsPreserveBasisAndOtherNutrientsAfterRestart(boolean afterWrite) {
        Entry draft = entries.createDraft(new CreateDraftCommand(owner(), EntryType.MEAL, SourceKind.TEXT,
                Map.of(), SENT, Map.of("description", "Печёное яблоко 🍎", "mass_g", 100,
                "nutrients_basis", "per_100g", "nutrients", Map.of("energy_kcal", 50, "protein_g", 2, "fat_g", 1)),
                Map.of(), new TelegramUpdateKey("main", 10))).entry();
        flow().handleCallback(button(11, draft, "m"));
        flow().handleMessage(message(12, "125,75"));
        Entry massEdited = active();
        assertEquals(new BigDecimal("125.75"), massEdited.payload().get("mass_g"));
        assertEquals(Map.of("energy_kcal", 50, "protein_g", 2, "fat_g", 1), massEdited.payload().get("nutrients"));
        assertEquals("per_100g", massEdited.payload().get("nutrients_basis"));
        flow().handleCallback(button(13, massEdited, "e"));
        EntryCoreService broken = mock(EntryCoreService.class, delegatesTo(entries));
        doAnswer(invocation -> {
            if (afterWrite) entries.patch(invocation.getArgument(0));
            throw new DataAccessResourceFailureException("lost response");
        }).when(broken).patch(any());
        assertThrows(DataAccessResourceFailureException.class, () -> new CoreBotFlow(users, broken, dialogs,
                Clock.systemUTC()).handleMessage(message(14, "75,125")));
        context.close(); reopen();
        flow().handleMessage(message(14, "75,125"));
        flow().handleMessage(message(14, "75,125"));
        Entry edited = active();
        assertEquals(3, edited.revision());
        assertEquals("per_100g", edited.payload().get("nutrients_basis"));
        assertEquals(new BigDecimal("125.75"), edited.payload().get("mass_g"));
        Map<?, ?> nutrients = (Map<?, ?>) edited.payload().get("nutrients");
        assertEquals(new BigDecimal("75.125"), nutrients.get("energy_kcal"));
        assertEquals(2, nutrients.get("protein_g"));
        assertEquals(1, nutrients.get("fat_g"));
        assertFalse(nutrients.containsKey("carbs_g"));
        assertEquals("reported", edited.fieldOrigins().get("nutrients.energy_kcal"));
        assertEquals(SENT, edited.occurredAt());
    }

    @ParameterizedTest @ValueSource(strings = {"MEAL", "NOTE"})
    void mealAndNoteDateKeepLocalTimeAcrossOffsetChange(String type) {
        ZoneId zone = users.findOrCreate(1001).timezone();
        Instant original = LocalDate.of(2026, 10, 6).atTime(12, 34, 56).atZone(zone).toInstant();
        Entry draft = entries.createDraft(new CreateDraftCommand(owner(), EntryType.valueOf(type), SourceKind.TEXT,
                Map.of(), original, type.equals("MEAL") ? Map.of("description", "Печёное яблоко 🍎") : Map.of("text", "Заметка ё 🍎"),
                Map.of(), new TelegramUpdateKey("main", 10))).entry();
        flow().handleCallback(button(11, draft, "d"));
        flow().handleMessage(message(12, "26.10.2026"));
        Entry edited = active();
        assertEquals(LocalDate.of(2026, 10, 26).atTime(12, 34, 56), edited.occurredAt().atZone(zone).toLocalDateTime());
        assertEquals(draft.payload(), edited.payload());
        assertEquals(2, edited.revision());
        assertEquals("reported", edited.fieldOrigins().get("occurred_at"));
    }

    @ParameterizedTest @ValueSource(strings = {"29.03.2026", "25.10.2026"})
    void mealDateRejectsDstGapAndOverlapWithoutMutation(String date) {
        ZoneId zone = users.findOrCreate(1001).timezone();
        Instant original = LocalDate.of(2026, 10, 6).atTime(2, 30).atZone(zone).toInstant();
        Entry draft = entries.createDraft(new CreateDraftCommand(owner(), EntryType.MEAL, SourceKind.TEXT,
                Map.of(), original, Map.of("description", "Яблоко"), Map.of(), new TelegramUpdateKey("main", 10))).entry();
        flow().handleCallback(button(11, draft, "d"));
        var response = flow().handleMessage(message(12, date));
        assertTrue(((BotAction.SendInlineMessage) response.getFirst()).text().contains("Не удалось применить значение"));
        assertEquals(original, active().occurredAt());
        assertEquals(1, active().revision());
        flow().handleCallback(button(13, active(), "d"));
        flow().handleMessage(message(14, "26.10.2026"));
        assertEquals(2, active().revision());
    }

    @Container static final MongoDBContainer MONGO = new MongoDBContainer(
            DockerImageName.parse("mongodb/mongodb-community-server:8.0-ubi9-slim").asCompatibleSubstituteFor("mongo"));
    private static final Instant SENT = Instant.parse("2026-10-06T23:50:00Z");
    private AnnotationConfigApplicationContext context;
    private String database;
    private UserService users;
    private EntryCoreService entries;
    private DialogStateService dialogs;

    @BeforeEach void open() { database = "draft94_" + UUID.randomUUID().toString().replace("-", ""); reopen(); }
    private void reopen() {
        context = new AnnotationConfigApplicationContext();
        TestPropertyValues.of("test.mongo.uri=" + MONGO.getReplicaSetUrl(), "test.mongo.database=" + database).applyTo(context);
        context.register(BotCoreStorageTestConfiguration.class); context.refresh();
        users = context.getBean(UserService.class); entries = context.getBean(EntryCoreService.class);
        dialogs = context.getBean(DialogStateService.class);
    }
    @AfterEach void close() { context.getBean(MongoTemplate.class).getDb().drop(); context.close(); }
    private CoreBotFlow flow() { return new CoreBotFlow(users, entries, dialogs, Clock.systemUTC(), URI.create("https://example.test")); }
    private OwnerContext owner() { return new OwnerContext(users.findOrCreate(1001).id()); }
    private Entry active() { return entries.findActiveDraft(owner()).orElseThrow(); }
    private static BotUpdate message(long id, String text) { return new BotUpdate(id, BotUpdate.Kind.MESSAGE,
            BotUpdate.ChatType.PRIVATE, 1001, 1001L, false, text, List.of(), null, null, SENT); }
    private static BotUpdate button(long id, Entry entry, String action) { return new BotUpdate(id, BotUpdate.Kind.CALLBACK,
            BotUpdate.ChatType.PRIVATE, 1001, 1001L, false, null, List.of(), "callback-" + id,
            "dr:" + action + ":" + entry.id() + ":" + entry.revision()); }

    @ParameterizedTest @ValueSource(booleans = {false, true})
    void decimalPendingSurvivesRestartBeforeOrAfterPatch(boolean afterWrite) {
        flow().handleMessage(message(10, "06.10.2026 за день прошёл 8000 шагов"));
        Entry original = active();
        flow().handleCallback(button(11, original, "v"));
        EntryCoreService broken = mock(EntryCoreService.class, delegatesTo(entries));
        AtomicBoolean failed = new AtomicBoolean();
        doAnswer(invocation -> {
            PatchEntryCommand command = invocation.getArgument(0);
            if (!afterWrite && failed.compareAndSet(false, true)) throw new DataAccessResourceFailureException("before");
            Entry result = entries.patch(command);
            if (afterWrite && failed.compareAndSet(false, true)) throw new DataAccessResourceFailureException("after");
            return result;
        }).when(broken).patch(any());
        var interrupted = new CoreBotFlow(users, broken, dialogs, Clock.systemUTC());
        assertThrows(DataAccessResourceFailureException.class, () -> interrupted.handleMessage(message(12, "123,456789")));
        assertEquals("draft_pending", dialogs.find(owner()).orElseThrow().step());
        context.close(); reopen();
        flow().handleMessage(message(12, "123,456789"));
        Entry patched = active();
        assertEquals(new BigDecimal("123.456789"), patched.payload().get("value"));
        context.getBean(MongoTemplate.class).updateFirst(
                org.springframework.data.mongodb.core.query.Query.query(
                        org.springframework.data.mongodb.core.query.Criteria.where("_id").is(patched.id().toString())),
                org.springframework.data.mongodb.core.query.Update.update("payload.value",
                        new org.bson.types.Decimal128(new BigDecimal("123.456789"))), "entries");
        assertEquals(new BigDecimal("123.456789"), active().payload().get("value"));
        assertEquals(2, patched.revision());
        assertEquals(SENT, patched.occurredAt());
        flow().handleMessage(message(12, "123,456789"));
        assertEquals(2, active().revision());
        flow().handleCallback(button(13, patched, "s"));
        flow().handleCallback(button(13, patched, "s"));
        assertEquals(3, entries.requireEntry(owner(), patched.id()).revision());
        flow().handleMessage(message(14, "06.10.2026 за день прошёл 100 шагов"));
        assertNotEquals(original.id(), active().id());
    }

    @Test void staleFieldCannotOverwriteMiniAppAndUnknownTimeStaysUnknown() {
        var card = (BotAction.SendInlineMessage) flow().handleMessage(message(10, "06.10.2026 пульс 72 ударов в минуту")).getFirst();
        Entry draft = active();
        assertTrue(card.rows().stream().flatMap(List::stream).anyMatch(b -> b.webAppUrl() != null
                && b.webAppUrl().getPath().equals("/diary/" + draft.id())));
        flow().handleCallback(button(11, draft, "v"));
        entries.patch(new PatchEntryCommand(owner(), draft.id(), draft.revision(), null, Map.of("value", 80), null));
        flow().handleMessage(message(12, "99"));
        assertEquals(80, active().payload().get("value"));
        flow().handleCallback(button(13, active(), "d"));
        flow().handleMessage(message(14, "05.10.2026"));
        Entry moved = active();
        assertEquals("2026-10-05", moved.payload().get("local_date"));
        assertNull(moved.payload().get("local_time"));
        assertEquals(SENT, moved.occurredAt());
        assertThrows(EntryValidationException.class, () -> entries.patch(new PatchEntryCommand(owner(), moved.id(),
                moved.revision(), SENT.plusSeconds(60), null, null)));
        assertEquals(1, entries.listEntries(new ListEntriesQuery(owner(), EntryStatus.DRAFT, EntryType.METRICS,
                LocalDate.of(2026,10,5), LocalDate.of(2026,10,5), ZoneId.of("Pacific/Honolulu"))).size());
    }

    @Test void fieldInputSurvivesRestartAndValidatesUnicodeWithoutMutation() {
        flow().handleMessage(message(10, "06.10.2026 за день прошёл 8000 шагов"));
        flow().handleCallback(button(11, active(), "v"));
        context.close(); reopen();
        var invalid = (BotAction.SendInlineMessage) flow().handleMessage(message(12, "ёжик 🍎")).getFirst();
        assertTrue(invalid.text().contains("Введите одно число"));
        assertEquals(1, active().revision());
        flow().handleMessage(message(13, "1".repeat(2001)));
        assertEquals(1, active().revision());
        flow().handleCallback(button(14, active(), "b"));
        flow().handleMessage(message(15, "100"));
        assertEquals(1, active().revision());
    }

    @Test void foreignAndMalformedCallbacksCannotReadOrMutateDraft() {
        flow().handleMessage(message(10, "06.10.2026 пульс 72 ударов в минуту"));
        Entry draft = active();
        var foreign = new BotUpdate(11, BotUpdate.Kind.CALLBACK, BotUpdate.ChatType.PRIVATE,
                2002, 2002L, false, null, List.of(), "foreign", "dr:s:" + draft.id() + ":1");
        var result = flow().handleCallback(foreign);
        assertTrue(result.stream().noneMatch(BotAction.SendInlineMessage.class::isInstance));
        for (String data : List.of("dr:s:broken:1", "dr:s:" + draft.id() + ":-1", "dr:s:" + draft.id() + ":999")) {
            flow().handleCallback(new BotUpdate(12, BotUpdate.Kind.CALLBACK, BotUpdate.ChatType.PRIVATE,
                    1001, 1001L, false, null, List.of(), "bad", data));
        }
        assertEquals(EntryStatus.DRAFT, active().status());
        assertEquals(1, active().revision());
    }

    @Test void cancelledButtonsCannotAffectNextDraft() {
        flow().handleMessage(message(10, "06.10.2026 пульс 72 ударов в минуту"));
        Entry old = active();
        flow().handleCallback(button(11, old, "x"));
        flow().handleMessage(message(12, "06.10.2026 пульс 81 ударов в минуту"));
        Entry next = active();
        for (String action : List.of("s", "x", "v", "d")) flow().handleCallback(button(13, old, action));
        assertEquals(next.id(), active().id());
        assertEquals(1, active().revision());
        assertEquals(EntryStatus.CANCELLED, entries.requireEntry(owner(), old.id()).status());
    }

    @ParameterizedTest @ValueSource(booleans = {false, true})
    void confirmationRecoversBeforeAndAfterLostResponse(boolean afterWrite) {
        flow().handleMessage(message(10, "06.10.2026 пульс 72 ударов в минуту"));
        Entry draft = active();
        EntryCoreService broken = mock(EntryCoreService.class, delegatesTo(entries));
        doAnswer(invocation -> {
            if (afterWrite) entries.confirm(invocation.getArgument(0));
            throw new DataAccessResourceFailureException("lost response");
        }).when(broken).confirm(any());
        assertThrows(DataAccessResourceFailureException.class, () -> new CoreBotFlow(users, broken, dialogs,
                Clock.systemUTC()).handleCallback(button(11, draft, "s")));
        context.close(); reopen();
        flow().handleCallback(button(11, draft, "s"));
        flow().handleCallback(button(12, draft, "s"));
        Entry saved = entries.requireEntry(owner(), draft.id());
        assertEquals(EntryStatus.CONFIRMED, saved.status());
        assertEquals(2, saved.revision());
        assertEquals(SENT, saved.occurredAt());
    }

    @Test void concurrentConfirmationCreatesOnlyOneRevision() throws Exception {
        flow().handleMessage(message(10, "06.10.2026 пульс 72 ударов в минуту"));
        Entry draft = active();
        var sharedFlow = flow();
        var start = new java.util.concurrent.CountDownLatch(1);
        try (var pool = java.util.concurrent.Executors.newFixedThreadPool(2)) {
            var first = pool.submit(() -> { start.await(); return sharedFlow.handleCallback(button(11, draft, "s")); });
            var second = pool.submit(() -> { start.await(); return sharedFlow.handleCallback(button(12, draft, "s")); });
            start.countDown();
            first.get(); second.get();
        }
        assertEquals(2, entries.requireEntry(owner(), draft.id()).revision());
        assertEquals(EntryStatus.CONFIRMED, entries.requireEntry(owner(), draft.id()).status());
    }

    @Test void clarificationAfterRestartKeepsOriginalReportAndDecimal() {
        flow().handleMessage(message(10, "пульс 72,5 ударов в минуту"));
        assertTrue(entries.findActiveDraft(owner()).isEmpty());
        context.close(); reopen();
        var reply = new BotUpdate(11, BotUpdate.Kind.MESSAGE, BotUpdate.ChatType.PRIVATE,
                1001, 1001L, false, "06.10.2026", List.of(), null, null, SENT.plusSeconds(86400));
        flow().handleMessage(reply);
        Entry draft = active();
        assertEquals(SENT, draft.occurredAt());
        assertEquals(new BigDecimal("72.5"), draft.payload().get("value"));
        flow().handleMessage(reply);
        assertEquals(draft.id(), active().id());
        assertEquals(1, active().revision());
    }

    @ParameterizedTest @ValueSource(strings = {"sleep_duration_min", "heart_rate"})
    void metricDateCorrectionPreservesKnownTimeAndReportMoment(String code) {
        Entry draft = entries.createDraft(new CreateDraftCommand(owner(), EntryType.METRICS, SourceKind.TEXT,
                Map.of(), SENT, Map.of("code", code, "value", 70, "unit", code.equals("heart_rate") ? "bpm" : "min",
                "local_date", "2026-10-06", "local_time", "07:15:00"), Map.of(), new TelegramUpdateKey("main", 10))).entry();
        flow().handleCallback(button(11, draft, "d"));
        flow().handleMessage(message(12, "25.10.2026"));
        Entry moved = active();
        assertEquals("2026-10-25", moved.payload().get("local_date"));
        assertEquals("07:15:00", moved.payload().get("local_time"));
        assertEquals(SENT, moved.occurredAt());
        assertEquals(2, moved.revision());
    }

    @ParameterizedTest @ValueSource(booleans = {false, true})
    void corruptPendingDoesNotTrapAllFutureInput(boolean callbackFirst) {
        flow().handleMessage(message(10, "06.10.2026 пульс 72 ударов в минуту"));
        Entry draft = active();
        dialogs.save(new org.healthtg.core.dialog.SaveDialogStateCommand(owner(), draft.id(), "draft_pending",
                Map.of("schema_version", 1, "operation", "patch", "timezone", "invalid-zone"),
                new TelegramUpdateKey("main", 11)));
        if (callbackFirst) assertDoesNotThrow(() -> flow().handleCallback(button(12, draft, "r")));
        else assertDoesNotThrow(() -> flow().handleMessage(message(12, "Обновить")));
        assertDoesNotThrow(() -> flow().handleCallback(button(13, draft, "r")));
        assertEquals(1, active().revision());
    }

    @ParameterizedTest @ValueSource(strings = {"missing_revision", "invalid_timezone", "future_schema", "invalid_decimal"})
    void corruptAwaitOrPendingCannotMutateEntry(String corruption) {
        flow().handleMessage(message(10, "06.10.2026 пульс 72 ударов в минуту"));
        Entry draft = active();
        Map<String, Object> state = new HashMap<>(Map.of("schema_version", 1, "entry_revision", 1,
                "timezone", "Europe/Vilnius", "field", "v", "operation", "patch", "value", "72"));
        switch (corruption) {
            case "missing_revision" -> state.remove("entry_revision");
            case "invalid_timezone" -> state.put("timezone", "invalid-zone");
            case "future_schema" -> state.put("schema_version", 999);
            case "invalid_decimal" -> state.put("value", "not-a-number");
        }
        dialogs.save(new org.healthtg.core.dialog.SaveDialogStateCommand(owner(), draft.id(),
                corruption.equals("invalid_decimal") ? "draft_pending" : "draft_await", state,
                new TelegramUpdateKey("main", 11)));
        assertDoesNotThrow(() -> flow().handleMessage(message(12, "99")));
        assertEquals(1, active().revision());
        assertDoesNotThrow(() -> flow().handleCallback(button(13, draft, "r")));
    }
}
