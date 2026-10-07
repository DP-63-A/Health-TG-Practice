package org.healthtg.analytics;

import com.health.analytics.AnalyticsFunctions;
import com.health.analytics.AnalyticsFunctions.Basis;
import com.health.analytics.AnalyticsFunctions.CheckinCategory;
import com.health.analytics.AnalyticsFunctions.DailyResult;
import com.health.analytics.AnalyticsFunctions.Metric;
import com.health.analytics.AnalyticsFunctions.Nutrients;
import com.health.analytics.AnalyticsFunctions.Period;
import com.health.analytics.AnalyticsFunctions.Qualifier;
import com.health.analytics.AnalyticsFunctions.RatingPoint;
import com.health.analytics.AnalyticsFunctions.Status;
import org.healthtg.core.entry.Entry;
import org.healthtg.core.entry.EntryCoreService;
import org.healthtg.core.entry.EntryStatus;
import org.healthtg.core.entry.EntryType;
import org.healthtg.core.entry.ListEntriesQuery;
import org.healthtg.core.entry.OwnerContext;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Clock;
import java.time.DateTimeException;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.EnumSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.TreeMap;
import java.util.UUID;

@Service
@ConditionalOnProperty(name = "health-tg.core.storage.enabled", matchIfMissing = true)
public class AnalyticsService {
    private static final ZoneId DEFAULT_TIMEZONE = ZoneId.of("Europe/Warsaw");
    private static final Set<EntryType> ANALYTICS_TYPES =
            EnumSet.of(EntryType.MEAL, EntryType.METRICS, EntryType.CHECKIN);
    /** Upper bound for a single daily steps/sleep value; keeps sums of up to 21 days inside long. */
    private static final BigDecimal MAX_WHOLE_METRIC = BigDecimal.valueOf(1_000_000_000L);
    private static final Comparator<Entry> DAILY_METRIC_ORDER = Comparator.comparing(Entry::occurredAt)
            .thenComparing(Entry::updatedAt).thenComparing(entry -> entry.id().toString());

    private final EntryCoreService entries;
    private final Clock clock;

    public AnalyticsService(EntryCoreService entries, Clock clock) {
        this.entries = entries;
        this.clock = clock;
    }

    public AnalyticsResponse calculate(OwnerContext owner, String periodKind, String timezone,
                                       String checkinCategory) {
        ZoneId zone;
        try {
            zone = timezone == null ? DEFAULT_TIMEZONE : ZoneId.of(timezone);
        } catch (DateTimeException invalidTimezone) {
            throw new IllegalArgumentException("Unsupported analytics timezone", invalidTimezone);
        }
        CheckinCategory selectedCategory = checkinCategory == null
                ? CheckinCategory.MOOD : checkinCategory(checkinCategory);
        LocalDate to = clock.instant().atZone(zone).toLocalDate();
        int periodDays = switch (periodKind) {
            case "today" -> 1;
            case "days_7" -> 7;
            case "days_21" -> 21;
            default -> throw new IllegalArgumentException("Unsupported analytics period");
        };
        LocalDate from = to.minusDays(periodDays - 1L);
        Period period = new Period(from, to, zone);

        // Steps and sleep belong to payload.local_date (the wake date for sleep), so the period filter
        // must not be applied to occurred_at in core. Load all confirmed entries of the owner and select
        // by the analytics date here.
        List<Entry> found = entries.listEntries(new ListEntriesQuery(owner, EntryStatus.CONFIRMED,
                        null, null, null, zone)).stream()
                .filter(entry -> ANALYTICS_TYPES.contains(entry.type()))
                .filter(entry -> inPeriod(entry, period))
                .sorted(Comparator.comparing(Entry::occurredAt).thenComparing(entry -> entry.id().toString()))
                .toList();
        List<AnalyticsFunctions.Entry> records = found.stream().map(AnalyticsService::calculationEntry).toList();

        AnalyticsFunctions.NutritionResult nutrition = AnalyticsFunctions.nutrition(records, period);
        DailyResult sleep = AnalyticsFunctions.dailyMetric(records, Metric.SLEEP_DURATION_MIN, period);
        DailyResult steps = AnalyticsFunctions.dailyMetric(records, Metric.STEPS, period);
        Optional<AnalyticsFunctions.HeartRateResult> heartRate =
                AnalyticsFunctions.latestHeartRate(records, period);
        Map<CheckinCategory, List<RatingPoint>> checkins = AnalyticsFunctions.checkins(records, period);

        List<Source> sources = buildSources(found, period, checkins, heartRate);
        return response(periodKind, periodDays, period, selectedCategory, found, nutrition, sleep,
                steps, heartRate, checkins, sources, clock.instant());
    }

    private static boolean inPeriod(Entry entry, Period period) {
        LocalDate date = analyticsDate(entry, period.zone());
        return date != null && !date.isBefore(period.from()) && !date.isAfter(period.to());
    }

    private static LocalDate analyticsDate(Entry entry, ZoneId zone) {
        if (isMetric(entry, Metric.STEPS) || isMetric(entry, Metric.SLEEP_DURATION_MIN)) {
            return localDate(entry.payload().get("local_date"));
        }
        return entry.occurredAt().atZone(zone).toLocalDate();
    }

    private static AnalyticsResponse response(String periodKind, int periodDays, Period period,
                                              CheckinCategory selectedCategory, List<Entry> found,
                                              AnalyticsFunctions.NutritionResult nutrition, DailyResult sleep,
                                              DailyResult steps,
                                              Optional<AnalyticsFunctions.HeartRateResult> heartRate,
                                              Map<CheckinCategory, List<RatingPoint>> checkins,
                                              List<Source> sources, Instant generatedAt) {
        int mealsWithEnergy = (int) found.stream()
                .filter(entry -> entry.type() == EntryType.MEAL)
                .filter(entry -> AnalyticsFunctions.nutrition(List.of(calculationEntry(entry)), period)
                        .energyKcal() != null)
                .count();
        NutritionCard nutritionCard = new NutritionCard(nutrition.energyKcal(), nutrition.proteinG(),
                nutrition.fatG(), nutrition.carbsG(), nutrition.incomplete(), mealsWithEnergy);
        Cards cards = new Cards(nutritionCard, new MealCountCard(nutrition.countedMeals()),
                sleepCard(sleep), stepsCard(steps),
                heartRate.map(result -> new HeartRateCard(result.value(), result.occurredAt(),
                        result.qualifier() == null ? null : result.qualifier().name().toLowerCase(Locale.ROOT),
                        UUID.fromString(result.entryId())))
                        .orElse(new HeartRateCard(null, null, null, null)),
                checkinCards(checkins));

        List<Source> allSources = sources.stream().distinct()
                .sorted(Comparator.comparing(Source::localDate).thenComparing(Source::entryId))
                .toList();
        Series series = new Series(nutritionSeries(found, period),
                metricSeries(found, sleep, Metric.SLEEP_DURATION_MIN, period, "min"),
                metricSeries(found, steps, Metric.STEPS, period, "count"),
                checkinSeries(selectedCategory, checkins));
        int daysWithData = (int) allSources.stream().map(Source::localDate).distinct().count();
        Observations observations = new Observations(periodDays, daysWithData, generatedAt);
        return new AnalyticsResponse(new PeriodResponse(periodKind, period.from(), period.to(),
                period.zone().getId()), cards, series, observations, allSources);
    }

    private static SleepCard sleepCard(DailyResult result) {
        return new SleepCard(wholeTotal(result.aggregate().total()), result.aggregate().average(),
                result.aggregate().daysWithData());
    }

    private static StepsCard stepsCard(DailyResult result) {
        return new StepsCard(wholeTotal(result.aggregate().total()), result.aggregate().average(),
                result.aggregate().daysWithData());
    }

    private static Long wholeTotal(BigDecimal total) {
        return total == null ? null : total.setScale(0, RoundingMode.HALF_UP).longValueExact();
    }

    private static CheckinCards checkinCards(Map<CheckinCategory, List<RatingPoint>> checkins) {
        return new CheckinCards(ratingCard(checkins, CheckinCategory.SLEEP_QUALITY),
                ratingCard(checkins, CheckinCategory.DIGESTION_COMFORT),
                ratingCard(checkins, CheckinCategory.WELLBEING),
                ratingCard(checkins, CheckinCategory.MOOD));
    }

    private static RatingCard ratingCard(Map<CheckinCategory, List<RatingPoint>> checkins,
                                         CheckinCategory category) {
        return checkins.getOrDefault(category, List.of()).stream().max(Comparator.comparing(RatingPoint::date))
                .map(point -> new RatingCard(point.value(), point.date(), UUID.fromString(point.entryId())))
                .orElse(new RatingCard(null, null, null));
    }

    private static List<NutritionPoint> nutritionSeries(List<Entry> found, Period period) {
        Map<LocalDate, List<Entry>> byDate = new TreeMap<>();
        for (Entry entry : found) {
            if (entry.type() == EntryType.MEAL) {
                LocalDate date = entry.occurredAt().atZone(period.zone()).toLocalDate();
                byDate.computeIfAbsent(date, ignored -> new ArrayList<>()).add(entry);
            }
        }
        return byDate.entrySet().stream().map(item -> {
            Period day = new Period(item.getKey(), item.getKey(), period.zone());
            List<AnalyticsFunctions.Entry> dayEntries = item.getValue().stream()
                    .map(AnalyticsService::calculationEntry).toList();
            BigDecimal energy = AnalyticsFunctions.nutrition(dayEntries, day).energyKcal();
            List<Source> daySources = item.getValue().stream()
                    .map(entry -> source(entry, item.getKey())).toList();
            return new NutritionPoint(item.getKey(), energy, daySources);
        }).toList();
    }

    private static List<MetricPoint> metricSeries(List<Entry> found, DailyResult result, Metric metric,
                                                   Period period, String unit) {
        return result.values().entrySet().stream().sorted(Map.Entry.comparingByKey()).map(item -> {
            Entry selected = found.stream()
                    .filter(entry -> isMetric(entry, metric))
                    .filter(entry -> item.getKey().equals(metricDate(entry, metric, period.zone())))
                    .filter(entry -> metricValue(entry) != null)
                    .max(DAILY_METRIC_ORDER).orElseThrow();
            return new MetricPoint(item.getKey(), item.getValue(), unit, source(selected, item.getKey()));
        }).toList();
    }

    private static CheckinSeries checkinSeries(CheckinCategory selectedCategory,
                                               Map<CheckinCategory, List<RatingPoint>> checkins) {
        List<RatingPointResponse> points = checkins.getOrDefault(selectedCategory, List.of()).stream()
                .map(point -> new RatingPointResponse(point.date(), point.value(), "score_1_5",
                        new Source(UUID.fromString(point.entryId()), "checkin", point.date())))
                .toList();
        return new CheckinSeries(selectedCategory.name().toLowerCase(Locale.ROOT), points);
    }

    private static List<Source> buildSources(List<Entry> found, Period period,
                                             Map<CheckinCategory, List<RatingPoint>> checkins,
                                             Optional<AnalyticsFunctions.HeartRateResult> heartRate) {
        List<Source> result = new ArrayList<>();
        for (Entry entry : found) {
            if (entry.type() == EntryType.MEAL) {
                result.add(source(entry, entry.occurredAt().atZone(period.zone()).toLocalDate()));
            } else if (entry.type() == EntryType.METRICS && isMetric(entry, Metric.HEART_RATE)) {
                if (heartRate.isPresent() && entry.id().toString().equals(heartRate.get().entryId())) {
                    result.add(source(entry, entry.occurredAt().atZone(period.zone()).toLocalDate()));
                }
            } else if (entry.type() == EntryType.METRICS
                    && (isMetric(entry, Metric.STEPS) || isMetric(entry, Metric.SLEEP_DURATION_MIN))) {
                Metric metric = isMetric(entry, Metric.STEPS) ? Metric.STEPS : Metric.SLEEP_DURATION_MIN;
                LocalDate date = metricDate(entry, metric, period.zone());
                if (metricValue(entry) != null && date != null
                        && metricSeriesSourceIsSelected(entry, found, metric, date, period.zone())) {
                    result.add(source(entry, date));
                }
            }
        }
        for (Map.Entry<CheckinCategory, List<RatingPoint>> category : checkins.entrySet()) {
            category.getValue().forEach(point -> result.add(new Source(UUID.fromString(point.entryId()),
                    "checkin", point.date())));
        }
        return result;
    }

    private static boolean metricSeriesSourceIsSelected(Entry candidate, List<Entry> found, Metric metric,
                                                        LocalDate date, ZoneId zone) {
        return found.stream().filter(entry -> isMetric(entry, metric))
                .filter(entry -> date.equals(metricDate(entry, metric, zone)))
                .filter(entry -> metricValue(entry) != null)
                .max(DAILY_METRIC_ORDER).map(entry -> entry.id().equals(candidate.id())).orElse(false);
    }

    private static Source source(Entry entry, LocalDate date) {
        return new Source(entry.id(), entry.type().code(), date);
    }

    private static LocalDate metricDate(Entry entry, Metric metric, ZoneId zone) {
        if (metric == Metric.HEART_RATE) return entry.occurredAt().atZone(zone).toLocalDate();
        return localDate(entry.payload().get("local_date"));
    }

    private static boolean isMetric(Entry entry, Metric metric) {
        if (entry.type() != EntryType.METRICS) return false;
        return switch (metric) {
            case STEPS -> "steps".equals(entry.payload().get("code"));
            case SLEEP_DURATION_MIN -> "sleep_duration_min".equals(entry.payload().get("code"));
            case HEART_RATE -> "heart_rate".equals(entry.payload().get("code"));
        };
    }

    /**
     * Metric value usable by analytics. Steps and sleep minutes are whole, non-negative numbers within a
     * bounded range (the canonical schema requires integer totals); legacy or malformed values are
     * ignored instead of failing the whole response.
     */
    private static BigDecimal metricValue(Entry entry) {
        BigDecimal value;
        try {
            value = decimal(entry.payload().get("value"));
        } catch (NumberFormatException invalid) {
            return null;
        }
        if (value == null || entry.type() != EntryType.METRICS) return value;
        if (!isMetric(entry, Metric.STEPS) && !isMetric(entry, Metric.SLEEP_DURATION_MIN)) return value;
        if (value.signum() < 0 || value.stripTrailingZeros().scale() > 0
                || value.compareTo(MAX_WHOLE_METRIC) > 0) {
            return null;
        }
        return value;
    }

    private static AnalyticsFunctions.Entry calculationEntry(Entry entry) {
        String type = entry.type().code();
        String code = text(entry.payload().get("code"));
        Metric metric = switch (code) {
            case "steps" -> Metric.STEPS;
            case "sleep_duration_min" -> Metric.SLEEP_DURATION_MIN;
            case "heart_rate" -> Metric.HEART_RATE;
            default -> null;
        };
        CheckinCategory category = checkinCategory(entry).orElse(null);
        Qualifier qualifier = switch (text(entry.payload().get("qualifier"))) {
            case "instant" -> Qualifier.INSTANT;
            case "resting" -> Qualifier.RESTING;
            default -> null;
        };
        return new AnalyticsFunctions.Entry(entry.id().toString(), type, Status.CONFIRMED, entry.occurredAt(),
                entry.updatedAt(), entry.revision(), localDate(entry.payload().get("local_date")),
                entry.type() == EntryType.METRICS && metric == Metric.SLEEP_DURATION_MIN
                        ? localDate(entry.payload().get("local_date")) : null,
                metric, metricValue(entry), qualifier, category,
                integer(entry.payload().get("score")), decimal(entry.payload().get("mass_g")),
                nutrients(entry.payload().get("nutrients")), basis(entry.payload().get("nutrients_basis")));
    }

    private static Optional<CheckinCategory> checkinCategory(Entry entry) {
        return entry.type() == EntryType.CHECKIN ? Optional.of(checkinCategory(text(entry.payload().get("category"))))
                : Optional.empty();
    }

    private static CheckinCategory checkinCategory(String value) {
        return switch (value) {
            case "sleep_quality" -> CheckinCategory.SLEEP_QUALITY;
            case "digestion_comfort" -> CheckinCategory.DIGESTION_COMFORT;
            case "wellbeing" -> CheckinCategory.WELLBEING;
            case "mood" -> CheckinCategory.MOOD;
            default -> throw new IllegalArgumentException("Unknown check-in category");
        };
    }

    private static Nutrients nutrients(Object value) {
        if (!(value instanceof Map<?, ?> map)) return null;
        return new Nutrients(decimal(map.get("energy_kcal")), decimal(map.get("protein_g")),
                decimal(map.get("fat_g")), decimal(map.get("carbs_g")));
    }

    private static Basis basis(Object value) {
        return switch (text(value)) {
            case "per_100g" -> Basis.PER_100G;
            case "per_serving" -> Basis.PER_SERVING;
            default -> Basis.UNKNOWN;
        };
    }

    private static LocalDate localDate(Object value) {
        if (value instanceof LocalDate date) return date;
        if (value instanceof String text) {
            try {
                return LocalDate.parse(text);
            } catch (RuntimeException ignored) {
                return null;
            }
        }
        return null;
    }

    private static BigDecimal decimal(Object value) {
        if (value instanceof BigDecimal decimal) return decimal;
        if (value instanceof Number number) return new BigDecimal(number.toString());
        return null;
    }

    private static Integer integer(Object value) {
        BigDecimal decimal = decimal(value);
        return decimal == null ? null : decimal.intValueExact();
    }

    private static String text(Object value) {
        return value instanceof String text ? text : "";
    }

    public record AnalyticsResponse(PeriodResponse period, Cards cards, Series series,
                                    Observations observations, List<Source> sources) {}
    public record PeriodResponse(String kind, LocalDate from, LocalDate to, String timezone) {}
    public record Cards(NutritionCard nutrition, MealCountCard mealCount, SleepCard sleep, StepsCard steps,
                        HeartRateCard heartRate, CheckinCards checkins) {}
    public record NutritionCard(BigDecimal energyKcal, BigDecimal proteinG, BigDecimal fatG, BigDecimal carbsG,
                                boolean incomplete, int mealsWithEnergy) {}
    public record MealCountCard(int count) {}
    /** Serialized as total_minutes / average_minutes / days_with_data (analytics.json#/$defs/aggregateMinutes). */
    public record SleepCard(Long totalMinutes, BigDecimal averageMinutes, int daysWithData) {}
    /** Serialized as total / average / days_with_data (analytics.json#/$defs/aggregateCount). */
    public record StepsCard(Long total, BigDecimal average, int daysWithData) {}
    public record HeartRateCard(BigDecimal valueBpm, Instant occurredAt, String qualifier, UUID entryId) {}
    public record CheckinCards(RatingCard sleepQuality, RatingCard digestionComfort, RatingCard wellbeing,
                               RatingCard mood) {}
    public record RatingCard(Integer score, LocalDate date, UUID entryId) {}
    public record Series(List<NutritionPoint> nutrition, List<MetricPoint> sleep, List<MetricPoint> steps,
                         CheckinSeries checkin) {}
    public record NutritionPoint(LocalDate date, BigDecimal energyKcal, List<Source> source) {}
    public record MetricPoint(LocalDate date, BigDecimal value, String unit, Source source) {}
    public record CheckinSeries(String category, List<RatingPointResponse> points) {}
    public record RatingPointResponse(LocalDate date, Integer value, String unit, Source source) {}
    public record Observations(int daysInPeriod, int daysWithAnyData, Instant generatedAt) {}
    public record Source(UUID entryId, String type, LocalDate localDate) {}
}
