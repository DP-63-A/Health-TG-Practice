package org.healthtg.seed;

import org.healthtg.core.entry.EntryType;
import org.junit.jupiter.api.Test;

import java.time.LocalDate;
import java.time.ZoneId;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class SyntheticDatasetGeneratorTest {
    private final SyntheticDatasetGenerator generator = new SyntheticDatasetGenerator();
    private final LocalDate startDate = LocalDate.of(2026, 9, 1);
    private final ZoneId timezone = ZoneId.of("America/New_York");

    @Test
    void generatesThreeReproducibleTwentyOneDayScenariosWithRequiredCoverage() {
        List<SyntheticDatasetGenerator.SyntheticProfileData> generated =
                generator.generate(24680, startDate, timezone);

        assertEquals(List.of(SyntheticProfile.REGULAR, SyntheticProfile.IRREGULAR, SyntheticProfile.INCOMPLETE),
                generated.stream().map(SyntheticDatasetGenerator.SyntheticProfileData::profile).toList());
        for (var profile : generated) {
            assertEquals(21, profile.days().size());
            assertEquals(startDate, profile.days().getFirst());
            assertEquals(startDate.plusDays(20), profile.days().getLast());
            assertTrue(profile.entries().stream().anyMatch(entry -> entry.type() == EntryType.MEAL));
            assertTrue(profile.entries().stream().anyMatch(entry -> entry.type() == EntryType.METRICS));
            assertTrue(profile.entries().stream().anyMatch(entry -> entry.type() == EntryType.NOTE));
            assertTrue(profile.entries().stream().allMatch(entry -> entry.occurredAt().getZone().equals(timezone)));
            assertTrue(profile.entries().stream().anyMatch(entry -> entry.occurredAt().getHour() < 12));
            assertTrue(profile.entries().stream().anyMatch(entry -> entry.occurredAt().getHour() >= 18));
        }

        var incomplete = generated.get(2);
        LocalDate emptyDay = startDate.plusDays(10);
        assertFalse(incomplete.entries().stream().anyMatch(entry ->
                entry.occurredAt().toLocalDate().equals(emptyDay)));
        assertTrue(generated.get(0).entries().stream().anyMatch(entry -> entry.deliveries() == 2));
        var changed = generated.get(0).entries().stream().filter(entry -> entry.initialPayload() != null)
                .findFirst().orElseThrow();
        assertNotEquals(changed.initialPayload(), changed.payload());
        assertTrue(incomplete.entries().stream().anyMatch(SyntheticEntry::cancelled));

        assertEquals(generated, generator.generate(24680, startDate, timezone));
        assertNotEquals(generated, generator.generate(24681, startDate, timezone));
    }

    @Test
    void recordsVariationAndValidatedUnitsAndOrigins() {
        var regular = generator.generateProfile(SyntheticProfile.REGULAR, 24680, startDate, timezone);
        List<Object> dailyStepValues = regular.entries().stream()
                .filter(entry -> entry.type() == EntryType.METRICS)
                .filter(entry -> entry.payload().get("code").equals("steps"))
                .map(entry -> entry.payload().get("value"))
                .distinct()
                .toList();

        assertTrue(dailyStepValues.size() > 1);
        var metric = regular.entries().stream().filter(entry -> entry.type() == EntryType.METRICS)
                .findFirst().orElseThrow();
        assertEquals("count", metric.payload().get("unit"));
        assertEquals("computed", metric.fieldOrigins().get("local_date"));
        assertTrue(Map.of("reported", true, "estimated", true, "extracted", true, "computed", true)
                .keySet().containsAll(metric.fieldOrigins().values()));
    }
}
