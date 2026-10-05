package org.healthtg.bot;

import org.healthtg.core.dialog.*;
import org.healthtg.core.entry.*;
import org.healthtg.user.UserService;
import org.junit.jupiter.api.*;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import org.springframework.boot.test.util.TestPropertyValues;
import org.springframework.context.annotation.AnnotationConfigApplicationContext;
import org.springframework.dao.DataAccessResourceFailureException;
import org.springframework.data.mongodb.core.MongoTemplate;
import org.testcontainers.containers.MongoDBContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.utility.DockerImageName;
import java.time.Clock;
import java.util.*;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.stream.Stream;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;
import static org.mockito.AdditionalAnswers.delegatesTo;

/** Real storage acceptance: recover a partial write, then finish the user's next task. */
@Testcontainers
class Pr76RecoveryRegressionTest {
    @Container static final MongoDBContainer MONGO = new MongoDBContainer(
            DockerImageName.parse("mongodb/mongodb-community-server:8.0-ubi9-slim")
                    .asCompatibleSubstituteFor("mongo"));
    private String database;
    private AnnotationConfigApplicationContext context;
    private UserService users;
    private EntryCoreService entries;
    private DialogStateService dialogs;

    @BeforeEach void open() {
        database = "draft_recovery_" + UUID.randomUUID().toString().replace("-", "");
        reopen();
    }
    private void reopen() {
        context = new AnnotationConfigApplicationContext();
        TestPropertyValues.of("test.mongo.uri=" + MONGO.getReplicaSetUrl(),
                "test.mongo.database=" + database).applyTo(context);
        context.register(BotCoreStorageTestConfiguration.class);
        context.refresh();
        users = context.getBean(UserService.class);
        entries = context.getBean(EntryCoreService.class);
        dialogs = context.getBean(DialogStateService.class);
    }
    @AfterEach void close() {
        context.getBean(MongoTemplate.class).getDb().drop();
        context.close();
    }
    private CoreBotFlow flow() { return new CoreBotFlow(users, entries, dialogs, Clock.systemUTC()); }
    private OwnerContext owner() { return new OwnerContext(users.findOrCreate(1001L).id()); }
    private DialogState state() { return dialogs.find(owner()).orElseThrow(); }
    private static BotUpdate message(long id, String text) {
        return new BotUpdate(id, BotUpdate.Kind.MESSAGE, BotUpdate.ChatType.PRIVATE,
                1001L, 1001L, false, text, List.of(), null, null);
    }
    private static BotUpdate cancel(long id, Entry draft) {
        return new BotUpdate(id, BotUpdate.Kind.CALLBACK, BotUpdate.ChatType.PRIVATE,
                1001L, 1001L, false, null, List.of(), "cancel-" + id,
                "cancel:" + draft.id() + ":" + draft.revision());
    }
    static Stream<Arguments> failures() {
        return Stream.of(false, true).flatMap(clarification -> Stream.of(false, true)
                .flatMap(afterCreate -> Stream.of(false, true)
                        .map(restart -> Arguments.of(clarification, afterCreate, restart))));
    }

    @ParameterizedTest(name = "clarification={0}, afterCreate={1}, restart={2}")
    @MethodSource("failures")
    void partialDraftRecoversWithoutDuplicatesAndCancelAllowsIndependentInput(
            boolean clarification, boolean afterCreate, boolean restart) {
        if (clarification) flow().handleMessage(message(10, "24.09.2026 пульс 72"));
        BotUpdate event = message(11, clarification ? "ударов в минуту"
                : "24.09.2026 за день прошёл 8000 шагов");
        EntryCoreService interruptedEntries = mock(EntryCoreService.class, delegatesTo(entries));
        DialogStateService interruptedDialogs = mock(DialogStateService.class, delegatesTo(dialogs));
        AtomicBoolean failed = new AtomicBoolean();
        if (afterCreate) {
            doAnswer(inv -> {
                var result = entries.createDraft(inv.getArgument(0));
                if (failed.compareAndSet(false, true)) throw new DataAccessResourceFailureException("after create");
                return result;
            }).when(interruptedEntries).createDraft(any());
        } else {
            doAnswer(inv -> {
                if (failed.compareAndSet(false, true)) throw new DataAccessResourceFailureException("before dialog");
                return dialogs.save(inv.getArgument(0));
            }).when(interruptedDialogs).save(any());
        }
        CoreBotFlow subject = new CoreBotFlow(users, interruptedEntries, interruptedDialogs, Clock.systemUTC());
        assertThrows(DataAccessResourceFailureException.class, () -> subject.handleMessage(event));
        Entry saved = entries.findActiveDraft(owner()).orElseThrow();
        if (restart) { context.close(); reopen(); }
        CoreBotFlow recovered = restart ? flow() : subject;
        recovered.handleMessage(event);
        assertEquals("draft_review", state().step());
        assertEquals(saved.id(), state().activeEntryId());
        DialogState completed = state();
        recovered.handleMessage(event);
        assertEquals(completed, state(), "Repeated recovery must be idempotent");
        assertEquals(saved.id(), entries.findActiveDraft(owner()).orElseThrow().id());
        recovered.handleCallback(cancel(12, saved));
        assertEquals("idle", state().step());
        assertTrue(entries.findActiveDraft(owner()).isEmpty());
        recovered.handleMessage(message(13, "25.09.2026 за день прошёл 9000 шагов"));
        Entry next = entries.findActiveDraft(owner()).orElseThrow();
        assertNotEquals(saved.id(), next.id());
        assertEquals("steps", next.payload().get("code"));
        assertEquals(0, new java.math.BigDecimal("9000").compareTo(
                new java.math.BigDecimal(next.payload().get("value").toString())));
        DialogState newer = state();
        recovered.handleCallback(cancel(14, saved));
        assertEquals(newer, state(), "Stale cancel must leave the new draft intact");
    }

    @Test void originalReplayCannotOverwriteNewerDialogAndUnrelatedInputCannotRepairIt() {
        BotUpdate original = message(20, "24.09.2026 за день прошёл 8000 шагов");
        flow().handleMessage(original);
        Entry draft = entries.findActiveDraft(owner()).orElseThrow();
        dialogs.save(new SaveDialogStateCommand(owner(), null, "text_clarification",
                Map.of("original_text", "newer"), new TelegramUpdateKey("main", 30)));
        DialogState newer = state();
        flow().handleMessage(original);
        assertEquals(newer, state(), "Recovery uses original ordering, not the newest event id");
        flow().handleMessage(message(40, "совсем другой ввод"));
        assertEquals(newer, state(), "An unrelated event cannot adopt an existing draft");
        assertEquals(draft.id(), entries.findActiveDraft(owner()).orElseThrow().id());
    }
}


