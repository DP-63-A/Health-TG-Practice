package org.healthtg.bot;

import org.healthtg.core.dialog.DialogStateService;
import org.healthtg.core.entry.*;
import org.healthtg.user.UserService;
import org.junit.jupiter.api.*;
import org.springframework.boot.test.util.TestPropertyValues;
import org.springframework.context.annotation.AnnotationConfigApplicationContext;
import org.springframework.dao.DataAccessResourceFailureException;
import org.springframework.data.mongodb.core.MongoTemplate;
import org.testcontainers.containers.MongoDBContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.utility.DockerImageName;

import java.time.*;
import java.util.*;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;
import static org.mockito.AdditionalAnswers.delegatesTo;

@Testcontainers
class TextDialogStorageTest {
    @Container static final MongoDBContainer MONGO = new MongoDBContainer(
            DockerImageName.parse("mongodb/mongodb-community-server:8.0-ubi9-slim").asCompatibleSubstituteFor("mongo"));
    private static final Instant SENT = Instant.parse("2026-10-07T08:00:00Z");
    private AnnotationConfigApplicationContext context;
    private String database;
    private EntryCoreService entries;
    private DialogStateService dialogs;
    private UserService users;

    @BeforeEach void open() { database = "text7_" + UUID.randomUUID().toString().replace("-", ""); reopen(); }
    private void reopen() {
        context = new AnnotationConfigApplicationContext();
        TestPropertyValues.of("test.mongo.uri=" + MONGO.getReplicaSetUrl(), "test.mongo.database=" + database).applyTo(context);
        context.register(BotCoreStorageTestConfiguration.class); context.refresh();
        entries = context.getBean(EntryCoreService.class); dialogs = context.getBean(DialogStateService.class);
        users = context.getBean(UserService.class);
    }
    @AfterEach void close() { context.getBean(MongoTemplate.class).getDb().drop(); context.close(); }
    private CoreBotFlow flow() { return new CoreBotFlow(users, entries, dialogs, Clock.fixed(SENT, ZoneOffset.UTC)); }
    private OwnerContext owner() { return new OwnerContext(users.findOrCreate(1001).id()); }
    private Entry active() { return entries.findActiveDraft(owner()).orElseThrow(); }
    private BotUpdate msg(long id, String text) { return new BotUpdate(id, BotUpdate.Kind.MESSAGE, BotUpdate.ChatType.PRIVATE,
            1001, 1001L, false, text, List.of(), null, null, SENT.plusSeconds(id)); }
    private BotUpdate cb(long id, String data) { return new BotUpdate(id, BotUpdate.Kind.CALLBACK, BotUpdate.ChatType.PRIVATE,
            1001, 1001L, false, null, List.of(), "cb" + id, data); }
    private String button(List<BotAction> actions, String label) {
        return actions.stream().filter(BotAction.SendInlineMessage.class::isInstance).map(BotAction.SendInlineMessage.class::cast)
                .flatMap(m -> m.rows().stream()).flatMap(List::stream).filter(b -> b.text().equals(label))
                .map(BotAction.InlineButton::callbackData).findFirst().orElseThrow();
    }
    private String text(List<BotAction> actions) { return actions.stream().filter(BotAction.SendInlineMessage.class::isInstance)
            .map(BotAction.SendInlineMessage.class::cast).map(BotAction.SendInlineMessage::text).reduce("", String::concat); }

    @Test void noteRequiresConsentAndExplicitDateTimeAndKeepsOriginalTextAcrossRestart() {
        String input = "Было тяжело сосредоточиться, всё ещё устал";
        var offer = flow().handleMessage(msg(10, input));
        assertTrue(entries.findActiveDraft(owner()).isEmpty());
        flow().handleCallback(cb(11, button(offer, "Создать заметку")));
        assertTrue(entries.findActiveDraft(owner()).isEmpty());
        assertTrue(text(flow().handleMessage(msg(12, "31.02.2026"))).contains("Несуществующая дата"));
        flow().handleMessage(msg(13, "06.10.2026"));
        context.close(); reopen();
        assertTrue(text(flow().handleMessage(msg(14, "25:00"))).contains("Введите время"));
        flow().handleMessage(msg(15, "14:30"));
        Entry draft = active();
        assertEquals(EntryType.NOTE, draft.type()); assertEquals(EntryStatus.DRAFT, draft.status());
        assertEquals(input, draft.payload().get("text"));
        assertEquals(LocalDateTime.of(2026, 10, 6, 14, 30), draft.occurredAt().atZone(users.findOrCreate(1001).timezone()).toLocalDateTime());
        assertEquals(10L, ((Number) draft.sourceRef().get("telegram_update_id")).longValue());
        assertEquals("reported", draft.fieldOrigins().get("occurred_at"));
        flow().handleMessage(msg(15, "14:30")); assertEquals(draft.id(), active().id());
        assertTrue(entries.listEntries(new ListEntriesQuery(owner(), EntryStatus.CONFIRMED, null, null, null, ZoneOffset.UTC)).isEmpty());
        flow().handleCallback(cb(16, "dr:s:" + draft.id() + ":1"));
        assertEquals(1, entries.listEntries(new ListEntriesQuery(owner(), EntryStatus.CONFIRMED, EntryType.NOTE, null, null, ZoneOffset.UTC)).size());
    }

    @Test void clarificationAsksOnlyMissingFieldsAndDoesNotAppendReplies() {
        var first = flow().handleMessage(msg(10, "пульс 72,5"));
        assertTrue(text(first).contains("дату"));
        flow().handleMessage(msg(11, "06.10.2026"));
        flow().handleMessage(msg(11, "06.10.2026")); // replay must not become the unit
        assertTrue(text(flow().handleMessage(msg(12, "кг"))).contains("единицу пульса"));
        context.close(); reopen();
        flow().handleMessage(msg(13, "ударов в минуту"));
        Entry draft = active();
        assertEquals("2026-10-06", draft.payload().get("local_date"));
        assertEquals("bpm", draft.payload().get("unit"));
        assertEquals(new java.math.BigDecimal("72.5"), draft.payload().get("value"));
        assertEquals(SENT.plusSeconds(10), draft.occurredAt());
        assertEquals(10L, ((Number) draft.sourceRef().get("telegram_update_id")).longValue());
    }

    @Test void stateAndNewTextCannotSilentlyReplaceNoteOfferAndOldButtonsAreHarmless() {
        var offer = flow().handleMessage(msg(10, "Устал после работы"));
        var saved = dialogs.find(owner()).orElseThrow();
        flow().beginCheckin(msg(11, "/state")); flow().handleMessage(msg(12, "Другой текст"));
        assertEquals(saved, dialogs.find(owner()).orElseThrow());
        String old = button(offer, "Создать заметку");
        flow().handleCallback(cb(13, button(offer, "Отменить ввод")));
        assertTrue(entries.findActiveDraft(owner()).isEmpty());
        var next = flow().handleMessage(msg(14, "Новая заметка"));
        var newer = dialogs.find(owner()).orElseThrow();
        flow().handleCallback(cb(15, old)); assertEquals(newer, dialogs.find(owner()).orElseThrow());
        flow().handleCallback(cb(16, button(next, "Создать заметку")));
        assertEquals("text_clarification", dialogs.find(owner()).orElseThrow().step());
    }

    @Test void explicitReplacementEscapesAmbiguousTextWithoutRetainingOldNumbers() {
        var reply = flow().handleMessage(msg(10, "06.10.2026 пульс 70 или 80 ударов в минуту"));
        assertTrue(entries.findActiveDraft(owner()).isEmpty());
        flow().handleCallback(cb(11, button(reply, "Ввести заново")));
        flow().handleMessage(msg(12, "06.10.2026 пульс 75 ударов в минуту"));
        assertEquals(75, ((Number) active().payload().get("value")).intValue());
        assertEquals(SENT.plusSeconds(12), active().occurredAt());
    }

    @Test void limitIsPerMessageAndInvalidUnicodeInputDoesNotDestroyPendingNote() {
        String input = "ё".repeat(2000);
        var offer = flow().handleMessage(msg(10, input));
        var saved = dialogs.find(owner()).orElseThrow();
        assertTrue(text(flow().handleMessage(msg(11, "я".repeat(2001)))).contains("2000"));
        assertTrue(text(flow().handleMessage(msg(12, "🙂"))).contains("Эмодзи"));
        assertEquals(saved, dialogs.find(owner()).orElseThrow());
        flow().handleCallback(cb(13, button(offer, "Создать заметку")));
        flow().handleMessage(msg(14, "07.10.2026")); flow().handleMessage(msg(15, "10:00"));
        assertEquals(input, active().payload().get("text"));
    }

    @Test void lostCreateResponseRecoversOneNoteWithoutAppendingTimeToText() {
        var offer = flow().handleMessage(msg(10, "Мне спокойнее"));
        flow().handleCallback(cb(11, button(offer, "Создать заметку")));
        flow().handleMessage(msg(12, "07.10.2026"));
        EntryCoreService failing = mock(EntryCoreService.class, delegatesTo(entries));
        doAnswer(inv -> { entries.createDraft(inv.getArgument(0)); throw new DataAccessResourceFailureException("lost"); })
                .when(failing).createDraft(any());
        assertThrows(DataAccessResourceFailureException.class, () -> new CoreBotFlow(users, failing, dialogs, Clock.systemUTC())
                .handleMessage(msg(13, "10:00")));
        UUID id = active().id(); context.close(); reopen();
        flow().handleMessage(msg(13, "10:00")); assertEquals(id, active().id());
        assertEquals("Мне спокойнее", active().payload().get("text"));
        flow().handleCallback(cb(14, "dr:x:" + id + ":1"));
        assertTrue(entries.findActiveDraft(owner()).isEmpty());
        flow().handleMessage(msg(15, "07.10.2026 за день 8000 шагов"));
        assertNotEquals(id, active().id());
    }

    @Test void anotherOwnerCannotUseNoteConsentButton() {
        var offer = flow().handleMessage(msg(10, "Устал после работы"));
        var original = dialogs.find(owner()).orElseThrow();
        var foreignOwner = new OwnerContext(users.findOrCreate(2002).id());
        var foreignMessage = new BotUpdate(11, BotUpdate.Kind.MESSAGE, BotUpdate.ChatType.PRIVATE,
                2002, 2002L, false, "Моя заметка", List.of(), null, null, SENT);
        flow().handleMessage(foreignMessage);
        var foreign = dialogs.find(foreignOwner).orElseThrow();
        flow().handleCallback(new BotUpdate(12, BotUpdate.Kind.CALLBACK, BotUpdate.ChatType.PRIVATE,
                2002, 2002L, false, null, List.of(), "cb12", button(offer, "Создать заметку")));
        assertEquals(original, dialogs.find(owner()).orElseThrow());
        assertEquals(foreign, dialogs.find(foreignOwner).orElseThrow());
        assertTrue(entries.findActiveDraft(foreignOwner).isEmpty());
    }

    @Test void corruptPersistedNumberResetsDialogWithoutCreatingEntry() {
        flow().handleMessage(msg(10, "пульс 72"));
        var state = dialogs.find(owner()).orElseThrow();
        var data = new LinkedHashMap<>(state.context());
        data.put("payload", Map.of("code", "heart_rate", "value", "broken"));
        dialogs.save(new org.healthtg.core.dialog.SaveDialogStateCommand(owner(), null, state.step(), data,
                new TelegramUpdateKey("main", 11)));
        assertTrue(text(flow().handleMessage(msg(12, "07.10.2026"))).contains("Не удалось восстановить"));
        assertEquals("idle", dialogs.find(owner()).orElseThrow().step());
        assertTrue(entries.findActiveDraft(owner()).isEmpty());
    }

    @Test void noteWithExplicitDateTimeCreatesOnlyAfterConsentAndRetriesLostCallback() {
        String input = "06.10.2026 в 14:30 чувствовал усталость";
        var offer = flow().handleMessage(msg(10, input));
        assertTrue(entries.findActiveDraft(owner()).isEmpty());
        String consent = button(offer, "Создать заметку");
        EntryCoreService failing = mock(EntryCoreService.class, delegatesTo(entries));
        doAnswer(inv -> { entries.createDraft(inv.getArgument(0)); throw new DataAccessResourceFailureException("lost"); })
                .when(failing).createDraft(any());
        assertThrows(DataAccessResourceFailureException.class, () -> new CoreBotFlow(users, failing, dialogs, Clock.systemUTC())
                .handleCallback(cb(11, consent)));
        UUID id = active().id();
        flow().handleCallback(cb(11, consent));
        assertEquals(id, active().id());
        assertEquals(input, active().payload().get("text"));
        assertEquals(EntryStatus.DRAFT, active().status());
        flow().handleCallback(cb(12, "dr:s:" + id + ":1"));
        assertTrue(entries.findActiveDraft(owner()).isEmpty());
        assertEquals(1, entries.listEntries(new ListEntriesQuery(owner(), EntryStatus.CONFIRMED, EntryType.NOTE,
                null, null, ZoneOffset.UTC)).size());
    }

    @Test void noteDstOverlapIsNotSilentlyResolvedAndCanBeCorrected() {
        var offer = flow().handleMessage(msg(10, "Неспокойный сон"));
        flow().handleCallback(cb(11, button(offer, "Создать заметку")));
        flow().handleMessage(msg(12, "25.10.2026"));
        assertTrue(text(flow().handleMessage(msg(13, "02:30"))).contains("неоднозначно"));
        assertTrue(entries.findActiveDraft(owner()).isEmpty());
        flow().handleMessage(msg(14, "04:30")); assertEquals(EntryType.NOTE, active().type());
    }
}
