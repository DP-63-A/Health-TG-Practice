package com.health.analytics;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.EnumMap;
import java.util.EnumSet;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.TreeMap;

/** Pure, database-independent analytics calculations. Inputs are never mutated. */
public final class AnalyticsFunctions {
    private AnalyticsFunctions() {}

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
        public static Entry meal(String id, Status status, LocalDate date, BigDecimal mass,
                                 Nutrients nutrients, Basis basis) {
            return new Entry(id, "meal", status, null, null, null, date, null, null, null,
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

    /** A sleep record belongs to its wake date; missing wakeDate is deliberately excluded. */
    public static Optional<LocalDate> sleepDate(Entry entry, ZoneId zone) {
        if (entry.wakeDate() != null) return Optional.of(entry.wakeDate());
        return entry.occurredAt() == null ? Optional.empty() : Optional.of(localDate(entry.occurredAt(), zone));
    }

    public static BigDecimal portion(Nutrients per100g, BigDecimal massGrams) {
        if (per100g == null || massGrams == null) return null;
        if (massGrams.signum() < 0) throw new IllegalArgumentException("mass must be non-negative");
        return massGrams.divide(BigDecimal.valueOf(100), 12, RoundingMode.HALF_UP);
    }

    public static NutritionResult nutrition(List<Entry> entries, Period period) {
        BigDecimal e = BigDecimal.ZERO, p = BigDecimal.ZERO, f = BigDecimal.ZERO, c = BigDecimal.ZERO;
        int count = 0; boolean incomplete = false;
        for (Entry entry : current(entries)) {
            if (!"meal".equals(entry.type()) || !period.contains(entry.localDate())) continue;
            count++;
            Nutrients n = entry.nutrients();
            if (n == null) { incomplete = true; continue; }
            BigDecimal factor = BigDecimal.ONE;
            if (entry.basis() == Basis.PER_100G) {
                if (entry.massGrams() == null) { incomplete = true; continue; }
                factor = portion(n, entry.massGrams());
            } else if (entry.basis() == Basis.UNKNOWN || entry.basis() == null) {
                incomplete = true; continue;
            }
            BigDecimal[] values = {n.energyKcal(), n.proteinG(), n.fatG(), n.carbsG()};
            if (java.util.Arrays.stream(values).anyMatch(Objects::isNull)) incomplete = true;
            if (values[0] != null) e = e.add(values[0].multiply(factor));
            if (values[1] != null) p = p.add(values[1].multiply(factor));
            if (values[2] != null) f = f.add(values[2].multiply(factor));
            if (values[3] != null) c = c.add(values[3].multiply(factor));
        }
        return new NutritionResult(count == 0 ? null : e, count == 0 ? null : p, count == 0 ? null : f,
                count == 0 ? null : c, count, incomplete);
    }

    /** Selects one latest daily total by occurredAt, updatedAt, then id; never sums duplicates. */
    public static DailyResult dailyMetric(List<Entry> entries, Metric metric, Period period) {
        Map<LocalDate, Entry> selected = new TreeMap<>();
        Comparator<Entry> order = Comparator.comparing(Entry::occurredAt, Comparator.nullsFirst(Comparator.naturalOrder()))
                .thenComparing(Entry::updatedAt, Comparator.nullsFirst(Comparator.naturalOrder()))
                .thenComparing(Entry::id);
        for (Entry e : current(entries)) {
            if (e.metric() != metric || e.value() == null || e.occurredAt() == null) continue;
            LocalDate date = localDate(e.occurredAt(), period.zone());
            if (metric == Metric.SLEEP_DURATION_MIN && e.wakeDate() != null) date = e.wakeDate();
            if (!period.contains(date)) continue;
            selected.merge(date, e, (a, b) -> order.compare(a, b) <= 0 ? b : a);
        }
        Map<LocalDate, BigDecimal> values = new TreeMap<>();
        selected.forEach((date, e) -> values.put(date, e.value()));
        BigDecimal total = values.values().stream().reduce(BigDecimal.ZERO, BigDecimal::add);
        BigDecimal average = values.isEmpty() ? null : total.divide(BigDecimal.valueOf(values.size()), 12, RoundingMode.HALF_UP);
        return new DailyResult(Map.copyOf(values), new AggregateResult(values.isEmpty() ? null : total, average, values.size()));
    }

    /** Returns the latest confirmed heart-rate sample; no daily average is invented. */
    public static Optional<HeartRateResult> latestHeartRate(List<Entry> entries, Period period) {
        return current(entries).stream().filter(e -> e.metric() == Metric.HEART_RATE && e.value() != null && e.occurredAt() != null)
                .filter(e -> period.contains(localDate(e.occurredAt(), period.zone())))
                .max(Comparator.comparing(Entry::occurredAt).thenComparing(Entry::updatedAt, Comparator.nullsFirst(Comparator.naturalOrder())))
                .map(e -> new HeartRateResult(e.value(), e.occurredAt(), e.qualifier(), e.id()));
    }

    /** One latest point per check-in category and date; absent values remain absent. */
    public static Map<CheckinCategory, List<RatingPoint>> checkins(List<Entry> entries, Period period) {
        Map<CheckinCategory, Map<LocalDate, Entry>> selected = new EnumMap<>(CheckinCategory.class);
        for (Entry e : current(entries)) {
            if (e.category() == null || e.score() == null || e.localDate() == null || !period.contains(e.localDate())) continue;
            selected.computeIfAbsent(e.category(), ignored -> new TreeMap<>()).merge(e.localDate(), e,
                    (a, b) -> Comparator.comparing(Entry::updatedAt, Comparator.nullsFirst(Comparator.naturalOrder()))
                            .thenComparing(Entry::id).compare(a, b) <= 0 ? b : a);
        }
        Map<CheckinCategory, List<RatingPoint>> result = new EnumMap<>(CheckinCategory.class);
        selected.forEach((category, days) -> result.put(category, days.entrySet().stream()
                .map(x -> new RatingPoint(x.getKey(), x.getValue().score(), x.getValue().id())).toList()));
        return Map.copyOf(result);
    }
}
