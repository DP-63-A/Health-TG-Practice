package com.health.analytics;

import com.health.analytics.AnalyticsFunctions.Basis;
import com.health.analytics.AnalyticsFunctions.CheckinCategory;
import com.health.analytics.AnalyticsFunctions.DailyResult;
import com.health.analytics.AnalyticsFunctions.Entry;
import com.health.analytics.AnalyticsFunctions.HeartRateResult;
import com.health.analytics.AnalyticsFunctions.Metric;
import com.health.analytics.AnalyticsFunctions.Nutrients;
import com.health.analytics.AnalyticsFunctions.NutritionResult;
import com.health.analytics.AnalyticsFunctions.Period;
import com.health.analytics.AnalyticsFunctions.Qualifier;
import com.health.analytics.AnalyticsFunctions.RatingPoint;
import com.health.analytics.AnalyticsFunctions.Status;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static com.health.analytics.AnalyticsFunctions.checkins;
import static com.health.analytics.AnalyticsFunctions.dailyMetric;
import static com.health.analytics.AnalyticsFunctions.latestHeartRate;
import static com.health.analytics.AnalyticsFunctions.localDate;
import static com.health.analytics.AnalyticsFunctions.nutrition;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class AnalyticsFunctionsIndependentOracleTest {

    private static final ZoneId WARSAW = ZoneId.of("Europe/Warsaw");

    private static final Period SEVEN_DAYS = new Period(
            LocalDate.of(2026, 9, 13),
            LocalDate.of(2026, 9, 19),
            WARSAW
    );

    private static final BigDecimal ZERO = new BigDecimal("0");

    private static BigDecimal decimal(String value) {
        return new BigDecimal(value);
    }

    @Test
    void nutritionUsesIndependent930KcalOracle() {
        Nutrients serving = new Nutrients(
                decimal("600"),
                decimal("30"),
                decimal("20"),
                decimal("70")
        );

        Nutrients per100g = new Nutrients(
                decimal("165"),
                decimal("10"),
                decimal("5"),
                decimal("20")
        );

        Entry firstMeal = Entry.meal(
                "meal-serving",
                Status.CONFIRMED,
                LocalDate.of(2026, 9, 19),
                null,
                serving,
                Basis.PER_SERVING
        );

        Entry secondMeal = Entry.meal(
                "meal-200g",
                Status.CONFIRMED,
                LocalDate.of(2026, 9, 19),
                decimal("200"),
                per100g,
                Basis.PER_100G
        );

        NutritionResult result = nutrition(
                List.of(firstMeal, secondMeal),
                SEVEN_DAYS
        );

        assertEquals(decimal("930"), result.energyKcal());
        assertEquals(decimal("50"), result.proteinG());
        assertEquals(decimal("30"), result.fatG());
        assertEquals(decimal("110"), result.carbsG());
        assertEquals(2, result.countedMeals());
        assertFalse(result.incomplete());
    }

    @Test
    void nutritionUsesIndependent847Point5KcalOracleAfterMassChange() {
        Nutrients serving = new Nutrients(
                decimal("600"),
                decimal("30"),
                decimal("20"),
                decimal("70")
        );

        Nutrients per100g = new Nutrients(
                decimal("165"),
                decimal("10"),
                decimal("5"),
                decimal("20")
        );

        Entry firstMeal = Entry.meal(
                "meal-serving",
                Status.CONFIRMED,
                LocalDate.of(2026, 9, 19),
                null,
                serving,
                Basis.PER_SERVING
        );

        Entry changedMeal = Entry.meal(
                "meal-150g",
                Status.CONFIRMED,
                LocalDate.of(2026, 9, 19),
                decimal("150"),
                per100g,
                Basis.PER_100G
        );

        NutritionResult result = nutrition(
                List.of(firstMeal, changedMeal),
                SEVEN_DAYS
        );

        assertEquals(decimal("847.5"), result.energyKcal());
        assertEquals(decimal("45"), result.proteinG());
        assertEquals(decimal("27.5"), result.fatG());
        assertEquals(decimal("100"), result.carbsG());
        assertEquals(2, result.countedMeals());
        assertFalse(result.incomplete());
    }

    @Test
    void nutritionUsesIndependent600KcalOracleWhenSecondMealIsAbsent() {
        Nutrients serving = new Nutrients(
                decimal("600"),
                decimal("30"),
                decimal("20"),
                decimal("70")
        );

        Entry remainingMeal = Entry.meal(
                "meal-serving",
                Status.CONFIRMED,
                LocalDate.of(2026, 9, 19),
                null,
                serving,
                Basis.PER_SERVING
        );

        NutritionResult result = nutrition(
                List.of(remainingMeal),
                SEVEN_DAYS
        );

        assertEquals(decimal("600"), result.energyKcal());
        assertEquals(decimal("30"), result.proteinG());
        assertEquals(decimal("20"), result.fatG());
        assertEquals(decimal("70"), result.carbsG());
        assertEquals(1, result.countedMeals());
        assertFalse(result.incomplete());
    }

    @Test
    void per100gScalingUses200gAsExactlyTwoHundredPercent() {
        Nutrients nutrients = new Nutrients(
                decimal("330"),
                decimal("20"),
                decimal("10"),
                decimal("40")
        );

        Entry meal = Entry.meal(
                "200g-meal",
                Status.CONFIRMED,
                LocalDate.of(2026, 9, 19),
                decimal("200"),
                nutrients,
                Basis.PER_100G
        );

        NutritionResult result = nutrition(
                List.of(meal),
                SEVEN_DAYS
        );

        assertEquals(decimal("660"), result.energyKcal());
        assertEquals(decimal("40"), result.proteinG());
        assertEquals(decimal("20"), result.fatG());
        assertEquals(decimal("80"), result.carbsG());
    }

    @Test
    void perServingValuesAreNotScaledByMass() {
        Nutrients nutrients = new Nutrients(
                decimal("600"),
                decimal("30"),
                decimal("10"),
                decimal("80")
        );

        Entry meal = Entry.meal(
                "serving",
                Status.CONFIRMED,
                LocalDate.of(2026, 9, 19),
                decimal("500"),
                nutrients,
                Basis.PER_SERVING
        );

        NutritionResult result = nutrition(
                List.of(meal),
                SEVEN_DAYS
        );

        assertEquals(decimal("600"), result.energyKcal());
        assertEquals(decimal("30"), result.proteinG());
        assertEquals(decimal("10"), result.fatG());
        assertEquals(decimal("80"), result.carbsG());
        assertEquals(1, result.countedMeals());
        assertFalse(result.incomplete());
    }

    @Test
    void unknownNutrientBasisProducesMissingValuesInsteadOfZero() {
        Nutrients nutrients = new Nutrients(
                decimal("330"),
                decimal("20"),
                decimal("10"),
                decimal("40")
        );

        Entry meal = Entry.meal(
                "unknown-basis",
                Status.CONFIRMED,
                LocalDate.of(2026, 9, 19),
                decimal("100"),
                nutrients,
                Basis.UNKNOWN
        );

        NutritionResult result = nutrition(
                List.of(meal),
                SEVEN_DAYS
        );

        assertNull(result.energyKcal());
        assertNull(result.proteinG());
        assertNull(result.fatG());
        assertNull(result.carbsG());
        assertEquals(1, result.countedMeals());
        assertTrue(result.incomplete());
    }

    @Test
    void missingNutrientsProduceMissingValuesInsteadOfZero() {
        Entry meal = Entry.meal(
                "missing-nutrients",
                Status.CONFIRMED,
                LocalDate.of(2026, 9, 19),
                decimal("100"),
                null,
                Basis.PER_100G
        );

        NutritionResult result = nutrition(
                List.of(meal),
                SEVEN_DAYS
        );

        assertNull(result.energyKcal());
        assertNull(result.proteinG());
        assertNull(result.fatG());
        assertNull(result.carbsG());
        assertEquals(1, result.countedMeals());
        assertTrue(result.incomplete());
    }

    @Test
    void incompleteNutrientComponentDoesNotBecomeZero() {
        Nutrients nutrients = new Nutrients(
                decimal("330"),
                null,
                decimal("10"),
                decimal("40")
        );

        Entry meal = Entry.meal(
                "missing-protein",
                Status.CONFIRMED,
                LocalDate.of(2026, 9, 19),
                decimal("100"),
                nutrients,
                Basis.PER_100G
        );

        NutritionResult result = nutrition(
                List.of(meal),
                SEVEN_DAYS
        );

        assertEquals(decimal("330"), result.energyKcal());
        assertNull(result.proteinG());
        assertEquals(decimal("10"), result.fatG());
        assertEquals(decimal("40"), result.carbsG());
        assertTrue(result.incomplete());
    }

    @Test
    void nonConfirmedStatusesDoNotContributeToNutrition() {
        Nutrients valid = new Nutrients(
                decimal("600"),
                decimal("30"),
                decimal("20"),
                decimal("70")
        );

        Nutrients invalid = new Nutrients(
                decimal("999"),
                decimal("999"),
                decimal("999"),
                decimal("999")
        );

        Entry confirmed = Entry.meal(
                "confirmed",
                Status.CONFIRMED,
                LocalDate.of(2026, 9, 19),
                null,
                valid,
                Basis.PER_SERVING
        );

        Entry draft = Entry.meal(
                "draft",
                Status.DRAFT,
                LocalDate.of(2026, 9, 19),
                null,
                invalid,
                Basis.PER_SERVING
        );

        Entry cancelled = Entry.meal(
                "cancelled",
                Status.CANCELLED,
                LocalDate.of(2026, 9, 19),
                null,
                invalid,
                Basis.PER_SERVING
        );

        Entry deleted = Entry.meal(
                "deleted",
                Status.DELETED,
                LocalDate.of(2026, 9, 19),
                null,
                invalid,
                Basis.PER_SERVING
        );

        NutritionResult result = nutrition(
                List.of(
                        confirmed,
                        draft,
                        cancelled,
                        deleted
                ),
                SEVEN_DAYS
        );

        assertEquals(decimal("600"), result.energyKcal());
        assertEquals(decimal("30"), result.proteinG());
        assertEquals(decimal("20"), result.fatG());
        assertEquals(decimal("70"), result.carbsG());
        assertEquals(1, result.countedMeals());
        assertFalse(result.incomplete());
    }

    @Test
    void emptyDayHasNullAggregateAndZeroDenominator() {
        DailyResult result = dailyMetric(
                List.of(),
                Metric.STEPS,
                SEVEN_DAYS
        );

        assertTrue(result.values().isEmpty());
        assertNull(result.aggregate().total());
        assertNull(result.aggregate().average());
        assertEquals(0, result.aggregate().daysWithData());
    }

    @Test
    void averageUsesDaysWithDataRatherThanLengthOfPeriod() {
        Entry first = steps(
                "steps-1",
                "2026-09-14T10:00:00Z",
                "2026-09-14T10:01:00Z",
                "300"
        );

        Entry second = steps(
                "steps-2",
                "2026-09-16T10:00:00Z",
                "2026-09-16T10:01:00Z",
                "500"
        );

        DailyResult result = dailyMetric(
                List.of(first, second),
                Metric.STEPS,
                SEVEN_DAYS
        );

        assertEquals(decimal("800"), result.aggregate().total());

        // 800 / 2 days with data = 400.
        // It must not be divided by all seven calendar days.
        assertEquals(decimal("400"), result.aggregate().average());

        assertEquals(2, result.aggregate().daysWithData());
    }

    @Test
    void dailyMetricSelectsOneLatestValuePerDayInsteadOfSummingDailyTotals() {
        Entry first = steps(
                "old",
                "2026-09-19T10:00:00Z",
                "2026-09-19T10:01:00Z",
                "3000"
        );

        Entry second = steps(
                "new",
                "2026-09-19T18:00:00Z",
                "2026-09-19T18:01:00Z",
                "5000"
        );

        DailyResult result = dailyMetric(
                List.of(first, second),
                Metric.STEPS,
                SEVEN_DAYS
        );

        // The expected value is 5000, not 8000.
        assertEquals(decimal("5000"), result.aggregate().total());
        assertEquals(decimal("5000"), result.aggregate().average());
        assertEquals(1, result.aggregate().daysWithData());
    }

    @Test
    void equalOccurredAtAndUpdatedAtUseStableIdAsTieBreak() {
        Instant occurredAt = Instant.parse("2026-09-19T10:00:00Z");
        Instant updatedAt = Instant.parse("2026-09-19T10:05:00Z");

        Entry first = new Entry(
                "a",
                "metrics",
                Status.CONFIRMED,
                occurredAt,
                updatedAt,
                1L,
                null,
                null,
                Metric.STEPS,
                decimal("3000"),
                null,
                null,
                null,
                null,
                null,
                null
        );

        Entry second = new Entry(
                "b",
                "metrics",
                Status.CONFIRMED,
                occurredAt,
                updatedAt,
                2L,
                null,
                null,
                Metric.STEPS,
                decimal("5000"),
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
                SEVEN_DAYS
        );

        assertEquals(decimal("5000"), result.aggregate().total());
        assertEquals(
                decimal("5000"),
                result.values().get(LocalDate.of(2026, 9, 19))
        );
    }

    @Test
    void dailyMetricResultDoesNotDependOnInputOrder() {
        Entry first = steps(
                "a",
                "2026-09-19T10:00:00Z",
                "2026-09-19T10:05:00Z",
                "3000"
        );

        Entry second = steps(
                "b",
                "2026-09-19T10:00:00Z",
                "2026-09-19T10:05:00Z",
                "5000"
        );

        DailyResult forward = dailyMetric(
                List.of(first, second),
                Metric.STEPS,
                SEVEN_DAYS
        );

        DailyResult reverse = dailyMetric(
                List.of(second, first),
                Metric.STEPS,
                SEVEN_DAYS
        );

        assertEquals(forward, reverse);
        assertEquals(decimal("5000"), forward.aggregate().total());
    }

    @Test
    void sleepIsAssignedToWakeDate() {
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
                decimal("420"),
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
                SEVEN_DAYS
        );

        assertEquals(
                decimal("420"),
                result.values().get(LocalDate.of(2026, 9, 19))
        );

        assertEquals(decimal("420"), result.aggregate().total());
        assertEquals(decimal("420"), result.aggregate().average());
        assertEquals(1, result.aggregate().daysWithData());
    }

    @Test
    void missingSleepWakeDateDoesNotCreateArtificialDay() {
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
                decimal("420"),
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
                SEVEN_DAYS
        );

        assertTrue(result.values().isEmpty());
        assertNull(result.aggregate().total());
        assertNull(result.aggregate().average());
        assertEquals(0, result.aggregate().daysWithData());
    }

    @Test
    void warsawMidnightChangesLocalDateAtExpectedInstant() {
        Instant beforeMidnight = Instant.parse(
                "2026-09-18T21:59:59Z"
        );

        Instant atMidnight = Instant.parse(
                "2026-09-18T22:00:00Z"
        );

        assertEquals(
                LocalDate.of(2026, 9, 18),
                localDate(beforeMidnight, WARSAW)
        );

        assertEquals(
                LocalDate.of(2026, 9, 19),
                localDate(atMidnight, WARSAW)
        );
    }

    @Test
    void valueExactlyAtWarsawMidnightBelongsToFollowingLocalDay() {
        Entry entry = steps(
                "midnight",
                "2026-09-18T22:00:00Z",
                "2026-09-18T22:01:00Z",
                "200"
        );

        Period onlySeptember19 = new Period(
                LocalDate.of(2026, 9, 19),
                LocalDate.of(2026, 9, 19),
                WARSAW
        );

        DailyResult result = dailyMetric(
                List.of(entry),
                Metric.STEPS,
                onlySeptember19
        );

        assertEquals(decimal("200"), result.aggregate().total());
        assertEquals(1, result.aggregate().daysWithData());
    }

    @Test
    void valueBeforeWarsawMidnightDoesNotLeakIntoFollowingDay() {
        Entry entry = steps(
                "before-midnight",
                "2026-09-18T21:59:59Z",
                "2026-09-18T22:00:00Z",
                "100"
        );

        Period onlySeptember19 = new Period(
                LocalDate.of(2026, 9, 19),
                LocalDate.of(2026, 9, 19),
                WARSAW
        );

        DailyResult result = dailyMetric(
                List.of(entry),
                Metric.STEPS,
                onlySeptember19
        );

        assertTrue(result.values().isEmpty());
        assertNull(result.aggregate().total());
        assertNull(result.aggregate().average());
        assertEquals(0, result.aggregate().daysWithData());
    }

    @Test
    void latestHeartRateKeepsValueQualifierAndSourceId() {
        Entry pulse = new Entry(
                "pulse",
                "metrics",
                Status.CONFIRMED,
                Instant.parse("2026-09-19T10:15:00Z"),
                null,
                1L,
                null,
                null,
                Metric.HEART_RATE,
                decimal("72"),
                Qualifier.RESTING,
                null,
                null,
                null,
                null,
                null
        );

        Optional<HeartRateResult> result = latestHeartRate(
                List.of(pulse),
                SEVEN_DAYS
        );

        assertTrue(result.isPresent());

        HeartRateResult heartRate = result.orElseThrow();

        assertEquals(decimal("72"), heartRate.value());
        assertEquals(Qualifier.RESTING, heartRate.qualifier());
        assertEquals("pulse", heartRate.entryId());
    }

    @Test
    void latestHeartRateSelectsLatestSample() {
        Entry earlier = new Entry(
                "pulse-old",
                "metrics",
                Status.CONFIRMED,
                Instant.parse("2026-09-19T09:00:00Z"),
                null,
                1L,
                null,
                null,
                Metric.HEART_RATE,
                decimal("65"),
                Qualifier.RESTING,
                null,
                null,
                null,
                null,
                null
        );

        Entry later = new Entry(
                "pulse-new",
                "metrics",
                Status.CONFIRMED,
                Instant.parse("2026-09-19T10:00:00Z"),
                null,
                1L,
                null,
                null,
                Metric.HEART_RATE,
                decimal("72"),
                Qualifier.INSTANT,
                null,
                null,
                null,
                null,
                null
        );

        Optional<HeartRateResult> result = latestHeartRate(
                List.of(earlier, later),
                SEVEN_DAYS
        );

        HeartRateResult heartRate = result.orElseThrow();

        assertEquals(decimal("72"), heartRate.value());
        assertEquals(Qualifier.INSTANT, heartRate.qualifier());
        assertEquals("pulse-new", heartRate.entryId());
    }

    @Test
    void checkinsRemainSeparatedByCategory() {
        LocalDate date = LocalDate.of(2026, 9, 19);

        Entry sleepQuality = checkin(
                "sleep-quality",
                date,
                "2026-09-19T08:00:00Z",
                CheckinCategory.SLEEP_QUALITY,
                4
        );

        Entry digestion = checkin(
                "digestion",
                date,
                "2026-09-19T09:00:00Z",
                CheckinCategory.DIGESTION_COMFORT,
                3
        );

        Entry wellbeing = checkin(
                "wellbeing",
                date,
                "2026-09-19T10:00:00Z",
                CheckinCategory.WELLBEING,
                5
        );

        Entry mood = checkin(
                "mood",
                date,
                "2026-09-19T11:00:00Z",
                CheckinCategory.MOOD,
                2
        );

        Map<CheckinCategory, List<RatingPoint>> result = checkins(
                List.of(
                        sleepQuality,
                        digestion,
                        wellbeing,
                        mood
                ),
                SEVEN_DAYS
        );

        assertEquals(4, result.size());

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
    void checkinsDoNotFillMissingDays() {
        LocalDate firstDay = LocalDate.of(2026, 9, 14);
        LocalDate thirdDay = LocalDate.of(2026, 9, 16);

        Entry first = checkin(
                "mood-first",
                firstDay,
                "2026-09-14T10:00:00Z",
                CheckinCategory.MOOD,
                2
        );

        Entry third = checkin(
                "mood-third",
                thirdDay,
                "2026-09-16T10:00:00Z",
                CheckinCategory.MOOD,
                5
        );

        Map<CheckinCategory, List<RatingPoint>> result = checkins(
                List.of(first, third),
                SEVEN_DAYS
        );

        List<RatingPoint> mood = result.get(CheckinCategory.MOOD);

        assertEquals(2, mood.size());

        assertEquals(firstDay, mood.get(0).date());
        assertEquals(2, mood.get(0).value());

        assertEquals(thirdDay, mood.get(1).date());
        assertEquals(5, mood.get(1).value());

        assertFalse(
                mood.stream()
                        .anyMatch(point ->
                                point.date().equals(
                                        LocalDate.of(2026, 9, 15)
                                )
                        )
        );
    }

    @Test
    void dailyMetricIgnoresRecordsOfWrongType() {
        Entry invalidMeal = new Entry(
                "wrong-type",
                "meal",
                Status.CONFIRMED,
                Instant.parse("2026-09-19T10:00:00Z"),
                Instant.parse("2026-09-19T10:01:00Z"),
                1L,
                LocalDate.of(2026, 9, 19),
                null,
                Metric.STEPS,
                decimal("9999"),
                null,
                null,
                null,
                null,
                null,
                null
        );

        DailyResult result = dailyMetric(
                List.of(invalidMeal),
                Metric.STEPS,
                SEVEN_DAYS
        );

        assertTrue(result.values().isEmpty());
        assertNull(result.aggregate().total());
        assertNull(result.aggregate().average());
        assertEquals(0, result.aggregate().daysWithData());
    }

    @Test
    void latestHeartRateIgnoresRecordsOfWrongType() {
        Entry invalidCheckin = new Entry(
                "wrong-type",
                "checkin",
                Status.CONFIRMED,
                Instant.parse("2026-09-19T10:00:00Z"),
                null,
                1L,
                LocalDate.of(2026, 9, 19),
                null,
                Metric.HEART_RATE,
                decimal("220"),
                Qualifier.RESTING,
                CheckinCategory.MOOD,
                5,
                null,
                null,
                null
        );

        Optional<HeartRateResult> result = latestHeartRate(
                List.of(invalidCheckin),
                SEVEN_DAYS
        );

        assertTrue(result.isEmpty());
    }

    private static Entry steps(
            String id,
            String occurredAt,
            String updatedAt,
            String value
    ) {
        return new Entry(
                id,
                "metrics",
                Status.CONFIRMED,
                Instant.parse(occurredAt),
                Instant.parse(updatedAt),
                1L,
                null,
                null,
                Metric.STEPS,
                decimal(value),
                null,
                null,
                null,
                null,
                null,
                null
        );
    }

    private static Entry checkin(
            String id,
            LocalDate date,
            String occurredAt,
            CheckinCategory category,
            int score
    ) {
        return new Entry(
                id,
                "checkin",
                Status.CONFIRMED,
                Instant.parse(occurredAt),
                null,
                1L,
                date,
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
