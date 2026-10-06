package com.health.analytics;

import java.math.BigDecimal;
import java.math.MathContext;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.Comparator;
import java.util.EnumMap;
import java.util.EnumSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.TreeMap;

/** Pure, database-independent analytics calculations. Inputs are never mutated. */
public final class AnalyticsFunctions {
    private AnalyticsFunctions() {}

    /**
     * Decimal average policy for this core API.
     *
     * <p>Recurring decimals cannot be represented exactly in {@link BigDecimal}.
     * We therefore keep the division policy explicit in the core API instead of a
     * hidden fixed scale (for example, 12 decimal places). Presentation layers can
     * still format the value for display as needed.</p>
     */
    public static final MathContext AVERAGE_MATH_CONTEXT = MathContext.DECIMAL128;

    public enum Status { DRAFT, CONFIRMED, CANCELLED, DELETED }
    public enum Metric { STEPS, SLEEP_DURATION_MIN, HEART_RATE }
    public enum Qualifier { INSTANT, RESTING }
    public enum CheckinCategory { SLEEP_QUALITY, DIGESTION_COMFORT, WELLBEING, MOOD }

    public record Entry(String id, String type, Status status, Instant occurredAt, Instant updatedAt,
                        Long revision, LocalDate localDate, LocalDate wakeDate, Metric metric,
                        BigDecimal value, Qualifier qualifier, CheckinCategory category, Integer score,
                        BigDecimal massGrams, Nutrients nutrients, Basis basis) {
        public Entry {
            Objects.requireNonNull(id, "id");
            Objects.requireNonNull(status, "status");
        }
        public static Entry meal(String id, Status status, Instant occurredAt, BigDecimal mass,
                                 Nutrients nutrients, Basis basis) {
            return new Entry(id, "meal", status, occurredAt, null, null, null, null, null, null,
                    null, null, null, mass, nutrients, basis);
        }
    }

    public record Nutrients(BigDecimal energyKcal, BigDecimal proteinG, BigDecimal fatG,
                            BigDecimal carbsG) {}
    public enum Basis { PER_100G, PER_SERVING, UNKNOWN }
    public record Period(LocalDate from, LocalDate to, ZoneId zone) {
        public Period {
            Objects.requireNonNull(from); Objects.requireNonNull(to); Objects.requireNonNull(zone);
            if (to.isBefore(from)) throw new IllegalArgumentException("to precedes from");
        }
        public boolean contains(LocalDate date) { return date != null && !date.isBefore(from) && !date.isAfter(to); }
    }
    public record NutritionResult(BigDecimal energyKcal, BigDecimal proteinG, BigDecimal fatG,
                                  BigDecimal carbsG, int countedMeals, boolean incomplete) {}
    public record AggregateResult(BigDecimal total, BigDecimal average, int daysWithData) {}
    public record HeartRateResult(BigDecimal value, Instant occurredAt, Qualifier qualifier, String entryId) {}
    public record RatingPoint(LocalDate date, Integer value, String entryId) {}
    public record DailyResult(Map<LocalDate, BigDecimal> values, AggregateResult aggregate) {}

    private static final Set<Status> CURRENT = EnumSet.of(Status.CONFIRMED);

    /** Keeps only confirmed entries; the returned list is a new immutable list. */
    public static List<Entry> current(List<Entry> entries) {
        return entries.stream().filter(Objects::nonNull).filter(e -> CURRENT.contains(e.status())).toList();
    }

    /** Converts an instant to the user's local calendar date, including today. */
    public static LocalDate localDate(Instant instant, ZoneId zone) {
        return Objects.requireNonNull(instant).atZone(Objects.requireNonNull(zone)).toLocalDate();
    }

    /**
     * A sleep record belongs only to its wake date.
     * Missing wakeDate means that the record cannot be assigned to a calendar day.
     */
    public static Optional<LocalDate> sleepDate(Entry entry, ZoneId zone) {
        Objects.requireNonNull(entry, "entry");
        Objects.requireNonNull(zone, "zone");

        if (entry.wakeDate() == null) {
            return Optional.empty();
        }

        return Optional.of(entry.wakeDate());
    }

    public static BigDecimal portionFactor(BigDecimal massGrams) {
        if (massGrams == null) {
            return null;
        }

        if (massGrams.signum() < 0) {
            throw new IllegalArgumentException("mass must be non-negative");
        }

        return normalize(
            massGrams.divide(BigDecimal.valueOf(100))
        );
    }

    private static BigDecimal normalize(BigDecimal value) {
        if (value == null) {
            return null;
        }

        BigDecimal normalized = value.stripTrailingZeros();
        return normalized.scale() < 0
            ? normalized.setScale(0)
            : normalized;
    }

    public static NutritionResult nutrition(List<Entry> entries, Period period) {
        BigDecimal energyKcal = BigDecimal.ZERO;
        BigDecimal proteinG = BigDecimal.ZERO;
        BigDecimal fatG = BigDecimal.ZERO;
        BigDecimal carbsG = BigDecimal.ZERO;

        boolean hasEnergyKcal = false;
        boolean hasProteinG = false;
        boolean hasFatG = false;
        boolean hasCarbsG = false;
        boolean incomplete = false;
        int countedMeals = 0;

        for (Entry entry : current(entries)) {
            if (!"meal".equals(entry.type())) {
                continue;
            }

            if (entry.occurredAt() == null) {
                incomplete = true;
                continue;
            }

            LocalDate mealDate = localDate(entry.occurredAt(), period.zone());

            if (!period.contains(mealDate)) {
                continue;
            }

            countedMeals++;

            Nutrients nutrients = entry.nutrients();
            if (nutrients == null) {
                incomplete = true;
                continue;
            }

            BigDecimal factor = BigDecimal.ONE;

            if (entry.basis() == Basis.PER_100G) {
                if (entry.massGrams() == null) {
                    incomplete = true;
                    continue;
                }

                factor = portionFactor(entry.massGrams());
            } else if (entry.basis() == null || entry.basis() == Basis.UNKNOWN) {
                incomplete = true;
                continue;
            }

            BigDecimal energy = nutrients.energyKcal();
            BigDecimal protein = nutrients.proteinG();
            BigDecimal fat = nutrients.fatG();
            BigDecimal carbs = nutrients.carbsG();

            if (energy == null) {
                incomplete = true;
            } else {
                energyKcal = energyKcal.add(energy.multiply(factor));
                hasEnergyKcal = true;
            }

            if (protein == null) {
                incomplete = true;
            } else {
                proteinG = proteinG.add(protein.multiply(factor));
                hasProteinG = true;
            }

            if (fat == null) {
                incomplete = true;
            } else {
                fatG = fatG.add(fat.multiply(factor));
                hasFatG = true;
            }

            if (carbs == null) {
                incomplete = true;
            } else {
                carbsG = carbsG.add(carbs.multiply(factor));
                hasCarbsG = true;
            }
        }

        return new NutritionResult(
            hasEnergyKcal ? normalize(energyKcal) : null,
            hasProteinG ? normalize(proteinG) : null,
            hasFatG ? normalize(fatG) : null,
            hasCarbsG ? normalize(carbsG) : null,
            countedMeals,
            incomplete
        );
    }

    private static Comparator<Entry> byOccurredUpdatedAndId() {
        return Comparator
            .comparing(Entry::occurredAt, Comparator.nullsFirst(Comparator.naturalOrder()))
            .thenComparing(Entry::updatedAt, Comparator.nullsFirst(Comparator.naturalOrder()))
            .thenComparing(Entry::id);
    }

    public static DailyResult dailyMetric(
        List<Entry> entries,
        Metric metric,
        Period period
    ) {
        Objects.requireNonNull(entries, "entries");
        Objects.requireNonNull(metric, "metric");
        Objects.requireNonNull(period, "period");

        if (metric == Metric.HEART_RATE) {
            throw new IllegalArgumentException(
                "Heart rate must be queried through latestHeartRate"
            );
        }

        Map<LocalDate, Entry> selected = new TreeMap<>();
        Comparator<Entry> order = byOccurredUpdatedAndId();

        for (Entry entry : current(entries)) {
            if (!"metrics".equals(entry.type())) {
                continue;
            }

            if (entry.metric() != metric || entry.value() == null) {
                continue;
            }

            Optional<LocalDate> date;

            if (metric == Metric.SLEEP_DURATION_MIN) {
                date = sleepDate(entry, period.zone());
            } else {
                if (entry.occurredAt() == null) {
                    continue;
                }

                date = Optional.ofNullable(entry.localDate());
            }

            if (date.isEmpty() || !period.contains(date.get())) {
                continue;
            }

            selected.merge(
                date.get(),
                entry,
                (first, second) ->
                    order.compare(first, second) <= 0
                        ? second
                        : first
            );
        }

        Map<LocalDate, BigDecimal> values = new TreeMap<>();

        selected.forEach((date, entry) ->
            values.put(date, normalize(entry.value()))
        );

        BigDecimal total = normalize(values.values()
            .stream()
            .reduce(BigDecimal.ZERO, BigDecimal::add));

        BigDecimal average = values.isEmpty()
            ? null
            : normalize(total.divide(
                BigDecimal.valueOf(values.size()),
                AVERAGE_MATH_CONTEXT
            ));

        return new DailyResult(
            Map.copyOf(values),
            new AggregateResult(
                values.isEmpty() ? null : total,
                average,
                values.size()
            )
        );
    }

    public static Optional<HeartRateResult> latestHeartRate(
        List<Entry> entries,
        Period period
    ) {
        Objects.requireNonNull(entries, "entries");
        Objects.requireNonNull(period, "period");

        return current(entries)
            .stream()
            .filter(entry -> "metrics".equals(entry.type()))
            .filter(entry -> entry.metric() == Metric.HEART_RATE)
            .filter(entry -> entry.value() != null)
            .filter(entry -> entry.occurredAt() != null)
            .filter(entry -> period.contains(
                localDate(entry.occurredAt(), period.zone())
            ))
            .max(byOccurredUpdatedAndId())
            .map(entry -> new HeartRateResult(
                entry.value(),
                entry.occurredAt(),
                entry.qualifier(),
                entry.id()
            ));
    }

    public static Map<CheckinCategory, List<RatingPoint>> checkins(
        List<Entry> entries,
        Period period
    ) {
        Objects.requireNonNull(entries, "entries");
        Objects.requireNonNull(period, "period");

        Map<CheckinCategory, Map<LocalDate, Entry>> selected =
            new EnumMap<>(CheckinCategory.class);

        Comparator<Entry> checkinOrder = byOccurredUpdatedAndId();

        for (Entry entry : current(entries)) {
            if (!"checkin".equals(entry.type())) {
                continue;
            }

            if (entry.category() == null
                || entry.score() == null
                || entry.occurredAt() == null) {
                continue;
            }

            LocalDate checkinDate = localDate(entry.occurredAt(), period.zone());

            if (!period.contains(checkinDate)) {
                continue;
            }

            selected
                .computeIfAbsent(
                    entry.category(),
                    ignored -> new TreeMap<>()
                )
                .merge(
                    checkinDate,
                    entry,
                    (first, second) ->
                        checkinOrder.compare(first, second) <= 0
                            ? second
                            : first
                );
        }

        Map<CheckinCategory, List<RatingPoint>> result =
            new EnumMap<>(CheckinCategory.class);

        selected.forEach((category, days) ->
            result.put(
                category,
                days.entrySet()
                    .stream()
                    .map(item -> new RatingPoint(
                        item.getKey(),
                        item.getValue().score(),
                        item.getValue().id()
                    ))
                    .toList()
            )
        );

        return Map.copyOf(result);
    }
}
