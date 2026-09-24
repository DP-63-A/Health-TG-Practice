package com.health.analytics;

import org.junit.jupiter.api.Test;

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

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static com.health.analytics.AnalyticsFunctions.*;
import static org.junit.jupiter.api.Assertions.*;


class AnalyticsFunctionsTest {
    private static final ZoneId WARSAW = ZoneId.of("Europe/Warsaw");
    private static final Period WEEK = new Period(LocalDate.of(2026, 9, 13), LocalDate.of(2026, 9, 19), WARSAW);
    private static BigDecimal d(String value) { return new BigDecimal(value); }

    @Test void nutritionScalesPer100gAndKeepsPrecision() {
        Nutrients base = new Nutrients(d("165"), d("10"), d("5"), d("20"));
        NutritionResult result = nutrition(List.of(
                Entry.meal("200g", Status.CONFIRMED, LocalDate.of(2026,9,19), d("200"), base, Basis.PER_100G),
                Entry.meal("150g", Status.CONFIRMED, LocalDate.of(2026,9,19), d("150"), base, Basis.PER_100G)), WEEK);
        assertEquals(d("577.5"), result.energyKcal());
        assertEquals(d("35"), result.proteinG());
        assertEquals(2, result.countedMeals());
    }

    @Test void unknownNutrientsAreNotZeroAndStatusesAreFiltered() {
        Entry unknown = Entry.meal("unknown", Status.CONFIRMED, LocalDate.of(2026,9,19), d("100"), null, Basis.UNKNOWN);
        Entry cancelled = Entry.meal("cancelled", Status.CANCELLED, LocalDate.of(2026,9,19), d("100"),
                new Nutrients(d("999"), d("1"), d("1"), d("1")), Basis.PER_100G);
        NutritionResult result = nutrition(List.of(unknown, cancelled), WEEK);
        assertNull(result.energyKcal()); assertTrue(result.incomplete()); assertEquals(1, result.countedMeals());
    }

    @Test void dailyTotalsDeduplicateAndTieBreak() {
        Instant occurred = Instant.parse("2026-09-19T10:00:00Z");
        Entry old = new Entry("a", "metrics", Status.CONFIRMED, occurred, Instant.parse("2026-09-19T10:01:00Z"), 1L,
                null, null, Metric.STEPS, d("3000"), null, null, null, null, null, (Basis) null);
        Entry latest = new Entry("b", "metrics", Status.CONFIRMED, occurred, Instant.parse("2026-09-19T10:02:00Z"), 2L,
                null, null, Metric.STEPS, d("5000"), null, null, null, null, null, (Basis) null);
        DailyResult result = dailyMetric(List.of(latest, old), Metric.STEPS, WEEK);
        assertEquals(d("5000"), result.aggregate().total()); assertEquals(1, result.aggregate().daysWithData());
    }

    @Test void sleepUsesWakeDateAndAverageUsesDaysWithData() {
        Entry sleep = new Entry("sleep", "metrics", Status.CONFIRMED, Instant.parse("2026-09-18T22:30:00Z"), null, 1L,
                null, LocalDate.of(2026,9,19), Metric.SLEEP_DURATION_MIN, d("420"), null, null, null, null, null, (Basis) null);
        DailyResult result = dailyMetric(List.of(sleep), Metric.SLEEP_DURATION_MIN, WEEK);
        assertEquals(d("420"), result.values().get(LocalDate.of(2026,9,19)));
        assertEquals(d("420"), result.aggregate().average()); assertEquals(1, result.aggregate().daysWithData());
    }

    @Test void timezoneAndHeartRateContextArePreserved() {
        assertEquals(LocalDate.of(2026,9,19), localDate(Instant.parse("2026-09-18T22:30:00Z"), WARSAW));
        Entry pulse = new Entry("pulse", "metrics", Status.CONFIRMED, Instant.parse("2026-09-19T10:15:00Z"), null, 1L,
                null, null, Metric.HEART_RATE, d("72"), Qualifier.RESTING, null, null, null, null, (Basis) null);
        assertEquals(Qualifier.RESTING, latestHeartRate(List.of(pulse), WEEK).orElseThrow().qualifier());
    }

    @Test
    void calculationDoesNotMutateOrDependOnInputOrder() {
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
            d("3000"),
            null,
            null,
            null,
            null,
            null,
            (Basis) null
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
            d("5000"),
            null,
            null,
            null,
            null,
            null,
            (Basis) null
        );

        List<Entry> forward = List.of(first, second);
        List<Entry> reverse = List.of(second, first);

        DailyResult forwardResult = dailyMetric(forward, Metric.STEPS, WEEK);
        DailyResult reverseResult = dailyMetric(reverse, Metric.STEPS, WEEK);

        assertEquals(d("5000"), forwardResult.values().get(LocalDate.of(2026, 9, 19)));
        assertEquals(forwardResult, reverseResult);

        assertEquals(List.of(first, second), forward);
        assertEquals(List.of(second, first), reverse);
        assertEquals("a", first.id());
        assertEquals("b", second.id());
    }

    @Test
    void completelyUnknownNutrientsReturnNullForEveryMetric() {
        Entry meal = Entry.meal(
            "unknown",
            Status.CONFIRMED,
            LocalDate.of(2026, 9, 19),
            d("100"),
            new Nutrients(null, null, null, null),
            Basis.PER_100G
        );

        NutritionResult result = nutrition(List.of(meal), WEEK);

        assertNull(result.energyKcal());
        assertNull(result.proteinG());
        assertNull(result.fatG());
        assertNull(result.carbsG());
        assertEquals(1, result.countedMeals());
        assertTrue(result.incomplete());
    }

    @Test
    void partiallyKnownNutrientsKeepKnownValuesAndReturnNullForUnknownValues() {
        Entry meal = Entry.meal(
            "partial",
            Status.CONFIRMED,
            LocalDate.of(2026, 9, 19),
            d("200"),
            new Nutrients(
                    d("165"),
                    null,
                    d("5"),
                    null
            ),
            Basis.PER_100G
        );

        NutritionResult result = nutrition(List.of(meal), WEEK);

        assertEquals(d("330"), result.energyKcal());
        assertNull(result.proteinG());
        assertEquals(d("10"), result.fatG());
        assertNull(result.carbsG());
        assertEquals(1, result.countedMeals());
        assertTrue(result.incomplete());
    }

    @Test
    void sleepWithoutWakeDateIsExcludedFromEveryDay() {
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
            (Basis) null
        );
    
        assertTrue(sleepDate(sleep, WARSAW).isEmpty());
    
        DailyResult result = dailyMetric(
            List.of(sleep),
            Metric.SLEEP_DURATION_MIN,
            WEEK
        );
    
        assertTrue(result.values().isEmpty());
        assertNull(result.aggregate().total());
        assertNull(result.aggregate().average());
        assertEquals(0, result.aggregate().daysWithData());
    }

    @Test
    void onlyConfirmedEntriesAreIncluded() {
        Nutrients nutrients = new Nutrients(
            d("165"),
            d("10"),
            d("5"),
            d("20")
        );

        List<Entry> entries = List.of(
            Entry.meal("confirmed", Status.CONFIRMED, LocalDate.of(2026, 9, 19),
                    d("100"), nutrients, Basis.PER_100G),
            Entry.meal("draft", Status.DRAFT, LocalDate.of(2026, 9, 19),
                    d("100"), nutrients, Basis.PER_100G),
            Entry.meal("cancelled", Status.CANCELLED, LocalDate.of(2026, 9, 19),
                    d("100"), nutrients, Basis.PER_100G),
            Entry.meal("deleted", Status.DELETED, LocalDate.of(2026, 9, 19),
                    d("100"), nutrients, Basis.PER_100G)
        );

        NutritionResult result = nutrition(entries, WEEK);

        assertEquals(d("165"), result.energyKcal());
        assertEquals(1, result.countedMeals());
    }

    @Test
    void perServingValuesAreNotScaledByMass() {
        Entry meal = Entry.meal(
            "serving",
            Status.CONFIRMED,
            LocalDate.of(2026, 9, 19),
            d("500"),
            new Nutrients(d("600"), d("30"), d("10"), d("80")),
            Basis.PER_SERVING
        );

        NutritionResult result = nutrition(List.of(meal), WEEK);

        assertEquals(d("600"), result.energyKcal());
        assertEquals(d("30"), result.proteinG());
        assertEquals(d("10"), result.fatG());
        assertEquals(d("80"), result.carbsG());
        assertFalse(result.incomplete());
    }

    @Test
    void emptyDayHasNullAggregateAndZeroDaysWithData() {
        DailyResult result = dailyMetric(
            List.of(),
            Metric.STEPS,
            WEEK
        );

        assertTrue(result.values().isEmpty());
        assertNull(result.aggregate().total());
        assertNull(result.aggregate().average());
        assertEquals(0, result.aggregate().daysWithData());
    }

    @Test
    void periodBoundariesAndWarsawMidnightAreHandledCorrectly() {
        Instant beforeWarsawDay = Instant.parse("2026-09-18T21:59:59Z");
        Instant atWarsawDay = Instant.parse("2026-09-18T22:00:00Z");
    
        assertEquals(
            LocalDate.of(2026, 9, 18),
            localDate(beforeWarsawDay, WARSAW)
        );
    
        assertEquals(
            LocalDate.of(2026, 9, 19),
            localDate(atWarsawDay, WARSAW)
        );
    
        Entry before = new Entry(
            "before",
            "metrics",
            Status.CONFIRMED,
            beforeWarsawDay,
            null,
            1L,
            null,
            null,
            Metric.STEPS,
            d("100"),
            null,
            null,
            null,
            null,
            null,
            (Basis) null
        );
    
        Entry atBoundary = new Entry(
            "at-boundary",
            "metrics",
            Status.CONFIRMED,
            atWarsawDay,
            null,
            2L,
            null,
            null,
            Metric.STEPS,
            d("200"),
            null,
            null,
            null,
            null,
            null,
            (Basis) null
        );
    
        Period oneDay = new Period(
            LocalDate.of(2026, 9, 19),
            LocalDate.of(2026, 9, 19),
            WARSAW
        );
    
        DailyResult result = dailyMetric(
            List.of(before, atBoundary),
            Metric.STEPS,
            oneDay
        );
    
        assertEquals(d("200"), result.aggregate().total());
        assertEquals(1, result.aggregate().daysWithData());
    }

    @Test
    void checkinsKeepAllCategoriesAndDoNotFillGaps() {
        LocalDate date = LocalDate.of(2026, 9, 19);
    
        List<Entry> entries = List.of(
            new Entry(
                "sleep-quality",
                "checkin",
                Status.CONFIRMED,
                Instant.parse("2026-09-19T08:00:00Z"),
                null,
                1L,
                date,
                null,
                null,
                null,
                null,
                CheckinCategory.SLEEP_QUALITY,
                4,
                null,
                null,
                (Basis) null
            ),
    
            new Entry(
                "digestion",
                "checkin",
                Status.CONFIRMED,
                Instant.parse("2026-09-19T09:00:00Z"),
                null,
                1L,
                date,
                null,
                null,
                null,
                null,
                CheckinCategory.DIGESTION_COMFORT,
                3,
                null,
                null,
                (Basis) null
            ),
    
            new Entry(
                "wellbeing",
                "checkin",
                Status.CONFIRMED,
                Instant.parse("2026-09-19T10:00:00Z"),
                null,
                1L,
                date,
                null,
                null,
                null,
                null,
                CheckinCategory.WELLBEING,
                5,
                null,
                null,
                (Basis) null
            ),
    
            new Entry(
                "mood",
                "checkin",
                Status.CONFIRMED,
                Instant.parse("2026-09-19T11:00:00Z"),
                null,
                1L,
                date,
                null,
                null,
                null,
                null,
                CheckinCategory.MOOD,
                2,
                null,
                null,
                (Basis) null
            )
        );
    
        Map<CheckinCategory, List<RatingPoint>> result =
            checkins(entries, WEEK);
    
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
    void portionFactorScalesMassRelativeTo100Grams() {
        assertEquals(d("2"), portionFactor(d("200")));
        assertEquals(d("1.5"), portionFactor(d("150")));
        assertEquals(d("0"), portionFactor(d("0")));
    }

    @Test
    void checkinsAreIndependentOfInputOrderAndUseDeterministicTieBreak() {
        Instant occurredAt = Instant.parse("2026-09-19T10:00:00Z");
        Instant updatedAt = Instant.parse("2026-09-19T10:05:00Z");
    
        Entry first = new Entry(
            "a",
            "checkin",
            Status.CONFIRMED,
            occurredAt,
            updatedAt,
            1L,
            LocalDate.of(2026, 9, 19),
            null,
            null,
            null,
            null,
            CheckinCategory.MOOD,
            2,
            null,
            null,
            (Basis) null
        );
    
        Entry second = new Entry(
            "b",
            "checkin",
            Status.CONFIRMED,
            occurredAt,
            updatedAt,
            2L,
            LocalDate.of(2026, 9, 19),
            null,
            null,
            null,
            null,
            CheckinCategory.MOOD,
            5,
            null,
            null,
            (Basis) null
        );
    
        Map<CheckinCategory, List<RatingPoint>> forward =
            checkins(List.of(first, second), WEEK);
    
        Map<CheckinCategory, List<RatingPoint>> reverse =
            checkins(List.of(second, first), WEEK);
    
        assertEquals(forward, reverse);
    
        RatingPoint selected =
            forward.get(CheckinCategory.MOOD).get(0);
    
        assertEquals("b", selected.entryId());
        assertEquals(5, selected.value());
    }

    @Test
    void dailyMetricIgnoresMealWithMetricField() {
        Entry invalidMeal = new Entry(
            "invalid-meal-metrics",
            "meal",
            Status.CONFIRMED,
            Instant.parse("2026-09-19T10:00:00Z"),
            Instant.parse("2026-09-19T10:01:00Z"),
            1L,
            LocalDate.of(2026, 9, 19),
            null,
            Metric.STEPS,
            d("9999"),
            null,
            null,
            null,
            null,
            null,
            (Basis) null
        );
    
        DailyResult result = dailyMetric(
            List.of(invalidMeal),
            Metric.STEPS,
            WEEK
        );
    
        assertTrue(result.values().isEmpty());
        assertNull(result.aggregate().total());
        assertNull(result.aggregate().average());
        assertEquals(0, result.aggregate().daysWithData());
    }

    @Test
    void latestHeartRateIgnoresCheckinWithHeartRateMetric() {
        Entry invalidCheckin = new Entry(
            "invalid-checkin-heart-rate",
            "checkin",
            Status.CONFIRMED,
            Instant.parse("2026-09-19T10:00:00Z"),
            Instant.parse("2026-09-19T10:01:00Z"),
            1L,
            LocalDate.of(2026, 9, 19),
            null,
            Metric.HEART_RATE,
            d("220"),
            Qualifier.RESTING,
            CheckinCategory.MOOD,
            5,
            null,
            null,
            (Basis) null
        );
    
        Optional<HeartRateResult> result = latestHeartRate(
            List.of(invalidCheckin),
            WEEK
        );
    
        assertTrue(result.isEmpty());
    }

    @Test
    void checkinsIgnoreMetricsWithCheckinFields() {
        Entry invalidMetrics = new Entry(
            "invalid-metrics-checkin",
            "metrics",
            Status.CONFIRMED,
            Instant.parse("2026-09-19T10:00:00Z"),
            Instant.parse("2026-09-19T10:01:00Z"),
            1L,
            LocalDate.of(2026, 9, 19),
            null,
            null,
            null,
            null,
            CheckinCategory.MOOD,
            5,
            null,
            null,
            (Basis) null
        );
    
        Map<CheckinCategory, List<RatingPoint>> result =
            checkins(List.of(invalidMetrics), WEEK);
    
        assertTrue(result.isEmpty());
    }

    @Test
    void checkinsIgnoreMealWithCheckinFields() {
        Entry invalidMeal = new Entry(
            "invalid-meal-checkin",
            "meal",
            Status.CONFIRMED,
            Instant.parse("2026-09-19T10:00:00Z"),
            Instant.parse("2026-09-19T10:01:00Z"),
            1L,
            LocalDate.of(2026, 9, 19),
            null,
            null,
            null,
            null,
            CheckinCategory.MOOD,
            5,
            d("100"),
            null,
            Basis.PER_100G
        );
    
        Map<CheckinCategory, List<RatingPoint>> result =
            checkins(List.of(invalidMeal), WEEK);
    
        assertTrue(result.isEmpty());
    }

    @Test
    void be301NormalFixtureProduces930Calories5000StepsAnd900SleepMinutes() {
        Nutrients firstMealNutrients = new Nutrients(
            d("600"),
            d("30"),
            d("20"),
            d("70")
        );

        Nutrients secondMealNutrients = new Nutrients(
            d("165"),
            d("10"),
            d("5"),
            d("20")
        );

        Entry firstMeal = Entry.meal(
            "22222222-2222-4222-8222-222222222210",
            Status.CONFIRMED,
            LocalDate.of(2026, 9, 19),
            null,
            firstMealNutrients,
            Basis.PER_SERVING
        );

        Entry secondMeal = Entry.meal(
            "22222222-2222-4222-8222-222222222211",
            Status.CONFIRMED,
            LocalDate.of(2026, 9, 19),
            d("200"),
            secondMealNutrients,
            Basis.PER_100G
        );

        Entry firstSteps = new Entry(
            "22222222-2222-4222-8222-222222222214",
            "metrics",
            Status.CONFIRMED,
            Instant.parse("2026-09-19T10:00:00Z"),
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
            (Basis) null
        );

        Entry secondSteps = new Entry(
            "22222222-2222-4222-8222-222222222215",
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
            (Basis) null
        );

        Entry firstSleep = new Entry(
            "22222222-2222-4222-8222-222222222216",
            "metrics",
            Status.CONFIRMED,
            Instant.parse("2026-09-14T07:00:00Z"),
            null,
            1L,
            null,
            LocalDate.of(2026, 9, 14),
            Metric.SLEEP_DURATION_MIN,
            d("420"),
            null,
            null,
            null,
            null,
            null,
            (Basis) null
        );

        Entry secondSleep = new Entry(
            "22222222-2222-4222-8222-222222222217",
            "metrics",
            Status.CONFIRMED,
            Instant.parse("2026-09-16T07:00:00Z"),
            null,
            1L,
            null,
            LocalDate.of(2026, 9, 16),
            Metric.SLEEP_DURATION_MIN,
            d("480"),
            null,
            null,
            null,
            null,
            null,
            (Basis) null
        );
    
        List<Entry> entries = List.of(
            firstMeal,
            secondMeal,
            firstSteps,
            secondSteps,
            firstSleep,
            secondSleep
        );

        NutritionResult nutritionResult = nutrition(entries, WEEK);

        assertEquals(d("930"), nutritionResult.energyKcal());
        assertEquals(2, nutritionResult.countedMeals());
        assertFalse(nutritionResult.incomplete());

        DailyResult stepsResult = dailyMetric(
            entries,
            Metric.STEPS,
            WEEK
        );
    
        assertEquals(d("5000"), stepsResult.aggregate().total());
        assertEquals(d("5000"), stepsResult.aggregate().average());
        assertEquals(1, stepsResult.aggregate().daysWithData());

        DailyResult sleepResult = dailyMetric(
            entries,
            Metric.SLEEP_DURATION_MIN,
            WEEK
        );

        assertEquals(d("900"), sleepResult.aggregate().total());
        assertEquals(d("450"), sleepResult.aggregate().average());
        assertEquals(2, sleepResult.aggregate().daysWithData());
    }

    @Test
    void be301MassChangedFixtureProduces847Point5Calories() {
        Nutrients firstMealNutrients = new Nutrients(
            d("600"),
            d("30"),
            d("20"),
            d("70")
        );

        Nutrients changedMealNutrients = new Nutrients(
            d("165"),
            d("10"),
            d("5"),
            d("20")
        );
    
        Entry firstMeal = Entry.meal(
            "first-meal",
            Status.CONFIRMED,
            LocalDate.of(2026, 9, 19),
            null,
            firstMealNutrients,
            Basis.PER_SERVING
        );

        Entry changedMeal = Entry.meal(
            "changed-meal",
            Status.CONFIRMED,
            LocalDate.of(2026, 9, 19),
            d("150"),
            changedMealNutrients,
            Basis.PER_100G
        );

        NutritionResult result = nutrition(
            List.of(firstMeal, changedMeal),
            WEEK
        );

        assertEquals(d("847.5"), result.energyKcal());
        assertEquals(2, result.countedMeals());
        assertFalse(result.incomplete());
    }

    @Test
    void be301FilteredFixtureIgnoresCancelledDeletedAndDraftEntries() {
        Nutrients nutrients = new Nutrients(
            d("600"),
            d("30"),
            d("20"),
            d("70")
        );

        Entry confirmed = Entry.meal(
            "confirmed",
            Status.CONFIRMED,
            LocalDate.of(2026, 9, 19),
            null,
            nutrients,
            Basis.PER_SERVING
        );

        Entry cancelled = Entry.meal(
            "cancelled",
            Status.CANCELLED,
            LocalDate.of(2026, 9, 19),
            null,
            new Nutrients(d("999"), d("999"), d("999"), d("999")),
            Basis.PER_SERVING
        );

        Entry deleted = Entry.meal(
            "deleted",
            Status.DELETED,
            LocalDate.of(2026, 9, 19),
            null,
            new Nutrients(d("999"), d("999"), d("999"), d("999")),
            Basis.PER_SERVING
        );

        Entry draft = Entry.meal(
            "draft",
            Status.DRAFT,
            LocalDate.of(2026, 9, 19),
            null,
            new Nutrients(d("999"), d("999"), d("999"), d("999")),
            Basis.PER_SERVING
        );

        NutritionResult result = nutrition(
            List.of(confirmed, cancelled, deleted, draft),
            WEEK
        );

        assertEquals(d("600"), result.energyKcal());
        assertEquals(1, result.countedMeals());
        assertFalse(result.incomplete());
    }

    @Test
    void be301GapsFixtureKeepsMissingDaysMissing() {
        Entry sleep = new Entry(
        "sleep-day-one",
        "metrics",
        Status.CONFIRMED,
        Instant.parse("2026-09-14T07:00:00Z"),
        null,
        1L,
        null,
        LocalDate.of(2026, 9, 14),
        Metric.SLEEP_DURATION_MIN,
        d("420"),
        null,
        null,
        null,
        null,
        null,
        (Basis) null
    );

        DailyResult result = dailyMetric(
            List.of(sleep),
            Metric.SLEEP_DURATION_MIN,
            WEEK
        );

        assertEquals(1, result.values().size());
        assertEquals(d("420"), result.values().get(LocalDate.of(2026, 9, 14)));
        assertEquals(d("420"), result.aggregate().total());
        assertEquals(d("420"), result.aggregate().average());
        assertEquals(1, result.aggregate().daysWithData());

        assertFalse(result.values().containsKey(LocalDate.of(2026, 9, 15)));
        assertFalse(result.values().containsKey(LocalDate.of(2026, 9, 16)));
    }
}