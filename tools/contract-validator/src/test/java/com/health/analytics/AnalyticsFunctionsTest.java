package com.health.analytics;

import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.List;

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
                null, null, Metric.STEPS, d("3000"), null, null, null, null, null);
        Entry latest = new Entry("b", "metrics", Status.CONFIRMED, occurred, Instant.parse("2026-09-19T10:02:00Z"), 2L,
                null, null, Metric.STEPS, d("5000"), null, null, null, null, null);
        DailyResult result = dailyMetric(List.of(latest, old), Metric.STEPS, WEEK);
        assertEquals(d("5000"), result.aggregate().total()); assertEquals(1, result.aggregate().daysWithData());
    }

    @Test void sleepUsesWakeDateAndAverageUsesDaysWithData() {
        Entry sleep = new Entry("sleep", "metrics", Status.CONFIRMED, Instant.parse("2026-09-18T22:30:00Z"), null, 1L,
                null, LocalDate.of(2026,9,19), Metric.SLEEP_DURATION_MIN, d("420"), null, null, null, null, null);
        DailyResult result = dailyMetric(List.of(sleep), Metric.SLEEP_DURATION_MIN, WEEK);
        assertEquals(d("420"), result.values().get(LocalDate.of(2026,9,19)));
        assertEquals(d("420"), result.aggregate().average()); assertEquals(1, result.aggregate().daysWithData());
    }

    @Test void timezoneAndHeartRateContextArePreserved() {
        assertEquals(LocalDate.of(2026,9,19), localDate(Instant.parse("2026-09-18T22:30:00Z"), WARSAW));
        Entry pulse = new Entry("pulse", "metrics", Status.CONFIRMED, Instant.parse("2026-09-19T10:15:00Z"), null, 1L,
                null, null, Metric.HEART_RATE, d("72"), Qualifier.RESTING, null, null, null, null);
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
            d("5000"),
            null,
            null,
            null,
            null,
            null
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
}
