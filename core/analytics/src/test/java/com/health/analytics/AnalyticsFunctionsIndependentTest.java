package com.health.analytics;

import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.List;
import java.util.Map;

import static com.health.analytics.AnalyticsFunctions.*;
import static org.junit.jupiter.api.Assertions.*;

class AnalyticsFunctionsIndependentTest {

    private static final ZoneId WARSAW = ZoneId.of("Europe/Warsaw");

    private static final Period FULL_PERIOD = new Period(
        LocalDate.of(2026, 9, 13),
        LocalDate.of(2026, 9, 19),
        WARSAW
    );

    private static final LocalDate TEST_DATE =
        LocalDate.of(2026, 9, 19);

    private static BigDecimal d(String value) {
        return new BigDecimal(value);
    }

    private static Nutrients nutrients(
        String kcal,
        String protein,
        String fat,
        String carbs
    ) {
        return new Nutrients(
            d(kcal),
            d(protein),
            d(fat),
            d(carbs)
        );
    }

    @Test
    void per100gMass200Produces330CaloriesAnd20_10_40Macros() {
        Entry meal = Entry.meal(
            "oracle-200g",
            Status.CONFIRMED,
            Instant.parse("2026-09-19T10:00:00Z"),
            d("200"),
            nutrients("165", "10", "5", "20"),
            Basis.PER_100G
        );

        NutritionResult result = nutrition(
            List.of(meal),
            FULL_PERIOD
        );

        // Independent manual calculation:
        // 165 * 200 / 100 = 330
        // 10  * 200 / 100 = 20
        // 5   * 200 / 100 = 10
        // 20  * 200 / 100 = 40

        assertEquals(d("330"), result.energyKcal());
        assertEquals(d("20"), result.proteinG());
        assertEquals(d("10"), result.fatG());
        assertEquals(d("40"), result.carbsG());
        assertEquals(1, result.countedMeals());
        assertFalse(result.incomplete());
    }

    @Test
    void per100gMass150Produces247Point5CaloriesAnd15_7Point5_30Macros() {
        Entry meal = Entry.meal(
            "oracle-150g",
            Status.CONFIRMED,
            Instant.parse("2026-09-19T10:00:00Z"),
            d("150"),
            nutrients("165", "10", "5", "20"),
            Basis.PER_100G
        );

        NutritionResult result = nutrition(
            List.of(meal),
            FULL_PERIOD
        );

        // 165 * 1.5 = 247.5
        // 10  * 1.5 = 15
        // 5   * 1.5 = 7.5
        // 20  * 1.5 = 30

        assertEquals(d("247.5"), result.energyKcal());
        assertEquals(d("15"), result.proteinG());
        assertEquals(d("7.5"), result.fatG());
        assertEquals(d("30"), result.carbsG());
        assertEquals(1, result.countedMeals());
        assertFalse(result.incomplete());
    }

    @Test
    void dailyNutrition930Then847Point5Then600() {
        Entry fixedMeal = Entry.meal(
            "fixed-meal",
            Status.CONFIRMED,
            Instant.parse("2026-09-19T08:00:00Z"),
            null,
            nutrients("600", "30", "20", "70"),
            Basis.PER_SERVING
        );

        Entry variableMeal = Entry.meal(
            "variable-meal",
            Status.CONFIRMED,
            Instant.parse("2026-09-19T12:00:00Z"),
            d("200"),
            nutrients("165", "10", "5", "20"),
            Basis.PER_100G
        );

        NutritionResult original = nutrition(
            List.of(fixedMeal, variableMeal),
            FULL_PERIOD
        );

        assertEquals(d("930"), original.energyKcal());

        Entry changedMeal = Entry.meal(
            "variable-meal",
            Status.CONFIRMED,
            Instant.parse("2026-09-19T12:00:00Z"),
            d("150"),
            nutrients("165", "10", "5", "20"),
            Basis.PER_100G
        );

        NutritionResult changed = nutrition(
            List.of(fixedMeal, changedMeal),
            FULL_PERIOD
        );

        assertEquals(d("847.5"), changed.energyKcal());

        NutritionResult afterDeletion = nutrition(
            List.of(fixedMeal),
            FULL_PERIOD
        );

        assertEquals(d("600"), afterDeletion.energyKcal());
    }

    @Test
    void perServingIsNotScaledByMass() {
        Entry meal = Entry.meal(
            "per-serving",
            Status.CONFIRMED,
            Instant.parse("2026-09-19T10:00:00Z"),
            d("500"),
            nutrients("600", "30", "20", "70"),
            Basis.PER_SERVING
        );

        NutritionResult result = nutrition(
            List.of(meal),
            FULL_PERIOD
        );

        assertEquals(d("600"), result.energyKcal());
        assertEquals(d("30"), result.proteinG());
        assertEquals(d("20"), result.fatG());
        assertEquals(d("70"), result.carbsG());
        assertFalse(result.incomplete());
    }

    @Test
    void draftCancelledAndDeletedEntriesDoNotParticipate() {
        Entry confirmed = Entry.meal(
            "confirmed",
            Status.CONFIRMED,
            Instant.parse("2026-09-19T10:00:00Z"),
            null,
            nutrients("600", "30", "20", "70"),
            Basis.PER_SERVING
        );

        Entry draft = Entry.meal(
            "draft",
            Status.DRAFT,
            Instant.parse("2026-09-19T10:00:00Z"),
            null,
            nutrients("9999", "999", "999", "999"),
            Basis.PER_SERVING
        );

        Entry cancelled = Entry.meal(
            "cancelled",
            Status.CANCELLED,
            Instant.parse("2026-09-19T10:00:00Z"),
            null,
            nutrients("9999", "999", "999", "999"),
            Basis.PER_SERVING
        );

        Entry deleted = Entry.meal(
            "deleted",
            Status.DELETED,
            Instant.parse("2026-09-19T10:00:00Z"),
            null,
            nutrients("9999", "999", "999", "999"),
            Basis.PER_SERVING
        );

        NutritionResult result = nutrition(
            List.of(
                confirmed,
                draft,
                cancelled,
                deleted
            ),
            FULL_PERIOD
        );

        assertEquals(d("600"), result.energyKcal());
        assertEquals(1, result.countedMeals());
        assertFalse(result.incomplete());
    }

    @Test
    void completelyUnknownNutritionIsNullAndIncomplete() {
        Entry meal = Entry.meal(
            "unknown",
            Status.CONFIRMED,
            Instant.parse("2026-09-19T10:00:00Z"),
            d("100"),
            null,
            Basis.UNKNOWN
        );

        NutritionResult result = nutrition(
            List.of(meal),
            FULL_PERIOD
        );

        assertNull(result.energyKcal());
        assertNull(result.proteinG());
        assertNull(result.fatG());
        assertNull(result.carbsG());
        assertEquals(1, result.countedMeals());
        assertTrue(result.incomplete());
    }

    @Test
    void partiallyUnknownNutrientsRemainNullWithoutBecomingZero() {
        Entry meal = Entry.meal(
            "partial",
            Status.CONFIRMED,
            Instant.parse("2026-09-19T10:00:00Z"),
            d("100"),
            nutrients("200", "10", "5", null),
            Basis.PER_100G
        );

        NutritionResult result = nutrition(
            List.of(meal),
            FULL_PERIOD
        );

        assertEquals(d("200"), result.energyKcal());
        assertEquals(d("10"), result.proteinG());
        assertEquals(d("5"), result.fatG());

        assertNull(result.carbsG());
        assertTrue(result.incomplete());
    }

    @Test
    void emptyPeriodProducesNullAggregateAndZeroDaysWithData() {
        DailyResult result = dailyMetric(
            List.of(),
            Metric.STEPS,
            FULL_PERIOD
        );

        assertTrue(result.values().isEmpty());
        assertNull(result.aggregate().total());
        assertNull(result.aggregate().average());
        assertEquals(0, result.aggregate().daysWithData());
    }

    @Test
    void dailyTotalsAreNotSummed() {
        Instant firstOccurred =
            Instant.parse("2026-09-19T08:00:00Z");

        Instant secondOccurred =
            Instant.parse("2026-09-19T18:00:00Z");

        Entry first = new Entry(
            "steps-first",
            "metrics",
            Status.CONFIRMED,
            firstOccurred,
            Instant.parse("2026-09-19T08:01:00Z"),
            1L,
            null,
            null,
            Metric.STEPS,
            d("3000"),
            null,
            null,
            null,
            null,
            null,
            null
        );

        Entry second = new Entry(
            "steps-second",
            "metrics",
            Status.CONFIRMED,
            secondOccurred,
            Instant.parse("2026-09-19T18:01:00Z"),
            2L,
            null,
            null,
            Metric.STEPS,
            d("5000"),
            null,
            null,
            null,
            null,
            null,
            null
        );

        DailyResult result = dailyMetric(
            List.of(first, second),
            Metric.STEPS,
            FULL_PERIOD
        );

        // Must select 5000, not 8000.
        assertEquals(d("5000"), result.aggregate().total());
        assertEquals(d("5000"), result.aggregate().average());
        assertEquals(1, result.aggregate().daysWithData());
    }

    @Test
    void equalOccurredAtUsesUpdatedAt() {
        Instant occurred =
            Instant.parse("2026-09-19T10:00:00Z");

        Entry old = new Entry(
            "old",
            "metrics",
            Status.CONFIRMED,
            occurred,
            Instant.parse("2026-09-19T10:01:00Z"),
            1L,
            null,
            null,
            Metric.STEPS,
            d("3000"),
            null,
            null,
            null,
            null,
            null,
            null
        );

        Entry updated = new Entry(
            "updated",
            "metrics",
            Status.CONFIRMED,
            occurred,
            Instant.parse("2026-09-19T10:02:00Z"),
            2L,
            null,
            null,
            Metric.STEPS,
            d("5000"),
            null,
            null,
            null,
            null,
            null,
            null
        );

        DailyResult result = dailyMetric(
            List.of(updated, old),
            Metric.STEPS,
            FULL_PERIOD
        );

        assertEquals(d("5000"), result.aggregate().total());
    }

    @Test
    void equalOccurredAtAndUpdatedAtUsesIdAsStableTieBreak() {
        Instant occurred =
            Instant.parse("2026-09-19T10:00:00Z");

        Instant updated =
            Instant.parse("2026-09-19T10:05:00Z");

        Entry a = new Entry(
            "a",
            "metrics",
            Status.CONFIRMED,
            occurred,
            updated,
            1L,
            null,
            null,
            Metric.STEPS,
            d("3000"),
            null,
            null,
            null,
            null,
            null,
            null
        );

        Entry b = new Entry(
            "b",
            "metrics",
            Status.CONFIRMED,
            occurred,
            updated,
            2L,
            null,
            null,
            Metric.STEPS,
            d("5000"),
            null,
            null,
            null,
            null,
            null,
            null
        );

        DailyResult forward = dailyMetric(
            List.of(a, b),
            Metric.STEPS,
            FULL_PERIOD
        );

        DailyResult reverse = dailyMetric(
            List.of(b, a),
            Metric.STEPS,
            FULL_PERIOD
        );

        assertEquals(forward, reverse);
        assertEquals(d("5000"), forward.aggregate().total());
    }

    @Test
    void sleepBelongsToWakeDate() {
        Entry sleep = new Entry(
            "sleep",
            "metrics",
            Status.CONFIRMED,
            Instant.parse("2026-09-18T22:30:00Z"),
            null,
            1L,
            null,
            LocalDate.of(2026, 9, 19),
            Metric.SLEEP_DURATION_MIN,
            d("420"),
            null,
            null,
            null,
            null,
            null,
            null
        );

        DailyResult result = dailyMetric(
            List.of(sleep),
            Metric.SLEEP_DURATION_MIN,
            FULL_PERIOD
        );

        assertEquals(
            d("420"),
            result.values().get(TEST_DATE)
        );

        assertEquals(
            d("420"),
            result.aggregate().average()
        );

        assertEquals(1, result.aggregate().daysWithData());
    }

    @Test
    void sleepWithoutWakeDateIsNotAssignedToOccurredAtDate() {
        Entry sleep = new Entry(
            "sleep-without-wake-date",
            "metrics",
            Status.CONFIRMED,
            Instant.parse("2026-09-18T22:30:00Z"),
            null,
            1L,
            null,
            null,
            Metric.SLEEP_DURATION_MIN,
            d("420"),
            null,
            null,
            null,
            null,
            null,
            null
        );

        DailyResult result = dailyMetric(
            List.of(sleep),
            Metric.SLEEP_DURATION_MIN,
            FULL_PERIOD
        );

        assertTrue(result.values().isEmpty());
        assertNull(result.aggregate().total());
        assertNull(result.aggregate().average());
        assertEquals(0, result.aggregate().daysWithData());
    }

    @Test
    void WarsawUtcMidnightBelongsToNextLocalDay() {
        Entry meal = Entry.meal(
            "warsaw-midnight",
            Status.CONFIRMED,
            Instant.parse("2026-09-18T22:00:00Z"),
            d("100"),
            nutrients("165", "10", "5", "20"),
            Basis.PER_100G
        );

        Period oneDay = new Period(
            LocalDate.of(2026, 9, 19),
            LocalDate.of(2026, 9, 19),
            WARSAW
        );

        NutritionResult result = nutrition(
            List.of(meal),
            oneDay
        );

        assertEquals(d("165"), result.energyKcal());
        assertEquals(1, result.countedMeals());
        assertFalse(result.incomplete());
    }

    @Test
    void missingOccurredAtDoesNotBecomeAnArtificialNutritionDate() {
        Entry meal = Entry.meal(
            "missing-date",
            Status.CONFIRMED,
            null,
            d("100"),
            nutrients("165", "10", "5", "20"),
            Basis.PER_100G
        );

        NutritionResult result = nutrition(
            List.of(meal),
            FULL_PERIOD
        );

        assertNull(result.energyKcal());
        assertEquals(0, result.countedMeals());
        assertTrue(result.incomplete());
    }

    @Test
    void heartRateReturnsLatestValueAndQualifier() {
        Entry first = new Entry(
            "pulse-old",
            "metrics",
            Status.CONFIRMED,
            Instant.parse("2026-09-19T09:00:00Z"),
            Instant.parse("2026-09-19T09:01:00Z"),
            1L,
            null,
            null,
            Metric.HEART_RATE,
            d("68"),
            Qualifier.INSTANT,
            null,
            null,
            null,
            null,
            null
        );

        Entry latest = new Entry(
            "pulse-latest",
            "metrics",
            Status.CONFIRMED,
            Instant.parse("2026-09-19T12:00:00Z"),
            Instant.parse("2026-09-19T12:01:00Z"),
            2L,
            null,
            null,
            Metric.HEART_RATE,
            d("72"),
            Qualifier.RESTING,
            null,
            null,
            null,
            null,
            null
        );

        HeartRateResult result = latestHeartRate(
            List.of(first, latest),
            FULL_PERIOD
        ).orElseThrow();

        assertEquals(d("72"), result.value());
        assertEquals(
            Instant.parse("2026-09-19T12:00:00Z"),
            result.occurredAt()
        );
        assertEquals(Qualifier.RESTING, result.qualifier());
        assertEquals("pulse-latest", result.entryId());
    }

    @Test
    void heartRateWithoutQualifierKeepsQualifierAbsent() {
        Entry pulse = new Entry(
            "pulse",
            "metrics",
            Status.CONFIRMED,
            Instant.parse("2026-09-19T12:00:00Z"),
            null,
            1L,
            null,
            null,
            Metric.HEART_RATE,
            d("72"),
            null,
            null,
            null,
            null,
            null,
            null
        );

        HeartRateResult result = latestHeartRate(
            List.of(pulse),
            FULL_PERIOD
        ).orElseThrow();

        assertEquals(d("72"), result.value());
        assertNull(result.qualifier());
    }

    @Test
    void checkinsKeepFourCategoriesSeparate() {
        Entry sleepQuality = checkin(
            "sleep",
            CheckinCategory.SLEEP_QUALITY,
            4,
            "2026-09-19T09:00:00Z"
        );

        Entry digestion = checkin(
            "digestion",
            CheckinCategory.DIGESTION_COMFORT,
            3,
            "2026-09-19T10:00:00Z"
        );

        Entry wellbeing = checkin(
            "wellbeing",
            CheckinCategory.WELLBEING,
            5,
            "2026-09-19T11:00:00Z"
        );

        Entry mood = checkin(
            "mood",
            CheckinCategory.MOOD,
            2,
            "2026-09-19T12:00:00Z"
        );

        Map<CheckinCategory, List<RatingPoint>> result =
            checkins(
                List.of(
                    sleepQuality,
                    digestion,
                    wellbeing,
                    mood
                ),
                FULL_PERIOD
            );

        assertEquals(
            4,
            result.size()
        );

        assertEquals(
            4,
            result.get(CheckinCategory.SLEEP_QUALITY)
                .get(0)
                .value()
        );

        assertEquals(
            3,
            result.get(CheckinCategory.DIGESTION_COMFORT)
                .get(0)
                .value()
        );

        assertEquals(
            5,
            result.get(CheckinCategory.WELLBEING)
                .get(0)
                .value()
        );

        assertEquals(
            2,
            result.get(CheckinCategory.MOOD)
                .get(0)
                .value()
        );
    }

    @Test
    void checkinsKeepMissingDaysAsGaps() {
        Entry first = checkin(
            "day-one",
            CheckinCategory.MOOD,
            4,
            "2026-09-13T10:00:00Z"
        );

        Entry third = checkin(
            "day-three",
            CheckinCategory.MOOD,
            5,
            "2026-09-15T10:00:00Z"
        );

        Map<CheckinCategory, List<RatingPoint>> result =
            checkins(
                List.of(first, third),
                FULL_PERIOD
            );

        List<RatingPoint> mood =
            result.get(CheckinCategory.MOOD);

        assertEquals(2, mood.size());
        assertEquals(
            LocalDate.of(2026, 9, 13),
            mood.get(0).date()
        );
        assertEquals(
            LocalDate.of(2026, 9, 15),
            mood.get(1).date()
        );

        assertFalse(
            mood.stream()
                .anyMatch(point ->
                    point.date().equals(
                        LocalDate.of(2026, 9, 14)
                    )
                )
        );
    }

    @Test
    void calculationsAreIndependentOfInputOrder() {
        Entry early = new Entry(
            "early",
            "metrics",
            Status.CONFIRMED,
            Instant.parse("2026-09-19T08:00:00Z"),
            Instant.parse("2026-09-19T08:01:00Z"),
            1L,
            null,
            null,
            Metric.STEPS,
            d("3000"),
            null,
            null,
            null,
            null,
            null,
            null
        );

        Entry late = new Entry(
            "late",
            "metrics",
            Status.CONFIRMED,
            Instant.parse("2026-09-19T18:00:00Z"),
            Instant.parse("2026-09-19T18:01:00Z"),
            2L,
            null,
            null,
            Metric.STEPS,
            d("5000"),
            null,
            null,
            null,
            null,
            null,
            null
        );

        DailyResult forward = dailyMetric(
            List.of(early, late),
            Metric.STEPS,
            FULL_PERIOD
        );

        DailyResult reverse = dailyMetric(
            List.of(late, early),
            Metric.STEPS,
            FULL_PERIOD
        );

        assertEquals(forward, reverse);
        assertEquals(d("5000"), forward.aggregate().total());
    }

    private static Entry checkin(
        String id,
        CheckinCategory category,
        int score,
        String occurredAt
    ) {
        return new Entry(
            id,
            "checkin",
            Status.CONFIRMED,
            Instant.parse(occurredAt),
            null,
            1L,
            null,
            null,
            null,
            null,
            null,
            category,
            score,
            null,
            null,
            null
        );
    }
}
