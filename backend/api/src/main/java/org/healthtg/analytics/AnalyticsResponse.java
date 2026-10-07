package org.healthtg.analytics;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalTime;
import java.util.List;
import java.util.Map;
import java.util.UUID;

public record AnalyticsResponse(Period period, Cards cards, Series series,
                                Observations observations, List<Source> sources) {
    public record Period(String kind, LocalDate from, LocalDate to, String timezone) { }

    public record Cards(Nutrition nutrition, MealCount mealCount, AggregateMinutes sleep,
                        AggregateCount steps, HeartRate heartRate, Map<String, Rating> checkins) { }

    public record Nutrition(BigDecimal energyKcal, BigDecimal proteinG, BigDecimal fatG,
                            BigDecimal carbsG, boolean incomplete, int mealsWithEnergy) { }

    public record MealCount(int count) { }

    public record AggregateMinutes(Integer totalMinutes, BigDecimal averageMinutes, int daysWithData) { }

    public record AggregateCount(Long total, BigDecimal average, int daysWithData) { }

    public record HeartRate(BigDecimal valueBpm, Instant occurredAt, LocalDate localDate,
                            LocalTime localTime, String qualifier, UUID entryId) { }

    public record Rating(Integer score, LocalDate date, UUID entryId) { }

    public record Series(List<NutritionPoint> nutrition, List<MetricPoint> sleep,
                         List<MetricPoint> steps, CheckinSeries checkin) { }

    public record NutritionPoint(LocalDate date, BigDecimal energyKcal, List<Source> source) { }

    public record MetricPoint(LocalDate date, BigDecimal value, String unit, Source source) { }

    public record CheckinSeries(String category, List<MetricPoint> points) { }

    public record Observations(int daysInPeriod, int daysWithAnyData, Instant generatedAt) { }

    public record Source(UUID entryId, String type, LocalDate localDate) { }
}
