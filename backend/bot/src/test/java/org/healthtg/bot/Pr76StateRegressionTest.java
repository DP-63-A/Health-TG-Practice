package org.healthtg.bot;

import org.healthtg.core.dialog.*;
import org.healthtg.core.entry.*;
import org.healthtg.user.UserService;
import org.junit.jupiter.api.*;
import org.springframework.boot.test.util.TestPropertyValues;
import org.springframework.context.annotation.AnnotationConfigApplicationContext;
import org.springframework.data.mongodb.core.MongoTemplate;
import org.testcontainers.containers.MongoDBContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.utility.DockerImageName;
import java.time.Clock;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicBoolean;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;
import static org.mockito.AdditionalAnswers.delegatesTo;

/** Independent acceptance tests: actual Mongo services, no Telegram transport. */
@Testcontainers
class Pr76StateRegressionTest {
    @Container static final MongoDBContainer MONGO = new MongoDBContainer(
        DockerImageName.parse("mongodb/mongodb-community-server:8.0-ubi9-slim").asCompatibleSubstituteFor("mongo"));
    String database;
    AnnotationConfigApplicationContext context;
    UserService users;
    EntryCoreService entries;
    DialogStateService dialogs;
    @BeforeEach void open() {
        database = "pr76_regression_" + UUID.randomUUID().toString().replace("-", "");
        reopen();
    }
    void reopen() {
        context = new AnnotationConfigApplicationContext();
        TestPropertyValues.of("test.mongo.uri=" + MONGO.getReplicaSetUrl(), "test.mongo.database=" + database).applyTo(context);
        context.register(BotCoreStorageTestConfiguration.class); context.refresh();
        users = context.getBean(UserService.class); entries = context.getBean(EntryCoreService.class);
        dialogs = context.getBean(DialogStateService.class);
    }
    @AfterEach void close() { context.getBean(MongoTemplate.class).getDb().drop(); context.close(); }
    CoreBotFlow flow() { return new CoreBotFlow(users, entries, dialogs, context.getBean(Clock.class)); }
    OwnerContext owner(long user) { return new OwnerContext(users.findOrCreate(user).id()); }
    DialogState state(long user) { return dialogs.find(owner(user)).orElseThrow(); }
    static BotUpdate msg(long user, long update, String text) {
        return new BotUpdate(update, BotUpdate.Kind.MESSAGE, BotUpdate.ChatType.PRIVATE, user, user, false, text, List.of(), null, null,
                java.time.Instant.parse("2026-09-25T08:00:00Z"));
    }
    static BotUpdate cb(long user, long update, String data) {
        return new BotUpdate(update, BotUpdate.Kind.CALLBACK, BotUpdate.ChatType.PRIVATE, user, user, false, null, List.of(), "cb"+update, data);
    }
    static BotAction.SendInlineMessage keyboard(List<BotAction> actions) {
        return actions.stream().filter(BotAction.SendInlineMessage.class::isInstance).map(BotAction.SendInlineMessage.class::cast).findFirst().orElseThrow();
    }
    static String button(List<BotAction> actions) { return keyboard(actions).rows().getFirst().getFirst().callbackData(); }
    static String token(String button) { return button.substring(2, 34); }
    static String storedToken(DialogState state) { return state.context().get("selector_id").toString().replace("-", ""); }
    String draft(CoreBotFlow flow, long user, long update) {
        flow.handleMessage(msg(user, update, "24.09.2026 за день прошёл 8000 шагов"));
        Entry draft = entries.findActiveDraft(owner(user)).orElseThrow();
        // These regressions exercise cancellation buttons sent before draft-review was introduced.
        return "cancel:" + draft.id() + ":" + draft.revision();
    }

    @Test void repeatedStartAcrossTwoContextRestartsKeepsUsableButtons() {
        String first = button(flow().beginCheckin(msg(1101, 10, "/state")));
        context.close(); reopen();
        String replay = button(flow().beginCheckin(msg(1101, 10, "/state")));
        assertEquals(first, replay, "Repeated update must render the persisted selector token");
        context.close(); reopen();
        String score = button(flow().handleCallback(cb(1101, 11, replay)));
        assertTrue(score.endsWith(":v1"));
        assertEquals("checkin_score", state(1101).step());
    }

    @Test void replayAfterCategoryDoesNotRollbackToCategory() {
        CoreBotFlow f = flow(); String category = button(f.beginCheckin(msg(1102, 10, "/state")));
        String score = button(f.handleCallback(cb(1102, 11, category)));
        DialogState before = state(1102);
        assertEquals(score, button(flow().beginCheckin(msg(1102, 10, "/state"))));
        assertEquals(before, state(1102));
    }

    @Test void staleLocalSelectorMustNotProduceUnsavedToken() {
        CoreBotFlow stale = flow(); String old = button(stale.beginCheckin(msg(1103, 10, "/state")));
        String current = button(flow().beginCheckin(msg(1103, 20, "/state")));
        assertNotEquals(old, current);
        assertEquals(current, button(stale.beginCheckin(msg(1103, 10, "/state"))));
        assertEquals(storedToken(state(1103)), token(current));
    }

    @Test void staleCachedCallbackCannotOverwriteCurrentSession() {
        CoreBotFlow stale = flow(); String old = button(stale.beginCheckin(msg(1104, 10, "/state")));
        flow().beginCheckin(msg(1104, 20, "/state")); DialogState current = state(1104);
        stale.handleCallback(cb(1104, 21, old));
        assertEquals(current, state(1104), "Old cached session must not replace newer persisted session");
    }

    @Test void genuinelyNewStartAfterCompletionCreatesNewToken() {
        CoreBotFlow f = flow(); String category = button(f.beginCheckin(msg(1105, 10, "/state")));
        String score = button(f.handleCallback(cb(1105, 11, category)));
        f.handleCallback(cb(1105, 12, score)); assertEquals("checkin_complete", state(1105).step());
        String next = button(f.beginCheckin(msg(1105, 13, "/state")));
        assertNotEquals(token(category), token(next)); assertEquals(storedToken(state(1105)), token(next));
    }

    @Test void twoConcurrentStartHandlersReturnSamePersistedSession() throws Exception {
        owner(1106);
        CyclicBarrier barrier = new CyclicBarrier(2);
        DialogStateService coordinated = mock(DialogStateService.class, delegatesTo(dialogs));
        doAnswer(inv -> { barrier.await(10, TimeUnit.SECONDS); return dialogs.save(inv.getArgument(0)); }).when(coordinated).save(any());
        CoreBotFlow a = new CoreBotFlow(users, entries, coordinated, Clock.systemUTC());
        CoreBotFlow b = new CoreBotFlow(users, entries, coordinated, Clock.systemUTC());
        try (ExecutorService pool = Executors.newFixedThreadPool(2)) {
            Future<String> left = pool.submit(() -> button(a.beginCheckin(msg(1106, 10, "/state"))));
            Future<String> right = pool.submit(() -> button(b.beginCheckin(msg(1106, 10, "/state"))));
            String l = left.get(20, TimeUnit.SECONDS), r = right.get(20, TimeUnit.SECONDS);
            assertEquals(l, r); assertEquals(storedToken(state(1106)), token(l));
        }
    }

    @Test void sameFlowConcurrentStartsReturnPersistedSession() throws Exception {
        owner(1108);
        CyclicBarrier barrier = new CyclicBarrier(2);
        CoreBotFlow subject = new CoreBotFlow(users, entries, dialogs, Clock.systemUTC());
        try (ExecutorService pool = Executors.newFixedThreadPool(2)) {
            // Race public calls; serialization inside one flow is a valid implementation.
            Future<String> left = pool.submit(() -> { barrier.await(10, TimeUnit.SECONDS); return button(subject.beginCheckin(msg(1108, 10, "/state"))); });
            Future<String> right = pool.submit(() -> { barrier.await(10, TimeUnit.SECONDS); return button(subject.beginCheckin(msg(1108, 10, "/state"))); });
            String l = left.get(20, TimeUnit.SECONDS), r = right.get(20, TimeUnit.SECONDS);
            assertEquals(l, r, "Concurrent duplicate starts must return the same buttons");
            assertEquals(storedToken(state(1108)), token(l), "Returned session must match the persisted session");
        }
    }

    @Test void oldStartAfterNewClarificationDoesNotSendObsoleteButtons() {
        CoreBotFlow f = flow(); f.beginCheckin(msg(1109, 10, "/state"));
        f.handleMessage(msg(1109, 11, "24.09.2026 пульс 72")); DialogState current = state(1109);
        assertEquals("text_clarification", current.step());
        assertTrue(keyboard(f.beginCheckin(msg(1109, 10, "/state"))).rows().isEmpty());
        assertEquals(current, state(1109));
    }

    @Test void concurrentScoreCallbacksMustNotResetInFlightCompletion() throws Exception {
        EntryCoreService coordinated = mock(EntryCoreService.class, delegatesTo(entries));
        CountDownLatch entered = new CountDownLatch(1), release = new CountDownLatch(1), second = new CountDownLatch(1);
        java.util.concurrent.atomic.AtomicInteger calls = new java.util.concurrent.atomic.AtomicInteger();
        doAnswer(inv -> {
            if (calls.incrementAndGet() == 1) { entered.countDown(); assertTrue(release.await(10, TimeUnit.SECONDS)); }
            else { second.countDown(); }
            return entries.createCheckin(inv.getArgument(0));
        }).when(coordinated).createCheckin(any());
        CoreBotFlow subject = new CoreBotFlow(users, coordinated, dialogs, Clock.systemUTC());
        String category = button(subject.beginCheckin(msg(1110, 10, "/state")));
        String score = button(subject.handleCallback(cb(1110, 11, category)));
        try (ExecutorService pool = Executors.newFixedThreadPool(2)) {
            Future<?> first = pool.submit(() -> subject.handleCallback(cb(1110, 12, score)));
            assertTrue(entered.await(10, TimeUnit.SECONDS));
            Future<?> another = pool.submit(() -> subject.handleCallback(cb(1110, 13, score)));
            second.await(2, TimeUnit.SECONDS); // fixed code blocks second transition until persistence completes
            release.countDown();
            first.get(10, TimeUnit.SECONDS); another.get(10, TimeUnit.SECONDS);
        } finally { release.countDown(); }
        assertEquals(1, calls.get(), "Restoring stored SCORE must not reset the first in-flight completion");
        assertEquals(1, entries.listConfirmedEntries(new ListConfirmedEntriesQuery(owner(1110),
                java.time.LocalDate.of(2026,1,1), java.time.LocalDate.of(2030,1,1), java.time.ZoneId.of("UTC"), Set.of())).size());
    }

    @Test void repeatOldCancelPreservesNewDraft() {
        CoreBotFlow f = flow(); String old = draft(f, 1201, 10);
        f.handleCallback(cb(1201, 11, old)); draft(f, 1201, 12); DialogState current = state(1201);
        f.handleCallback(cb(1201, 13, old)); assertEquals(current, state(1201));
    }

    @Test void repeatOldCancelPreservesCheckin() {
        CoreBotFlow f = flow(); String old = draft(f, 1202, 10);
        f.handleCallback(cb(1202, 11, old)); f.beginCheckin(msg(1202, 12, "/state")); DialogState current = state(1202);
        f.handleCallback(cb(1202, 13, old)); assertEquals(current, state(1202));
    }

    @Test void repeatOldCancelPreservesTextClarification() {
        CoreBotFlow f = flow(); String old = draft(f, 1203, 10);
        f.handleCallback(cb(1203, 11, old)); f.handleMessage(msg(1203, 12, "24.09.2026 пульс 72"));
        DialogState current = state(1203); assertEquals("text_clarification", current.step());
        f.handleCallback(cb(1203, 13, old)); assertEquals(current, state(1203));
    }

    @Test void dialogChangeDuringCancellationMustSurvive() {
        CoreBotFlow f = flow(); String old = draft(f, 1204, 10);
        EntryCoreService raced = mock(EntryCoreService.class, delegatesTo(entries));
        doAnswer(inv -> {
            Entry result = entries.cancel(inv.getArgument(0), inv.getArgument(1), inv.getArgument(2));
            dialogs.save(new SaveDialogStateCommand(owner(1204), null, "text_clarification", Map.of("new", "dialog"), new TelegramUpdateKey("main", 11)));
            return result;
        }).when(raced).cancel(any(), any(), anyLong());
        new CoreBotFlow(users, raced, dialogs, Clock.systemUTC()).handleCallback(cb(1204, 12, old));
        assertEquals("text_clarification", state(1204).step());
        assertEquals(Map.of("new", "dialog"), state(1204).context());
    }

    @Test void crashAfterEntryCancelCanFinishOwnCleanupOnRetry() {
        CoreBotFlow f = flow(); String cancel = draft(f, 1205, 10);
        DialogState old = state(1205); Entry entry = entries.requireEntry(owner(1205), old.activeEntryId());
        entries.cancel(owner(1205), entry.id(), entry.revision()); // crash before clearing dialog
        flow().handleCallback(cb(1205, 11, cancel));
        assertEquals("idle", state(1205).step());
        DialogState cleared = state(1205);
        flow().handleCallback(cb(1205, 11, cancel));
        assertEquals(cleared, state(1205));
    }

    @Test void anotherOwnerCannotCancelOrClearOriginalOwner() {
        CoreBotFlow f = flow(); String cancel = draft(f, 1206, 10); DialogState original = state(1206);
        f.beginCheckin(msg(1207, 11, "/state")); DialogState other = state(1207);
        f.handleCallback(cb(1207, 12, cancel));
        assertEquals(original, state(1206)); assertEquals(other, state(1207));
        assertEquals(EntryStatus.DRAFT, entries.requireEntry(owner(1206), original.activeEntryId()).status());
    }
    @Test void savedCheckinMustNotRepeatAfterCompletionSaveFailsAndProcessRestarts() {
        DialogStateService failing = mock(DialogStateService.class, delegatesTo(dialogs));
        AtomicBoolean fail = new AtomicBoolean(true);
        doAnswer(inv -> {
            SaveDialogStateCommand command = inv.getArgument(0);
            if (command.step().equals("checkin_complete") && fail.getAndSet(false)) {
                throw new IllegalStateException("simulated dialog write interruption after entry persisted");
            }
            return dialogs.save(command);
        }).when(failing).save(any());
        CoreBotFlow subject = new CoreBotFlow(users, entries, failing, Clock.systemUTC());
        String category = button(subject.beginCheckin(msg(1111, 10, "/state")));
        String score = button(subject.handleCallback(cb(1111, 11, category)));
        assertThrows(IllegalStateException.class, () -> subject.handleCallback(cb(1111, 12, score)));
        assertEquals("checkin_score", state(1111).step());
        context.close(); reopen();
        flow().handleCallback(cb(1111, 13, score));
        assertEquals("checkin_complete", state(1111).step());
        assertEquals(1, entries.listConfirmedEntries(new ListConfirmedEntriesQuery(owner(1111),
                java.time.LocalDate.of(2026,1,1), java.time.LocalDate.of(2030,1,1), java.time.ZoneId.of("UTC"), Set.of())).size());
    }

    @Test void failedEntryWriteCanRetrySameUpdateAndPersist() {
        EntryCoreService failing = mock(EntryCoreService.class, delegatesTo(entries));
        AtomicBoolean fail = new AtomicBoolean(true);
        doAnswer(inv -> {
            if (fail.getAndSet(false)) throw new IllegalStateException("simulated entry write failure");
            return entries.createCheckin(inv.getArgument(0));
        }).when(failing).createCheckin(any());
        CoreBotFlow subject = new CoreBotFlow(users, failing, dialogs, Clock.systemUTC());
        String category = button(subject.beginCheckin(msg(1112, 10, "/state")));
        String score = button(subject.handleCallback(cb(1112, 11, category)));
        assertThrows(IllegalStateException.class, () -> subject.handleCallback(cb(1112, 12, score)));
        subject.handleCallback(cb(1112, 12, score));
        verify(failing, times(2)).createCheckin(any());
        assertEquals("checkin_complete", state(1112).step());
        assertEquals(1, entries.listConfirmedEntries(new ListConfirmedEntriesQuery(owner(1112),
                java.time.LocalDate.of(2026,1,1), java.time.LocalDate.of(2030,1,1), java.time.ZoneId.of("UTC"), Set.of())).size());
    }

    @Test void newSessionReplacesAcknowledgedCompletionAfterIdleFailure() {
        DialogStateService failing = mock(DialogStateService.class, delegatesTo(dialogs));
        AtomicBoolean fail = new AtomicBoolean(true);
        doAnswer(inv -> {
            SaveDialogStateCommand command = inv.getArgument(0);
            if (command.step().equals("checkin_complete") && fail.getAndSet(false)) {
                throw new IllegalStateException("simulated completion failure");
            }
            return dialogs.save(command);
        }).when(failing).save(any());
        CoreBotFlow subject = new CoreBotFlow(users, entries, failing, Clock.systemUTC());
        String firstCategory = button(subject.beginCheckin(msg(1113, 10, "/state")));
        String firstScore = button(subject.handleCallback(cb(1113, 11, firstCategory)));
        assertThrows(IllegalStateException.class, () -> subject.handleCallback(cb(1113, 12, firstScore)));
        String nextCategory = button(subject.beginCheckin(msg(1113, 20, "/state")));
        assertNotEquals(token(firstCategory), token(nextCategory));
        String nextScore = button(subject.handleCallback(cb(1113, 21, nextCategory)));
        subject.handleCallback(cb(1113, 22, nextScore));
        assertEquals("checkin_complete", state(1113).step());
        assertEquals(2, entries.listConfirmedEntries(new ListConfirmedEntriesQuery(owner(1113),
                java.time.LocalDate.of(2026,1,1), java.time.LocalDate.of(2030,1,1), java.time.ZoneId.of("UTC"), Set.of())).size());
    }

    @Test void completedCheckinReplayAfterRestartReturnsSavedConfirmation() {
        CoreBotFlow subject = flow();
        String category = button(subject.beginCheckin(msg(1114, 10, "/state")));
        String score = button(subject.handleCallback(cb(1114, 11, category)));
        subject.handleCallback(cb(1114, 12, score));
        context.close(); reopen();

        List<BotAction> replay = flow().handleCallback(cb(1114, 12, score));

        assertEquals("Уже сохранено", ((BotAction.AnswerCallback) replay.getFirst()).text());
        assertEquals("Отметка уже сохранена.", ((BotAction.SendInlineMessage) replay.get(1)).text());
        assertEquals(1, entries.listConfirmedEntries(new ListConfirmedEntriesQuery(owner(1114),
                java.time.LocalDate.of(2026,1,1), java.time.LocalDate.of(2030,1,1),
                java.time.ZoneId.of("UTC"), Set.of())).size());
    }
}
