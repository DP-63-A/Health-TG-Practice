package org.healthtg.seed;

import org.healthtg.core.entry.EntryType;

import java.time.LocalDate;
import java.time.LocalTime;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.SplittableRandom;

public final class SyntheticDatasetGenerator {
    public static final int DAYS = 21;
    public static final long DEFAULT_SEED = 20260505L;
    public static final LocalDate DEFAULT_START_DATE = LocalDate.of(2026, 9, 1);

    public List<SyntheticProfileData> generate(long seed, LocalDate startDate, ZoneId timezone) {
        List<SyntheticProfileData> profiles = new ArrayList<>();
        for (SyntheticProfile profile : SyntheticProfile.values()) {
            profiles.add(generateProfile(profile, seed, startDate, timezone));
        }
        return List.copyOf(profiles);
    }

    public SyntheticProfileData generateProfile(SyntheticProfile profile, long seed, LocalDate startDate,
                                                ZoneId timezone) {
        SplittableRandom random = new SplittableRandom(seed + 31L * profile.ordinal());
        List<LocalDate> days = new ArrayList<>(DAYS);
        List<SyntheticEntry> entries = new ArrayList<>();
        for (int day = 0; day < DAYS; day++) {
            LocalDate date = startDate.plusDays(day);
            days.add(date);
            if (profile == SyntheticProfile.INCOMPLETE && day == 10) continue;
            addDay(profile, day, date, timezone, random, entries);
        }
        return new SyntheticProfileData(profile, days, entries);
    }

    private static void addDay(SyntheticProfile profile, int day, LocalDate date, ZoneId timezone,
                               SplittableRandom random, List<SyntheticEntry> entries) {
        boolean regular = profile == SyntheticProfile.REGULAR;
        boolean incomplete = profile == SyntheticProfile.INCOMPLETE;
        boolean breakfast = regular || (profile == SyntheticProfile.IRREGULAR
                ? day % 4 != 1 : day % 3 != 0);
        boolean dinner = regular || (profile == SyntheticProfile.IRREGULAR
                ? day % 5 != 2 : day % 4 == 0);
        if (breakfast) {
            Map<String, Object> meal = meal("Fictional morning meal", random, day);
            Map<String, Object> initial = profile == SyntheticProfile.REGULAR && day == 4
                    ? meal("Earlier meal description", random, day + 50) : null;
            if (initial != null) initial.put("description", "Temporary entry awaiting correction");
            entries.add(entry("meal-morning-" + day, EntryType.MEAL, date, LocalTime.of(8, 0)
                    .plusMinutes(random.nextInt(45)), timezone, meal, mealOrigins(), initial,
                    profile == SyntheticProfile.REGULAR && day == 6 ? 2 : 1, false));
        }
        if (dinner) {
            entries.add(entry("meal-evening-" + day, EntryType.MEAL, date, LocalTime.of(19, 0)
                    .plusMinutes(random.nextInt(90)), timezone, meal("Fictional evening meal", random, day + 100),
                    mealOrigins(), null, 1, false));
        }

        boolean hasSteps = regular || (profile == SyntheticProfile.IRREGULAR
                ? day % 4 != 2 : day % 3 != 1);
        if (hasSteps) {
            entries.add(metric("steps-" + day, date, LocalTime.of(21, 0), timezone, "steps",
                    3500 + random.nextInt(11000), "count"));
        }
        boolean hasSleep = regular || (profile == SyntheticProfile.IRREGULAR
                ? day % 3 == 0 : day % 4 == 1);
        if (hasSleep) {
            entries.add(metric("sleep-" + day, date, LocalTime.of(7, 5), timezone, "sleep_duration_min",
                    300 + random.nextInt(300), "min"));
        }
        if (regular || (profile == SyntheticProfile.IRREGULAR ? day % 2 == 0 : day % 3 == 0)) {
            entries.add(metric("heart-rate-" + day, date, LocalTime.of(7, 15), timezone,
                    "heart_rate", 58 + random.nextInt(40), "bpm"));
        }
        if (regular || (profile == SyntheticProfile.IRREGULAR ? day % 3 == 1 : day % 5 == 0)) {
            entries.add(entry("quick-note-" + day, EntryType.NOTE, date, LocalTime.of(20, 15), timezone,
                    Map.of("text", "Synthetic journal note " + (day + 1) + " (not health advice)."),
                    Map.of("text", "reported"), null, 1, false));
        }
        if (regular || (profile == SyntheticProfile.IRREGULAR ? day % 4 == 0 : day % 6 == 0)) {
            Map<String, Object> payload = Map.of("category", "wellbeing", "score", 1 + random.nextInt(5));
            entries.add(entry("quick-checkin-" + day, EntryType.CHECKIN, date, LocalTime.of(20, 30), timezone,
                    payload, Map.of("category", "reported", "score", "reported"), null, 1,
                    incomplete && day == 6));
        }
    }

    private static Map<String, Object> meal(String description, SplittableRandom random, int variation) {
        Map<String, Object> nutrients = new LinkedHashMap<>();
        nutrients.put("energy_kcal", 320 + random.nextInt(380) + variation % 7);
        nutrients.put("protein_g", 8 + random.nextInt(25));
        nutrients.put("fat_g", 5 + random.nextInt(24));
        nutrients.put("carbs_g", 20 + random.nextInt(60));
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("description", description + " " + (variation + 1));
        payload.put("mass_g", 180 + random.nextInt(280));
        payload.put("nutrients", nutrients);
        payload.put("nutrients_basis", "per_serving");
        return payload;
    }

    private static Map<String, String> mealOrigins() {
        return Map.of("description", "reported", "mass_g", "estimated",
                "nutrients.energy_kcal", "estimated", "nutrients.protein_g", "estimated",
                "nutrients.fat_g", "estimated", "nutrients.carbs_g", "estimated",
                "nutrients_basis", "reported");
    }

    private static SyntheticEntry metric(String key, LocalDate date, LocalTime time, ZoneId timezone,
                                         String code, int value, String unit) {
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("code", code);
        payload.put("value", value);
        payload.put("unit", unit);
        payload.put("local_date", date.toString());
        payload.put("local_time", time.toString());
        if (code.equals("heart_rate")) payload.put("qualifier", "resting");
        Map<String, String> origins = new LinkedHashMap<>();
        origins.put("code", "extracted");
        origins.put("value", "extracted");
        origins.put("unit", "extracted");
        origins.put("local_date", "computed");
        origins.put("local_time", "computed");
        if (code.equals("heart_rate")) origins.put("qualifier", "estimated");
        return entry(key, EntryType.METRICS, date, time, timezone, payload, origins, null, 1, false);
    }

    private static SyntheticEntry entry(String key, EntryType type, LocalDate date, LocalTime time, ZoneId timezone,
                                        Map<String, Object> payload, Map<String, String> origins,
                                        Map<String, Object> initialPayload, int deliveries, boolean cancelled) {
        return new SyntheticEntry(key, type, ZonedDateTime.of(date, time, timezone), payload, origins, initialPayload,
                deliveries, cancelled);
    }

    public record SyntheticProfileData(SyntheticProfile profile, List<LocalDate> days, List<SyntheticEntry> entries) {
        public SyntheticProfileData {
            days = List.copyOf(days);
            entries = List.copyOf(entries);
            if (days.size() != DAYS) throw new IllegalArgumentException("Profiles must span 21 days");
        }
    }
}
