package org.healthtg.bot;

import java.time.*;
import java.util.*;
import java.util.concurrent.*;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.*;
import static org.junit.jupiter.api.Assertions.*;

class CheckinDialogueTest {
    static final long USER = 1001L;
    static final Instant NOW = Instant.parse("2026-09-22T10:30:00Z");
    static final Clock CLOCK = Clock.fixed(NOW, ZoneOffset.UTC);
    static BotUpdate start(int id) {
        return new BotUpdate(BotUpdate.Kind.MESSAGE, BotUpdate.ChatType.PRIVATE, USER, USER, false,
                "/state", List.of(new BotUpdate.Entity("bot_command", 0, 6)), id, null, null);
    }
    static BotUpdate callback(int id, String data) {
        return new BotUpdate(BotUpdate.Kind.CALLBACK, BotUpdate.ChatType.PRIVATE, USER, USER, false,
                null, List.of(), id, "callback-" + id, data);
    }
    static BotAction.InlineMessage output(List<BotAction> actions) {
        return actions.stream().filter(BotAction.InlineMessage.class::isInstance)
                .map(BotAction.InlineMessage.class::cast).findFirst().orElseThrow();
    }
    static List<BotAction.Button> buttons(BotAction.InlineMessage message) {
        return message.rows().stream().flatMap(List::stream).toList();
    }
    static class RecordingStore implements CheckinStore {
        final Fixture fixture = new Fixture();
        final List<Request> calls = new ArrayList<>();
        int cancels; boolean ambiguousSave; boolean failCancel;
        public Saved save(Request request) {
            calls.add(request);
            Saved result = fixture.save(request);
            if (ambiguousSave) { ambiguousSave = false; throw new IllegalStateException("Synthetic lost response"); }
            return result;
        }
        public void cancel(long owner, String id) {
            cancels++;
            if (failCancel) { failCancel = false; throw new IllegalStateException("Synthetic unavailable"); }
            fixture.cancel(owner, id);
        }
    }
    static Stream<Arguments> selections() {
        return Arrays.stream(CheckinStore.Category.values()).flatMap(c ->
                java.util.stream.IntStream.rangeClosed(1, 5).mapToObj(v -> Arguments.of(c, v)));
    }
    @ParameterizedTest @MethodSource("selections")
    void threeClicksSaveEveryCategoryAndValueWithoutConfirmation(CheckinStore.Category category, int value) {
        var store = new RecordingStore(); var dialogue = new CheckinDialogue(store, CLOCK);
        var categories = output(dialogue.begin(start(1)));
        assertEquals(4, buttons(categories).size());
        assertEquals(Arrays.stream(CheckinStore.Category.values()).map(CheckinStore.Category::label).toList(),
                buttons(categories).stream().map(BotAction.Button::text).toList());
        var scores = output(dialogue.callback(callback(2, buttons(categories).get(category.ordinal()).data())));
        assertEquals(List.of("1", "2", "3", "4", "5"), buttons(scores).stream().map(BotAction.Button::text).toList());
        assertTrue(scores.text().contains("1 — очень плохо, 5 — очень хорошо"));
        assertTrue(store.calls.isEmpty());
        var result = output(dialogue.callback(callback(3, buttons(scores).get(value - 1).data())));
        assertEquals(1, store.calls.size());
        var request = store.calls.getFirst();
        assertEquals(category, request.category()); assertEquals(value, request.value());
        assertEquals(USER, request.owner()); assertEquals(NOW, request.occurredAt());
        assertTrue(result.text().contains(category.label())); assertTrue(result.text().contains(value + "/5"));
        assertTrue(result.text().contains("22.09.2026 12:30:00")); assertTrue(result.text().contains("Europe/Warsaw +02:00"));
        assertTrue(result.text().contains("Тестовый режим: в БД не сохранено"));
        assertEquals(List.of("Отменить"), buttons(result).stream().map(BotAction.Button::text).toList());
        buttons(categories).forEach(b -> assertTrue(b.data().getBytes(java.nio.charset.StandardCharsets.UTF_8).length <= 64));
    }
    @Test void replayedTransitionsAreStableAndDifferentScoreCannotCreateSecondEntry() {
        var store = new RecordingStore(); var d = new CheckinDialogue(store, CLOCK);
        var initial = d.begin(start(10)); assertEquals(initial, d.begin(start(10)));
        var category = callback(11, buttons(output(initial)).getFirst().data());
        var scores = d.callback(category); assertEquals(scores, d.callback(category));
        var choice = callback(12, buttons(output(scores)).getFirst().data());
        var saved = d.callback(choice); assertEquals(saved, d.callback(choice));
        var other = output(d.callback(callback(13, buttons(output(scores)).get(4).data())));
        assertEquals(output(saved), other); assertEquals(1, store.calls.size()); assertEquals(1, store.fixture.size());
    }
    @Test void ambiguousSaveFreezesEntirePayloadEvenIfAnotherScoreAndTimeAreUsed() {
        var clock = org.mockito.Mockito.mock(Clock.class);
        org.mockito.Mockito.when(clock.instant()).thenReturn(NOW, NOW.plusSeconds(3600));
        var store = new RecordingStore(); store.ambiguousSave = true;
        var d = new CheckinDialogue(store, clock);
        var categories = output(d.begin(start(1)));
        var scores = output(d.callback(callback(2, buttons(categories).get(2).data())));
        var failure = output(d.callback(callback(3, buttons(scores).get(1).data())));
        assertTrue(failure.text().contains("Не удалось")); assertFalse(failure.text().contains("2/5"));
        assertTrue(output(d.begin(start(4))).text().contains("предыдущего сохранения"));
        var result = output(d.callback(callback(5, buttons(scores).get(4).data())));
        assertEquals(2, store.calls.size()); assertEquals(store.calls.get(0), store.calls.get(1));
        assertEquals(NOW, store.calls.get(1).occurredAt()); assertEquals(2, store.calls.get(1).value());
        assertEquals(1, store.fixture.size()); assertTrue(result.text().contains("2/5"));
    }
    @Test void previousSavedEntryCanBeCancelledAfterNewSessionAndCancelFailuresAreHonest() {
        var store = new RecordingStore(); var d = new CheckinDialogue(store, CLOCK);
        var first = output(d.begin(start(1)));
        var scores = output(d.callback(callback(2, buttons(first).getFirst().data())));
        var saved = output(d.callback(callback(3, buttons(scores).getFirst().data())));
        var cancel = callback(5, buttons(saved).getFirst().data());
        d.begin(start(4)); store.failCancel = true;
        assertTrue(output(d.callback(cancel)).text().contains("Не удалось отменить"));
        assertFalse(store.fixture.isCancelled(store.calls.getFirst().key()));
        var cancelled = d.callback(cancel);
        assertTrue(output(cancelled).text().contains("отменена"));
        assertEquals(cancelled, d.callback(cancel)); assertEquals(2, store.cancels);
        assertTrue(store.fixture.isCancelled(store.calls.getFirst().key()));
    }
    @Test void newSessionAndRestartRejectOldButtonsWithoutInventingSuccess() {
        var store = new RecordingStore(); var d = new CheckinDialogue(store, CLOCK);
        var old = buttons(output(d.begin(start(1)))).getFirst().data(); d.begin(start(2));
        assertTrue(output(d.callback(callback(3, old))).text().contains("недействительна"));
        var restarted = new CheckinDialogue(new CheckinStore.Fixture(), CLOCK);
        var result = output(restarted.callback(callback(4, old)));
        assertTrue(result.text().contains("прежние тестовые записи недоступны")); assertTrue(store.calls.isEmpty());
    }
    @Test void restartRejectsCancellationOfLostFixtureEntry() {
        var store = new RecordingStore(); var d = new CheckinDialogue(store, CLOCK);
        var categories = output(d.begin(start(1)));
        var scores = output(d.callback(callback(2, buttons(categories).getFirst().data())));
        var saved = output(d.callback(callback(3, buttons(scores).getFirst().data())));
        var restartedStore = new RecordingStore(); var restarted = new CheckinDialogue(restartedStore, CLOCK);
        var result = output(restarted.callback(callback(4, buttons(saved).getFirst().data())));
        assertTrue(result.text().contains("прежние тестовые записи недоступны"));
        assertFalse(result.text().contains("отменена")); assertEquals(0, restartedStore.cancels);
    }
    @Test void successDisplaysStoreResultRatherThanInventingOwnTimestamp() {
        CheckinStore store = new CheckinStore() {
            public Saved save(Request r) { return new Saved("persisted-id", new Request(r.key(),r.owner(),r.category(),r.value(),NOW.plusSeconds(60))); }
            public void cancel(long owner, String id) { assertEquals("persisted-id",id); }
        };
        var d = new CheckinDialogue(store, CLOCK);
        var categories = output(d.begin(start(1)));
        var scores = output(d.callback(callback(2, buttons(categories).getFirst().data())));
        var saved = output(d.callback(callback(3, buttons(scores).getFirst().data())));
        assertTrue(saved.text().contains("12:31:00"));
        assertTrue(output(d.callback(callback(4,buttons(saved).getFirst().data()))).text().contains("отменена"));
    }
    @ParameterizedTest @ValueSource(strings={"v0", "v6", "v-1", "v99", "retry", "cancel", "c4", ""})
    void malformedOrOutOfOrderButtonDoesNotSave(String suffix) {
        var store = new RecordingStore(); var d = new CheckinDialogue(store, CLOCK);
        String data = buttons(output(d.begin(start(1)))).getFirst().data();
        data = data.substring(0, data.lastIndexOf(':') + 1) + suffix;
        assertTrue(output(d.callback(callback(2, data))).text().contains("недействительна"));
        assertTrue(store.calls.isEmpty()); assertEquals(0, store.cancels);
    }
    @Test void accessGateDeniesCallbackBeforeStorageAndOwnerCannotReuseOthersButtons() {
        var store = new RecordingStore(); var d = new CheckinDialogue(store, CLOCK);
        var handler = new BotHandler(new BotSettings(Set.of(USER, 2002L), "test_bot", null), d);
        String data = buttons(output(handler.handle(start(1)))).getFirst().data();
        for (var type : BotUpdate.ChatType.values()) if (type != BotUpdate.ChatType.PRIVATE)
            assertTrue(handler.handle(new BotUpdate(BotUpdate.Kind.CALLBACK,type,USER,USER,false,null,List.of(),2,"id",data)).isEmpty());
        for (var u : List.of(
                new BotUpdate(BotUpdate.Kind.CALLBACK,BotUpdate.ChatType.PRIVATE,USER,USER,true,null,List.of(),2,"id",data),
                new BotUpdate(BotUpdate.Kind.CALLBACK,BotUpdate.ChatType.PRIVATE,USER,null,false,null,List.of(),2,"id",data),
                new BotUpdate(BotUpdate.Kind.CALLBACK,BotUpdate.ChatType.PRIVATE,3003,3003L,false,null,List.of(),2,"id",data),
                new BotUpdate(BotUpdate.Kind.CALLBACK,BotUpdate.ChatType.PRIVATE,2002,USER,false,null,List.of(),2,"id",data)))
            assertTrue(handler.handle(u).isEmpty());
        assertTrue(output(handler.handle(new BotUpdate(BotUpdate.Kind.CALLBACK,BotUpdate.ChatType.PRIVATE,2002,2002L,false,null,List.of(),2,"id",data))).text().contains("недействительна"));
        assertTrue(store.calls.isEmpty());
    }
    @Test void concurrentScoresForSameSessionSaveExactlyOnce() throws Exception {
        var store = new RecordingStore(); var d = new CheckinDialogue(store, CLOCK);
        var categories = output(d.begin(start(1)));
        var scores = buttons(output(d.callback(callback(2, buttons(categories).getFirst().data()))));
        try (var pool = Executors.newFixedThreadPool(5)) {
            var gate = new CountDownLatch(1); var futures = new ArrayList<Future<List<BotAction>>>();
            for (int i = 0; i < 5; i++) { final int index = i; futures.add(pool.submit(() -> { gate.await(); return d.callback(callback(3+index,scores.get(index).data())); })); }
            gate.countDown(); for (var f : futures) assertTrue(output(f.get(5, TimeUnit.SECONDS)).text().contains("/5"));
        }
        assertEquals(1, store.calls.size()); assertEquals(1, store.fixture.size());
    }
    @Test void fixtureTombstonesOwnershipAndCapacityCannotCreateFalseCancellationSuccess() {
        var fixture = new CheckinStore.Fixture();
        var request = new CheckinStore.Request("one",USER,CheckinStore.Category.MOOD,3,NOW);
        var saved = fixture.save(request); assertEquals(saved,fixture.save(request));
        assertThrows(IllegalArgumentException.class, () -> fixture.cancel(2002,saved.id()));
        assertThrows(IllegalArgumentException.class, () -> fixture.cancel(USER,"missing"));
        fixture.cancel(USER,saved.id()); fixture.cancel(USER,saved.id());
        assertEquals(saved,fixture.save(request)); assertTrue(fixture.isCancelled(saved.id()));
        assertThrows(IllegalArgumentException.class, () -> fixture.save(new CheckinStore.Request("one",USER,CheckinStore.Category.MOOD,4,NOW)));
        for(int i=1;i<1000;i++) fixture.save(new CheckinStore.Request("entry-"+i,USER,CheckinStore.Category.MOOD,3,NOW));
        assertThrows(IllegalStateException.class, () -> fixture.save(new CheckinStore.Request("overflow",USER,CheckinStore.Category.MOOD,3,NOW)));
        assertEquals(1000,fixture.size()); assertEquals(saved,fixture.save(request));
    }
    @Test void fixtureIsExplicitAndUnknownModeDoesNotSilentlyEnableIt() {
        var env = new HashMap<>(RuntimeSettingsTest.environment());
        assertFalse(RuntimeSettings.from(env).fixture()); env.put("CHECKIN_MODE","fixture"); assertTrue(RuntimeSettings.from(env).fixture());
        env.put("CHECKIN_MODE","unavailable"); assertFalse(RuntimeSettings.from(env).fixture());
        for (String invalid : List.of("live","true","fixtures","")) { env.put("CHECKIN_MODE",invalid); assertThrows(IllegalArgumentException.class, () -> RuntimeSettings.from(env)); }
    }
}
