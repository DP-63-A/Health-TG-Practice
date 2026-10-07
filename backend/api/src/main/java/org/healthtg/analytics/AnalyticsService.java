package org.healthtg.analytics;

import com.health.analytics.AnalyticsFunctions;
import org.healthtg.core.entry.Entry;
import org.healthtg.core.entry.EntryCoreService;
import org.healthtg.core.entry.EntryType;
import org.healthtg.core.entry.ListConfirmedEntriesQuery;
import org.healthtg.core.entry.OwnerContext;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.time.Clock;
import java.time.LocalDate;
import java.time.LocalTime;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

@Service
@ConditionalOnProperty(name = "health-tg.core.storage.enabled", matchIfMissing = true)
public class AnalyticsService {
    private static final Set<EntryType> TYPES = Set.of(EntryType.MEAL, EntryType.METRICS, EntryType.CHECKIN);
    private static final Comparator<Entry> ENTRY_ORDER = Comparator.comparing(Entry::occurredAt)
            .thenComparing(Entry::updatedAt).thenComparing(Entry::id);

    private final EntryCoreService entries;
    private final Clock clock;

    public AnalyticsService(EntryCoreService entries, Clock clock) {
        this.entries = entries;
        this.clock = clock;
    }

    public AnalyticsResponse get(OwnerContext owner, String periodName, String timezoneName,
                                 String categoryName) {
        PeriodKind kind = PeriodKind.from(periodName);
        ZoneId zone = zone(timezoneName);
        AnalyticsFunctions.CheckinCategory category = checkinCategory(categoryName);
        LocalDate to = LocalDate.now(clock.withZone(zone));
        LocalDate from = to.minusDays(kind.days - 1L);
        var period = new AnalyticsFunctions.Period(from, to, zone);

        List<Entry> stored = entries.listConfirmedEntries(
                new ListConfirmedEntriesQuery(owner, from, to, zone, TYPES));
        List<AnalyticsFunctions.Entry> input = stored.stream().map(this::adapt).toList();
        AnalyticsFunctions.NutritionResult nutrition = AnalyticsFunctions.nutrition(input, period);
        AnalyticsFunctions.DailyResult sleep = AnalyticsFunctions.dailyMetric(
                input, AnalyticsFunctions.Metric.SLEEP_DURATION_MIN, period);
        AnalyticsFunctions.DailyResult steps = AnalyticsFunctions.dailyMetric(
                input, AnalyticsFunctions.Metric.STEPS, period);
        var heart = AnalyticsFunctions.latestHeartRate(input, period);
        var ratings = AnalyticsFunctions.checkins(input, period);

        Map<LocalDate, List<Entry>> meals = mealsByDate(stored, zone);
        Map<LocalDate, Entry> selectedSleep = selectedMetrics(stored, "sleep_duration_min");
        Map<LocalDate, Entry> selectedSteps = selectedMetrics(stored, "steps");
        Map<String, AnalyticsResponse.Source> sources = new LinkedHashMap<>();

        List<AnalyticsResponse.NutritionPoint> nutritionSeries = meals.entrySet().stream().map(item -> {
            List<AnalyticsResponse.Source> pointSources = item.getValue().stream()
                    .map(entry -> source(entry, item.getKey())).toList();
            pointSources.forEach(source -> sources.put(source.entryId().toString(), source));
            BigDecimal energy = AnalyticsFunctions.nutrition(
                    item.getValue().stream().map(this::adapt).toList(),
                    new AnalyticsFunctions.Period(item.getKey(), item.getKey(), zone)).energyKcal();
            return new AnalyticsResponse.NutritionPoint(item.getKey(), energy, pointSources);
        }).toList();
        List<AnalyticsResponse.MetricPoint> sleepSeries = metricSeries(
                sleep, selectedSleep, "min", sources);
        List<AnalyticsResponse.MetricPoint> stepsSeries = metricSeries(
                steps, selectedSteps, "count", sources);

        Map<String, AnalyticsResponse.Rating> ratingCards = new LinkedHashMap<>();
        for (AnalyticsFunctions.CheckinCategory value : AnalyticsFunctions.CheckinCategory.values()) {
            List<AnalyticsFunctions.RatingPoint> points = ratings.getOrDefault(value, List.of());
            AnalyticsFunctions.RatingPoint latest = points.isEmpty() ? null : points.getLast();
            ratingCards.put(code(value), latest == null
                    ? new AnalyticsResponse.Rating(null, null, null)
                    : new AnalyticsResponse.Rating(latest.value(), latest.date(), uuid(latest.entryId())));
            points.forEach(point -> addCheckinSource(stored, point, sources));
        }
        List<AnalyticsResponse.MetricPoint> checkinPoints = ratings.getOrDefault(category, List.of()).stream()
                .map(point -> new AnalyticsResponse.MetricPoint(point.date(), BigDecimal.valueOf(point.value()),
                        "score_1_5", sources.get(point.entryId())))
                .toList();

        AnalyticsResponse.HeartRate heartCard = heart.map(value -> {
            Entry entry = stored.stream().filter(item -> item.id().toString().equals(value.entryId()))
                    .findFirst().orElseThrow();
            AnalyticsResponse.Source source = source(entry, value.localDate());
            sources.put(source.entryId().toString(), source);
            return new AnalyticsResponse.HeartRate(value.value(), value.occurredAt(), value.localDate(),
                    value.localTime(), code(value.qualifier()), uuid(value.entryId()));
        }).orElseGet(() -> new AnalyticsResponse.HeartRate(null, null, null, null, null, null));

        int mealsWithEnergy = (int) meals.values().stream().flatMap(List::stream)
                .filter(entry -> AnalyticsFunctions.nutrition(List.of(adapt(entry)), period).energyKcal() != null)
                .count();
        Set<LocalDate> daysWithData = new LinkedHashSet<>();
        sources.values().forEach(source -> daysWithData.add(source.localDate()));

        return new AnalyticsResponse(
                new AnalyticsResponse.Period(kind.code, from, to, zone.getId()),
                new AnalyticsResponse.Cards(
                        new AnalyticsResponse.Nutrition(nutrition.energyKcal(), nutrition.proteinG(),
                                nutrition.fatG(), nutrition.carbsG(), nutrition.incomplete(), mealsWithEnergy),
                        new AnalyticsResponse.MealCount(nutrition.countedMeals()),
                        minutes(sleep.aggregate()), counts(steps.aggregate()), heartCard, Map.copyOf(ratingCards)),
                new AnalyticsResponse.Series(nutritionSeries, sleepSeries, stepsSeries,
                        new AnalyticsResponse.CheckinSeries(code(category), checkinPoints)),
                new AnalyticsResponse.Observations(kind.days, daysWithData.size(), clock.instant()),
                List.copyOf(sources.values()));
    }

    private AnalyticsFunctions.Entry adapt(Entry entry) {
        Map<String, Object> payload = entry.payload();
        String code = text(payload, "code");
        AnalyticsFunctions.Metric metric = metric(code);
        LocalDate localDate = date(payload, "local_date");
        return new AnalyticsFunctions.Entry(entry.id().toString(), entry.type().code(),
                AnalyticsFunctions.Status.CONFIRMED, entry.occurredAt(), entry.updatedAt(), entry.revision(),
                localDate, metric == AnalyticsFunctions.Metric.SLEEP_DURATION_MIN ? localDate : null,
                metric, decimal(payload.get("value")), qualifier(text(payload, "qualifier")),
                checkinCategoryOrNull(text(payload, "category")), integer(payload.get("score")),
                decimal(payload.get("mass_g")), nutrients(payload.get("nutrients")),
                basis(text(payload, "nutrients_basis")), time(payload, "local_time"));
    }

    private static Map<LocalDate, List<Entry>> mealsByDate(List<Entry> stored, ZoneId zone) {
        Map<LocalDate, List<Entry>> result = new java.util.TreeMap<>();
        stored.stream().filter(entry -> entry.type() == EntryType.MEAL).forEach(entry -> result
                .computeIfAbsent(entry.occurredAt().atZone(zone).toLocalDate(), ignored -> new ArrayList<>())
                .add(entry));
        return result;
    }

    private static Map<LocalDate, Entry> selectedMetrics(List<Entry> stored, String metricCode) {
        Map<LocalDate, Entry> selected = new java.util.TreeMap<>();
        stored.stream().filter(entry -> entry.type() == EntryType.METRICS)
                .filter(entry -> metricCode.equals(entry.payload().get("code")))
                .filter(entry -> date(entry.payload(), "local_date") != null)
                .forEach(entry -> selected.merge(date(entry.payload(), "local_date"), entry,
                        (first, second) -> ENTRY_ORDER.compare(first, second) <= 0 ? second : first));
        return selected;
    }

    private static List<AnalyticsResponse.MetricPoint> metricSeries(
            AnalyticsFunctions.DailyResult result, Map<LocalDate, Entry> selected, String unit,
            Map<String, AnalyticsResponse.Source> sources) {
        return result.values().entrySet().stream().sorted(Map.Entry.comparingByKey()).map(item -> {
            AnalyticsResponse.Source source = source(selected.get(item.getKey()), item.getKey());
            sources.put(source.entryId().toString(), source);
            return new AnalyticsResponse.MetricPoint(item.getKey(), item.getValue(), unit, source);
        }).toList();
    }

    private static void addCheckinSource(List<Entry> stored, AnalyticsFunctions.RatingPoint point,
                                         Map<String, AnalyticsResponse.Source> sources) {
        stored.stream().filter(entry -> entry.id().toString().equals(point.entryId())).findFirst()
                .ifPresent(entry -> sources.put(point.entryId(), source(entry, point.date())));
    }

    private static AnalyticsResponse.Source source(Entry entry, LocalDate date) {
        return new AnalyticsResponse.Source(entry.id(), entry.type().code(), date);
    }

    private static AnalyticsResponse.AggregateMinutes minutes(AnalyticsFunctions.AggregateResult value) {
        return new AnalyticsResponse.AggregateMinutes(value.total() == null ? null : value.total().intValueExact(),
                value.average(), value.daysWithData());
    }

    private static AnalyticsResponse.AggregateCount counts(AnalyticsFunctions.AggregateResult value) {
        return new AnalyticsResponse.AggregateCount(value.total() == null ? null : value.total().longValueExact(),
                value.average(), value.daysWithData());
    }

    private static AnalyticsFunctions.Nutrients nutrients(Object raw) {
        if (!(raw instanceof Map<?, ?> values)) return null;
        return new AnalyticsFunctions.Nutrients(decimal(values.get("energy_kcal")),
                decimal(values.get("protein_g")), decimal(values.get("fat_g")), decimal(values.get("carbs_g")));
    }

    private static BigDecimal decimal(Object value) {
        if (value == null) return null;
        if (value instanceof BigDecimal decimal) return decimal;
        if (value instanceof Number number) return new BigDecimal(number.toString());
        return new BigDecimal(value.toString());
    }

    private static Integer integer(Object value) {
        return value instanceof Number number ? number.intValue() : null;
    }

    private static String text(Map<String, Object> payload, String field) {
        return payload.get(field) instanceof String value ? value : null;
    }

    private static LocalDate date(Map<String, Object> payload, String field) {
        String value = text(payload, field);
        return value == null ? null : LocalDate.parse(value);
    }

    private static LocalTime time(Map<String, Object> payload, String field) {
        String value = text(payload, field);
        return value == null ? null : LocalTime.parse(value);
    }

    private static AnalyticsFunctions.Metric metric(String code) {
        if (code == null) return null;
        return switch (code) {
            case "steps" -> AnalyticsFunctions.Metric.STEPS;
            case "sleep_duration_min" -> AnalyticsFunctions.Metric.SLEEP_DURATION_MIN;
            case "heart_rate" -> AnalyticsFunctions.Metric.HEART_RATE;
            default -> null;
        };
    }

    private static AnalyticsFunctions.Qualifier qualifier(String code) {
        if (code == null) return null;
        return AnalyticsFunctions.Qualifier.valueOf(code.toUpperCase(java.util.Locale.ROOT));
    }

    private static AnalyticsFunctions.Basis basis(String code) {
        if (code == null) return null;
        return switch (code) {
            case "per_100g" -> AnalyticsFunctions.Basis.PER_100G;
            case "per_serving" -> AnalyticsFunctions.Basis.PER_SERVING;
            default -> AnalyticsFunctions.Basis.UNKNOWN;
        };
    }

    private static ZoneId zone(String name) {
        try {
            return ZoneId.of(name);
        } catch (java.time.DateTimeException invalid) {
            throw new IllegalArgumentException("Unsupported timezone", invalid);
        }
    }

    private static AnalyticsFunctions.CheckinCategory checkinCategory(String code) {
        return AnalyticsFunctions.CheckinCategory.valueOf(code.toUpperCase(java.util.Locale.ROOT));
    }

    private static AnalyticsFunctions.CheckinCategory checkinCategoryOrNull(String code) {
        return code == null ? null : checkinCategory(code);
    }

    private static String code(Enum<?> value) {
        return value == null ? null : value.name().toLowerCase(java.util.Locale.ROOT);
    }

    private static java.util.UUID uuid(String value) {
        return java.util.UUID.fromString(value);
    }

    private enum PeriodKind {
        TODAY("today", 1), DAYS_7("days_7", 7), DAYS_21("days_21", 21);

        private final String code;
        private final int days;

        PeriodKind(String code, int days) {
            this.code = code;
            this.days = days;
        }

        private static PeriodKind from(String code) {
            for (PeriodKind value : values()) if (value.code.equals(code)) return value;
            throw new IllegalArgumentException("Unsupported analytics period");
        }
    }
}
