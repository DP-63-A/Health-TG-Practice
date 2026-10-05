package org.healthtg.seed;

import org.healthtg.core.entry.EntryType;

import java.time.LocalDate;
import java.time.LocalTime;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.Set;

/**
 * Deterministic generator of fictional 21-day educational stories. The output depends only on the profile,
 * the random seed and the start date. The values are not a model of real health.
 */
public final class SyntheticProfileGenerator {
    public static final int DAYS = 21;
    public static final String LABEL = "Synthetic educational story: fictional data, not real health data";
    public static final Set<Integer> INCOMPLETE_EMPTY_DAYS = Set.of(4, 5, 13);

    private static final String[] DISHES = {
            "oatmeal with apple", "chicken with rice", "vegetable soup", "yogurt with berries",
            "pasta with tomato sauce", "omelette with greens", "fish with potatoes", "lentil stew",
            "cottage cheese with fruit", "buckwheat with mushrooms", "turkey sandwich", "rice with vegetables"
    };

    private SyntheticProfileGenerator() {
    }

    public static List<SeedRecord> generate(SeedProfile profile, long seed, LocalDate startDate) {
        Builder builder = new Builder(new Random(seed * 1_000_003L + profile.ordinal()), startDate);
        for (int day = 0; day < DAYS; day++) {
            switch (profile) {
                case REGULAR -> builder.regularDay(day);
                case IRREGULAR -> builder.irregularDay(day);
                case INCOMPLETE -> builder.incompleteDay(day);
            }
        }
        List<SeedRecord> sorted = new ArrayList<>(builder.records);
        sorted.sort(Comparator.comparing(SeedRecord::date).thenComparing(SeedRecord::time));
        List<SeedRecord> indexed = new ArrayList<>(sorted.size());
        for (int i = 0; i < sorted.size(); i++) indexed.add(sorted.get(i).withIndex(i));
        return List.copyOf(indexed);
    }

    private static final class Builder {
        private final Random random;
        private final LocalDate start;
        private final List<SeedRecord> records = new ArrayList<>();

        Builder(Random random, LocalDate start) {
            this.random = random;
            this.start = start;
        }

        void regularDay(int day) {
            LocalDate date = start.plusDays(day);
            meal(date, LocalTime.of(8, random.nextInt(20)), 15, 45, 8, 30, 30, 80, null, false);
            meal(date, LocalTime.of(13, random.nextInt(30)), 25, 50, 12, 35, 45, 95, day == 5 ? 420 : null,
                    day == 2);
            meal(date, LocalTime.of(19, random.nextInt(30)), 20, 45, 10, 35, 35, 90, null, false);
            metric(date, "sleep_duration_min", "min", 400 + random.nextInt(91), null, LocalTime.of(7, 30), false);
            metric(date, "heart_rate", "bpm", 54 + random.nextInt(11), "resting", LocalTime.of(7, 40), false);
            metric(date, "steps", "count", 6500 + random.nextInt(5001), null, LocalTime.of(21, 0), day == 9);
            metric(date, "heart_rate", "bpm", 68 + random.nextInt(25), "instant", LocalTime.of(20, 30), false);
            checkin(date, "sleep_quality", 3 + random.nextInt(3), LocalTime.of(8, 10), false);
            checkin(date, "wellbeing", 3 + random.nextInt(3), LocalTime.of(8, 15), false);
            checkin(date, "digestion_comfort", 3 + random.nextInt(3), LocalTime.of(20, 0), false);
            checkin(date, "mood", 3 + random.nextInt(3), LocalTime.of(21, 30), day == 14);
        }

        void irregularDay(int day) {
            LocalDate date = start.plusDays(day);
            if (day != 3 && day != 10) {
                int meals = 1 + random.nextInt(4);
                for (int i = 0; i < meals; i++) {
                    LocalTime time = LocalTime.of(7 + random.nextInt(15), random.nextInt(60));
                    boolean corrected = day == 6 && i == 0;
                    meal(date, time, 5, 55, 3, 45, 10, 120, corrected ? 275 : null,
                            day == 2 && i == 0);
                }
            }
            if (day == 7) {
                metric(date, "steps", "count", 0, null, LocalTime.of(22, 0), false);
            } else {
                if (day == 12 || random.nextInt(10) < 7) {
                    int steps = 1500 + random.nextInt(14501);
                    metricWithCorrection(date, "steps", "count", steps, LocalTime.of(14 + random.nextInt(9),
                            random.nextInt(60)), day == 12 ? steps + 1200 : null);
                }
                if (random.nextInt(10) < 6) {
                    metric(date, "sleep_duration_min", "min", 240 + random.nextInt(361), null,
                            LocalTime.of(5 + random.nextInt(7), random.nextInt(60)), false);
                }
                if (random.nextInt(10) < 4) {
                    metric(date, "heart_rate", "bpm", 50 + random.nextInt(55), "instant",
                            LocalTime.of(6 + random.nextInt(16), random.nextInt(60)), false);
                }
            }
            for (String category : List.of("sleep_quality", "digestion_comfort", "wellbeing", "mood")) {
                if (random.nextInt(10) < 4) {
                    checkin(date, category, 1 + random.nextInt(5),
                            LocalTime.of(6 + random.nextInt(17), random.nextInt(60)), false);
                }
            }
            if (day == 9) cancelledDraft(date, LocalTime.of(20, 0), mealDraftPayload());
        }

        void incompleteDay(int day) {
            if (INCOMPLETE_EMPTY_DAYS.contains(day)) return;
            LocalDate date = start.plusDays(day);
            boolean any = false;
            if (day == 2 || random.nextInt(2) == 0) {
                incompleteMeal(date, LocalTime.of(9 + random.nextInt(10), random.nextInt(60)), day == 2, day == 2);
                any = true;
            }
            if (random.nextInt(2) == 0) {
                metric(date, "steps", "count", 2000 + random.nextInt(8001),
                        null, LocalTime.of(19 + random.nextInt(4), random.nextInt(60)), false);
                any = true;
            }
            if (random.nextInt(10) < 3) {
                metric(date, "sleep_duration_min", "min", 300 + random.nextInt(181), null,
                        LocalTime.of(6 + random.nextInt(4), random.nextInt(60)), false);
                any = true;
            }
            if (random.nextInt(10) < 3) {
                String category = List.of("sleep_quality", "digestion_comfort", "wellbeing", "mood")
                        .get(random.nextInt(4));
                checkin(date, category, 1 + random.nextInt(5), LocalTime.of(8 + random.nextInt(14),
                        random.nextInt(60)), false);
                any = true;
            }
            if (!any) incompleteMeal(date, LocalTime.of(12, random.nextInt(60)), false, false);
            if (day == 16) {
                Map<String, Object> payload = new LinkedHashMap<>();
                payload.put("code", "steps");
                payload.put("value", 3000);
                cancelledDraft(date, LocalTime.of(18, 0), EntryType.METRICS, payload,
                        Map.of("code", "extracted", "value", "extracted"));
            }
        }

        private void meal(LocalDate date, LocalTime time, int pMin, int pMax, int fMin, int fMax, int cMin,
                          int cMax, Integer correctedMass, boolean redelivered) {
            int protein = pMin + random.nextInt(pMax - pMin + 1);
            int fat = fMin + random.nextInt(fMax - fMin + 1);
            int carbs = cMin + random.nextInt(cMax - cMin + 1);
            Map<String, Object> nutrients = new LinkedHashMap<>();
            nutrients.put("energy_kcal", 4 * protein + 9 * fat + 4 * carbs);
            nutrients.put("protein_g", protein);
            nutrients.put("fat_g", fat);
            nutrients.put("carbs_g", carbs);
            Map<String, Object> payload = new LinkedHashMap<>();
            payload.put("description", "Synthetic meal: " + DISHES[random.nextInt(DISHES.length)]);
            payload.put("mass_g", 150 + random.nextInt(301));
            payload.put("nutrients", nutrients);
            payload.put("nutrients_basis", "per_serving");
            Map<String, String> origins = mealOrigins();
            Map<String, Object> correction = correctedMass == null ? null : Map.of("mass_g", correctedMass);
            Map<String, String> correctionOrigins = correctedMass == null ? null : Map.of("mass_g", "reported");
            records.add(new SeedRecord(0, EntryType.MEAL, date, time, payload, origins, correction,
                    correctionOrigins, redelivered, false));
        }

        private void incompleteMeal(LocalDate date, LocalTime time, boolean corrected, boolean redelivered) {
            Map<String, Object> nutrients = new LinkedHashMap<>();
            nutrients.put("energy_kcal", 200 + random.nextInt(500));
            nutrients.put("protein_g", null);
            nutrients.put("fat_g", null);
            nutrients.put("carbs_g", null);
            Map<String, Object> payload = new LinkedHashMap<>();
            payload.put("description", "Synthetic meal: " + DISHES[random.nextInt(DISHES.length)]);
            payload.put("mass_g", null);
            payload.put("nutrients", nutrients);
            payload.put("nutrients_basis", "per_serving");
            records.add(new SeedRecord(0, EntryType.MEAL, date, time, payload, mealOrigins(),
                    corrected ? Map.of("mass_g", 220) : null,
                    corrected ? Map.of("mass_g", "reported") : null, redelivered, false));
        }

        private Map<String, Object> mealDraftPayload() {
            Map<String, Object> nutrients = new LinkedHashMap<>();
            nutrients.put("energy_kcal", 450);
            nutrients.put("protein_g", null);
            nutrients.put("fat_g", null);
            nutrients.put("carbs_g", null);
            Map<String, Object> payload = new LinkedHashMap<>();
            payload.put("description", "Synthetic meal: draft later cancelled");
            payload.put("mass_g", null);
            payload.put("nutrients", nutrients);
            payload.put("nutrients_basis", "per_serving");
            return payload;
        }

        private void cancelledDraft(LocalDate date, LocalTime time, Map<String, Object> mealPayload) {
            cancelledDraft(date, time, EntryType.MEAL, mealPayload, mealOrigins());
        }

        private void cancelledDraft(LocalDate date, LocalTime time, EntryType type, Map<String, Object> payload,
                                    Map<String, String> origins) {
            records.add(new SeedRecord(0, type, date, time, payload, origins, null, null, false, true));
        }

        private static Map<String, String> mealOrigins() {
            Map<String, String> origins = new LinkedHashMap<>();
            origins.put("description", "reported");
            origins.put("mass_g", "estimated");
            origins.put("nutrients", "estimated");
            origins.put("nutrients_basis", "estimated");
            return origins;
        }

        private void metric(LocalDate date, String code, String unit, int value, String qualifier, LocalTime time,
                            boolean redelivered) {
            metricRecord(date, code, unit, value, qualifier, time, redelivered, null);
        }

        private void metricWithCorrection(LocalDate date, String code, String unit, int value, LocalTime time,
                                          Integer correctedValue) {
            metricRecord(date, code, unit, value, null, time, false, correctedValue);
        }

        private void metricRecord(LocalDate date, String code, String unit, int value, String qualifier,
                                  LocalTime time, boolean redelivered, Integer correctedValue) {
            Map<String, Object> payload = new LinkedHashMap<>();
            payload.put("code", code);
            payload.put("value", value);
            payload.put("unit", unit);
            payload.put("local_date", date.toString());
            payload.put("local_time", time.toString());
            payload.put("qualifier", qualifier);
            Map<String, String> origins = new LinkedHashMap<>();
            for (String field : List.of("code", "value", "unit", "local_date", "local_time")) {
                origins.put(field, "reported");
            }
            records.add(new SeedRecord(0, EntryType.METRICS, date, time, payload, origins,
                    correctedValue == null ? null : Map.of("value", correctedValue),
                    correctedValue == null ? null : Map.of("value", "reported"), redelivered, false));
        }

        private void checkin(LocalDate date, String category, int score, LocalTime time, boolean redelivered) {
            Map<String, Object> payload = new LinkedHashMap<>();
            payload.put("category", category);
            payload.put("score", score);
            records.add(new SeedRecord(0, EntryType.CHECKIN, date, time, payload,
                    Map.of("category", "reported", "score", "reported"), null, null, redelivered, false));
        }
    }
}
