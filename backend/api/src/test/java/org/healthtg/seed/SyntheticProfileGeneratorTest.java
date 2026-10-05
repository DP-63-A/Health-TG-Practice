package org.healthtg.seed;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.healthtg.core.entry.EntryType;
import org.junit.jupiter.api.Test;

import java.io.File;
import java.time.LocalDate;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class SyntheticProfileGeneratorTest {
    private static final LocalDate START = LocalDate.of(2026, 9, 14);
    private static final long SEED = 20260916L;

    @Test
    void sameSeedAndDateGiveIdenticalRecordsAndOtherParametersDiffer() {
        for (SeedProfile profile : SeedProfile.values()) {
            assertEquals(SyntheticProfileGenerator.generate(profile, SEED, START),
                    SyntheticProfileGenerator.generate(profile, SEED, START));
            assertNotEquals(SyntheticProfileGenerator.generate(profile, SEED, START),
                    SyntheticProfileGenerator.generate(profile, SEED + 1, START));
        }
    }

    @Test
    void indexesAreUniqueSequentialAndChronological() {
        for (SeedProfile profile : SeedProfile.values()) {
            List<SeedRecord> records = SyntheticProfileGenerator.generate(profile, SEED, START);
            for (int i = 0; i < records.size(); i++) {
                assertEquals(i, records.get(i).index());
                if (i > 0) {
                    assertTrue(!records.get(i).date().atTime(records.get(i).time())
                            .isBefore(records.get(i - 1).date().atTime(records.get(i - 1).time())));
                }
                assertTrue(!records.get(i).date().isBefore(START) && records.get(i).date().isBefore(START.plusDays(21)));
            }
        }
    }

    @Test
    void everyProfileHasNutritionDeviceMetricsAndQuickCheckinsAndCoversRequiredFeatures() {
        int changed = 0;
        int redelivered = 0;
        int cancelled = 0;
        for (SeedProfile profile : SeedProfile.values()) {
            List<SeedRecord> records = SyntheticProfileGenerator.generate(profile, SEED, START);
            Set<EntryType> types = new HashSet<>();
            records.stream().filter(r -> !r.cancelledDraft()).forEach(r -> types.add(r.type()));
            assertTrue(types.containsAll(Set.of(EntryType.MEAL, EntryType.METRICS, EntryType.CHECKIN)), profile.code());
            changed += (int) records.stream().filter(SeedRecord::changed).count();
            redelivered += (int) records.stream().filter(SeedRecord::redelivered).count();
            cancelled += (int) records.stream().filter(SeedRecord::cancelledDraft).count();
        }
        assertTrue(changed >= 1 && redelivered >= 1 && cancelled >= 1);
    }

    @Test
    void incompleteProfileHasFullyEmptyDaysAndOtherProfilesHaveNone() {
        assertEquals(Set.of(14 + 4, 14 + 5, 14 + 13), emptyDays(SeedProfile.INCOMPLETE));
        assertTrue(emptyDays(SeedProfile.REGULAR).isEmpty());
        assertTrue(emptyDays(SeedProfile.IRREGULAR).isEmpty());
    }

    @Test
    void regularHasMorningAndEveningEntriesEveryDayAndIrregularSkipsMeals() {
        List<SeedRecord> regular = SyntheticProfileGenerator.generate(SeedProfile.REGULAR, SEED, START);
        for (int day = 0; day < 21; day++) {
            LocalDate date = START.plusDays(day);
            assertTrue(regular.stream().anyMatch(r -> r.date().equals(date) && r.time().getHour() < 10));
            assertTrue(regular.stream().anyMatch(r -> r.date().equals(date) && r.time().getHour() >= 19));
        }
        Set<Integer> days = new HashSet<>();
        for (SeedRecord r : SyntheticProfileGenerator.generate(SeedProfile.IRREGULAR, SEED, START)) {
            if (r.type() == EntryType.MEAL && !r.cancelledDraft()) days.add(r.date().getDayOfMonth());
        }
        assertTrue(days.size() <= 19);
    }

    @Test
    void payloadsAreFictionalAndUseAgreedUnitsAndOrigins() {
        for (SeedProfile profile : SeedProfile.values()) {
            for (SeedRecord record : SyntheticProfileGenerator.generate(profile, SEED, START)) {
                if (record.type() == EntryType.MEAL && !record.cancelledDraft()) {
                    assertTrue(((String) record.payload().get("description")).startsWith("Synthetic"));
                }
                if (record.type() == EntryType.METRICS && !record.cancelledDraft()) {
                    Map<String, Object> payload = record.payload();
                    Map<String, String> units = Map.of("steps", "count", "sleep_duration_min", "min",
                            "heart_rate", "bpm");
                    assertEquals(units.get(payload.get("code")), payload.get("unit"));
                    assertEquals(record.date().toString(), payload.get("local_date"));
                }
                assertTrue(record.fieldOrigins().values().stream()
                        .allMatch(o -> Set.of("reported", "extracted", "estimated", "computed").contains(o)));
            }
        }
    }

    @Test
    void generatedDailyValuesMatchFrozenControlFixture() throws Exception {
        JsonNode control = new ObjectMapper().readTree(new File("../../fixtures/be3-05/control-values.json"));
        assertEquals(SEED, control.get("random_seed").asLong());
        assertEquals(START.toString(), control.get("start_date").asText());
        for (SeedProfile profile : SeedProfile.values()) {
            JsonNode expected = control.get("profiles").get(profile.code());
            List<SeedRecord> records = SyntheticProfileGenerator.generate(profile, SEED, START);
            for (JsonNode day : expected.get("days")) {
                LocalDate date = LocalDate.parse(day.get("date").asText());
                int kcal = 0;
                int meals = 0;
                int checkins = 0;
                Integer steps = null;
                for (SeedRecord record : records) {
                    if (record.cancelledDraft() || !record.date().equals(date)) continue;
                    Map<String, Object> payload = record.finalPayload();
                    switch (record.type()) {
                        case MEAL -> {
                            meals++;
                            kcal += (Integer) ((Map<?, ?>) payload.get("nutrients")).get("energy_kcal");
                        }
                        case CHECKIN -> checkins++;
                        case METRICS -> {
                            if (payload.get("code").equals("steps")) steps = (Integer) payload.get("value");
                        }
                        default -> { }
                    }
                }
                assertEquals(day.get("meals").asInt(), meals, profile + " " + date);
                assertEquals(day.get("checkins").asInt(), checkins, profile + " " + date);
                assertEquals(day.get("energy_kcal").isNull() ? null : day.get("energy_kcal").asInt(),
                        meals == 0 ? null : kcal, profile + " " + date);
                assertEquals(day.get("steps").isNull() ? null : day.get("steps").asInt(), steps,
                        profile + " " + date);
            }
        }
    }

    private static Set<Integer> emptyDays(SeedProfile profile) {
        Set<LocalDate> withData = new HashSet<>();
        SyntheticProfileGenerator.generate(profile, SEED, START).forEach(r -> withData.add(r.date()));
        Set<Integer> empty = new HashSet<>();
        for (int day = 0; day < 21; day++) {
            if (!withData.contains(START.plusDays(day))) empty.add(START.plusDays(day).getDayOfMonth());
        }
        return empty;
    }
}
