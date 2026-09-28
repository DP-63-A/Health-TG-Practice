package org.healthtg.bot.checkin;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.NullSource;
import org.junit.jupiter.params.provider.ValueSource;

import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

import static org.healthtg.bot.checkin.CheckinSelector.Stage.*;
import static org.healthtg.bot.checkin.CheckinSelector.Status.*;
import static org.junit.jupiter.api.Assertions.*;

class CheckinSelectorTest {
    private static final long OWNER = 1001;
    private final CheckinSelector selector = new CheckinSelector();

    private static List<CheckinSelector.Button> buttons(CheckinSelector.Outcome outcome) {
        return outcome.view().rows().stream().flatMap(List::stream).toList();
    }

    private static String action(CheckinSelector.Outcome outcome, String suffix) {
        String data = buttons(outcome).getFirst().callbackData();
        return data.substring(0, data.lastIndexOf(':') + 1) + suffix;
    }

    private CheckinSelector.Outcome click(long updateId, String data) {
        return selector.callback(OWNER, OWNER, updateId, data);
    }

    private static void rejected(CheckinSelector.Outcome result) {
        assertEquals(REJECTED, result.status());
        assertEquals(INVALID, result.view().stage());
        assertNull(result.view().selection());
        assertTrue(result.view().rows().isEmpty());
    }

    @ParameterizedTest
    @CsvSource({"0,sleep_quality,Качество сна", "1,digestion_comfort,Комфорт пищеварения",
            "2,wellbeing,Самочувствие", "3,mood,Настроение"})
    void allTwentyContractChoicesCompleteInThreeActions(int category, String code, String label) {
        for (int score = 1; score <= 5; score++) {
            long startId = score * 10L;
            var start = selector.begin(OWNER, OWNER, startId);
            assertEquals(ACCEPTED, start.status());
            assertEquals(CATEGORY, start.view().stage());
            assertNull(start.view().selection());
            assertEquals(4, buttons(start).size());
            var categoryButton = buttons(start).get(category);
            assertTrue(categoryButton.text().contains(label));
            assertTrue(categoryButton.text().codePoints().anyMatch(c -> c > 0xFFFF), "category has emoji");
            var scale = click(startId + 1, categoryButton.callbackData());
            assertEquals(ACCEPTED, scale.status());
            assertEquals(SCORE, scale.view().stage());
            assertEquals(List.of("1", "2", "3", "4", "5"), buttons(scale).stream().map(CheckinSelector.Button::text).toList());
            assertTrue(scale.view().text().contains("1 — очень плохо"));
            assertTrue(scale.view().text().contains("5 — очень хорошо"));
            for (var button : buttons(scale)) {
                assertTrue(button.callbackData().getBytes(StandardCharsets.UTF_8).length <= 64);
            }
            var finish = click(startId + 2, buttons(scale).get(score - 1).callbackData());
            assertEquals(SELECTED, finish.status());
            assertEquals(COMPLETE, finish.view().stage());
            assertEquals(code, finish.view().selection().category().code());
            assertEquals(score, finish.view().selection().score());
            assertEquals(OWNER, finish.view().selection().owner());
            assertNotNull(finish.view().selection().selectionId());
            assertTrue(finish.view().rows().isEmpty(), "no fourth confirmation");
            assertTrue(finish.view().text().contains("Данные не сохранены"));
        }
    }

    @Test void viewsCannotBeMutatedAndDoNotChangeAfterTransition() {
        var start = selector.begin(OWNER, OWNER, 0);
        var originalRows = start.view().rows();
        assertThrows(UnsupportedOperationException.class, () -> originalRows.clear());
        assertThrows(UnsupportedOperationException.class, () -> originalRows.getFirst().clear());
        var scale = click(1, buttons(start).getFirst().callbackData());
        assertThrows(UnsupportedOperationException.class, () -> scale.view().rows().getFirst().clear());
        click(2, buttons(scale).getFirst().callbackData());
        assertEquals(CATEGORY, start.view().stage());
        assertNull(start.view().selection());
        assertEquals(4, buttons(start).size());
    }

    @Test void selectionRejectsInvalidValuesAtItsBoundary() {
        UUID id = UUID.randomUUID();
        assertThrows(NullPointerException.class, () -> new CheckinSelection(null, OWNER, CheckinCategory.MOOD, 1));
        assertThrows(NullPointerException.class, () -> new CheckinSelection(id, OWNER, null, 1));
        for (long owner : new long[]{0, -1})
            assertThrows(IllegalArgumentException.class, () -> new CheckinSelection(id, owner, CheckinCategory.MOOD, 1));
        for (int score : new int[]{Integer.MIN_VALUE, -1, 0, 6, Integer.MAX_VALUE})
            assertThrows(IllegalArgumentException.class, () -> new CheckinSelection(id, OWNER, CheckinCategory.MOOD, score));
        assertEquals(1, new CheckinSelection(id, OWNER, CheckinCategory.MOOD, 1).score());
        assertEquals(5, new CheckinSelection(id, OWNER, CheckinCategory.MOOD, 5).score());
        assertThrows(IllegalArgumentException.class, () -> new CheckinSelector(0));
        assertThrows(IllegalArgumentException.class, () -> new CheckinSelector(-1));
    }

    @ParameterizedTest @NullSource @ValueSource(strings = {"", "garbage", "q:", "q:00000000000000000000000000000000:v1"})
    void malformedOrUnknownCallbackDoesNotPoisonSequence(String data) {
        var start = selector.begin(OWNER, OWNER, 0);
        rejected(click(Long.MAX_VALUE, data));
        assertEquals(ACCEPTED, click(1, buttons(start).getFirst().callbackData()).status());
    }

    @ParameterizedTest @ValueSource(strings = {"c-1", "c4", "c00", "c1:extra", "v0", "v6", "v-1", "v01", "v1 ", "v１", "v2147483647"})
    void tamperedActionDoesNotConsumeUpdateOrAlterSelection(String suffix) {
        var start = selector.begin(OWNER, OWNER, 0);
        rejected(click(100, action(start, suffix)));
        var scale = click(1, buttons(start).get(3).callbackData());
        assertEquals(SELECTED, click(2, buttons(scale).get(4).callbackData()).status());
    }

    @Test void foreignOwnerAndNonPrivateContextsCannotUseOrInvalidateButtons() {
        var start = selector.begin(OWNER, OWNER, 0);
        String data = buttons(start).getFirst().callbackData();
        for (long[] context : new long[][]{{2002, 2002, 99}, {OWNER, -100, 99}, {OWNER, 2002, 99}, {0, 0, 99}, {-1, -1, 99}, {OWNER, OWNER, -1}}) {
            rejected(selector.callback(context[0], context[1], context[2], data));
            if (context[0] != 2002) rejected(selector.begin(context[0], context[1], context[2]));
        }
        assertEquals(ACCEPTED, click(1, data).status());
    }

    @Test void scoreBeforeCategoryAndOldUpdateDoNotAdvanceState() {
        var start = selector.begin(OWNER, OWNER, 10);
        rejected(click(100, action(start, "v5")));
        rejected(click(9, buttons(start).getFirst().callbackData()));
        rejected(click(10, buttons(start).getFirst().callbackData()));
        var scale = click(11, buttons(start).getFirst().callbackData());
        assertEquals(SCORE, scale.view().stage());
        assertEquals(SELECTED, click(12, buttons(scale).getFirst().callbackData()).status());
    }

    @Test void duplicateStartsAndCategoriesNeverRollBackAndFirstScoreWins() {
        var start = selector.begin(OWNER, OWNER, 10);
        String category = buttons(start).get(2).callbackData();
        var scale = click(11, category);
        assertEquals(scale.view(), selector.begin(OWNER, OWNER, 10).view());
        assertEquals(REPLAY, click(11, category).status());
        rejected(click(12, buttons(start).getFirst().callbackData()));
        rejected(click(12, category));
        String score = buttons(scale).get(3).callbackData();
        var finish = click(12, score);
        assertEquals(SELECTED, finish.status());
        assertEquals(REPLAY, click(12, score).status());
        assertEquals(finish.view(), click(13, score).view());
        assertEquals(REPLAY, click(13, score).status());
        rejected(click(14, buttons(scale).getFirst().callbackData()));
        var replayStart = selector.begin(OWNER, OWNER, 10);
        assertEquals(REPLAY, replayStart.status());
        assertEquals(finish.view().selection(), replayStart.view().selection());
        assertEquals(CheckinCategory.WELLBEING, replayStart.view().selection().category());
        assertEquals(4, replayStart.view().selection().score());
    }

    @Test void newerScoreReplayRejectsDelayedStartWithoutReplacingCompletedSelection() {
        var start = selector.begin(OWNER, OWNER, 10);
        var scale = click(11, buttons(start).get(2).callbackData());
        String score = buttons(scale).get(3).callbackData();
        var finish = click(12, score);
        assertEquals(SELECTED, finish.status());

        var replay = click(100, score);
        assertEquals(REPLAY, replay.status());
        assertEquals(finish.view(), replay.view());
        rejected(selector.begin(OWNER, OWNER, 50));
        rejected(selector.begin(OWNER, OWNER, 100));
        rejected(click(99, score));
        rejected(click(100, buttons(scale).getFirst().callbackData()));
        var duplicate = click(100, score);
        assertEquals(REPLAY, duplicate.status());
        assertEquals(finish.view(), duplicate.view());

        var next = selector.begin(OWNER, OWNER, 101);
        assertEquals(ACCEPTED, next.status());
        assertEquals(CATEGORY, next.view().stage());
        assertNull(next.view().selection());
        rejected(click(102, score));
    }

    @ParameterizedTest @ValueSource(strings = {"c0", "v1", "v6"})
    void invalidCallbackAfterCompletedReplayDoesNotConsumeNewerUpdate(String suffix) {
        var start = selector.begin(OWNER, OWNER, 10);
        var scale = click(11, buttons(start).get(2).callbackData());
        String score = buttons(scale).get(3).callbackData();
        var finish = click(12, score);
        assertEquals(SELECTED, finish.status());
        assertEquals(REPLAY, click(100, score).status());

        rejected(click(Long.MAX_VALUE, action(scale, suffix)));
        var duplicate = click(100, score);
        assertEquals(REPLAY, duplicate.status());
        assertEquals(finish.view(), duplicate.view());
        assertEquals(ACCEPTED, selector.begin(OWNER, OWNER, 101).status());
    }

    @Test void replacingSelectionOrLosingStateInvalidatesOldButtons() {
        var old = selector.begin(OWNER, OWNER, 10);
        var current = selector.begin(OWNER, OWNER, 20);
        rejected(click(30, buttons(old).getFirst().callbackData()));
        rejected(selector.begin(OWNER, OWNER, 10));
        rejected(new CheckinSelector().callback(OWNER, OWNER, 30, buttons(current).getFirst().callbackData()));
        assertEquals(ACCEPTED, click(21, buttons(current).getFirst().callbackData()).status());
    }

    @Test void capacityEvictsOldestStartButRejectedInputsDoNotEvictAnyone() {
        var bounded = new CheckinSelector(2);
        var first = bounded.begin(1, 1, 0);
        var second = bounded.begin(2, 2, 0);
        for (int i = 3; i < 100; i++) {
            rejected(bounded.begin(i, -100, i));
            rejected(bounded.callback(i, i, i, buttons(first).getFirst().callbackData()));
        }
        assertEquals(REPLAY, bounded.begin(1, 1, 0).status());
        assertEquals(REPLAY, bounded.begin(2, 2, 0).status());
        bounded.begin(3, 3, 0);
        rejected(bounded.callback(1, 1, 1, buttons(first).getFirst().callbackData()));
        assertEquals(ACCEPTED, bounded.callback(2, 2, 1, buttons(second).getFirst().callbackData()).status());
    }

    @Test void concurrentScoresProduceExactlyOneImmutableChoice() throws Exception {
        var start = selector.begin(OWNER, OWNER, 0);
        var scale = click(1, buttons(start).getFirst().callbackData());
        var ready = new CountDownLatch(2);
        var release = new CountDownLatch(1);
        try (var executor = Executors.newFixedThreadPool(2)) {
            var low = executor.submit(() -> { ready.countDown(); assertTrue(release.await(5, TimeUnit.SECONDS)); return click(2, buttons(scale).getFirst().callbackData()); });
            var high = executor.submit(() -> { ready.countDown(); assertTrue(release.await(5, TimeUnit.SECONDS)); return click(3, buttons(scale).getLast().callbackData()); });
            assertTrue(ready.await(5, TimeUnit.SECONDS));
            release.countDown();
            var results = List.of(low.get(5, TimeUnit.SECONDS), high.get(5, TimeUnit.SECONDS));
            assertEquals(1, results.stream().filter(r -> r.status() == SELECTED).count());
            assertEquals(1, results.stream().filter(r -> r.status() == REJECTED).count());
            var chosen = results.stream().filter(r -> r.status() == SELECTED).findFirst().orElseThrow().view().selection();
            assertEquals(chosen, selector.begin(OWNER, OWNER, 0).view().selection());
        } finally { release.countDown(); }
    }
}
