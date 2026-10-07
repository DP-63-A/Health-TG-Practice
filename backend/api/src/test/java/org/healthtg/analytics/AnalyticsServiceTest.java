package org.healthtg.analytics;

import org.healthtg.core.entry.Entry;
import org.healthtg.core.entry.EntryCoreService;
import org.healthtg.core.entry.EntryStatus;
import org.healthtg.core.entry.EntryType;
import org.healthtg.core.entry.ListEntriesQuery;
import org.healthtg.core.entry.OwnerContext;
import org.healthtg.core.entry.SourceKind;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class AnalyticsServiceTest {
    private static final UUID OWNER = UUID.fromString("11111111-1111-4111-8111-111111111101");
    private static final Instant NOW = Instant.parse("2026-09-19T20:05:00Z");
    private final EntryCoreService entries = mock(EntryCoreService.class);
    private final AnalyticsService service = new AnalyticsService(entries, Clock.fixed(NOW, ZoneOffset.UTC));

    @Test
    void buildsAnalyticsFromOwnerConfirmedEntriesAndKeepsContractSemantics() {
        List<Entry> found = List.of(
                entry("meal-1", EntryType.MEAL, "2026-09-19T10:00:00Z",
                        Map.of("mass_g", 200, "nutrients_basis", "per_100g",
                                "nutrients", Map.of("energy_kcal", 165, "protein_g", 10, "fat_g", 5, "carbs_g", 20))),
                entry("meal-2", EntryType.MEAL, "2026-09-19T11:00:00Z",
                        Map.of("nutrients_basis", "per_serving",
                                "nutrients", Map.of("energy_kcal", 600, "protein_g", 30, "fat_g", 20, "carbs_g", 70))),
                metric("steps-1", "steps", 3000, "2026-09-19T12:00:00Z", "2026-09-19"),
                metric("steps-2", "steps", 5000, "2026-09-19T20:00:00Z", "2026-09-19"),
                metric("sleep", "sleep_duration_min", 420, "2026-09-14T06:30:00Z", "2026-09-14"),
                metric("heart-rate", "heart_rate", 72, "2026-09-19T10:15:00Z", "2026-09-19",
                        Map.of("qualifier", "resting")),
                checkin("sleep-quality", "sleep_quality", 4),
                checkin("digestion", "digestion_comfort", 5),
                checkin("wellbeing", "wellbeing", 3),
                checkin("mood", "mood", 4));
        when(entries.listEntries(any())).thenReturn(found);

        var response = service.calculate(new OwnerContext(OWNER), "days_7", null, null);

        ArgumentCaptor<ListEntriesQuery> query = ArgumentCaptor.forClass(ListEntriesQuery.class);
        verify(entries).listEntries(query.capture());
        assertEquals(OWNER, query.getValue().owner().userId());
        assertEquals(EntryStatus.CONFIRMED, query.getValue().status());
        assertNull(query.getValue().from());
        assertNull(query.getValue().to());
        assertEquals(ZoneId.of("Europe/Warsaw"), query.getValue().timezone());
        assertEquals("Europe/Warsaw", response.period().timezone());
        assertEquals(2, response.cards().mealCount().count());
        assertEquals(930, response.cards().nutrition().energyKcal().intValueExact());
        assertEquals(5000L, response.cards().steps().total());
        assertEquals(1, response.cards().steps().daysWithData());
        assertEquals(420L, response.cards().sleep().totalMinutes());
        assertEquals(1, response.cards().sleep().daysWithData());
        assertEquals(4, response.cards().checkins().mood().score());
        assertEquals("mood", response.series().checkin().category());
        assertEquals(found.get(3).id(), response.series().steps().getFirst().source().entryId());
        assertEquals(NOW, response.observations().generatedAt());
        assertEquals(2, response.observations().daysWithAnyData());
    }

    @Test
    void sleepIsAttributedToWakeDateEvenWhenOccurredAtIsOutsideThePeriod() {
        // Wake date 2026-09-19 (inside the period), reported after local midnight on 2026-09-20.
        Entry inside = metric("sleep-late", "sleep_duration_min", 450, "2026-09-19T23:30:00Z", "2026-09-19");
        // occurred_at is inside the period but the wake date is before it.
        Entry before = metric("sleep-old", "sleep_duration_min", 300, "2026-09-13T10:00:00Z", "2026-09-12");
        when(entries.listEntries(any())).thenReturn(List.of(inside, before));

        var response = service.calculate(new OwnerContext(OWNER), "days_7", "Europe/Warsaw", null);

        assertEquals(450L, response.cards().sleep().totalMinutes());
        assertEquals(1, response.cards().sleep().daysWithData());
        assertEquals(1, response.series().sleep().size());
        assertEquals(inside.id(), response.series().sleep().getFirst().source().entryId());
        assertEquals(1, response.observations().daysWithAnyData());
    }

    @Test
    void ignoresFractionalAndOutOfRangeStepsAndSleepInsteadOfFailing() {
        List<Entry> found = List.of(
                metric("sleep-fraction", "sleep_duration_min", 420.5, "2026-09-19T06:00:00Z", "2026-09-19"),
                metric("steps-huge", "steps", 1.0e12, "2026-09-19T06:00:00Z", "2026-09-19"),
                metric("steps-ok", "steps", 8000.0, "2026-09-19T07:00:00Z", "2026-09-19"));
        when(entries.listEntries(any())).thenReturn(found);

        var response = service.calculate(new OwnerContext(OWNER), "today", null, null);

        assertNull(response.cards().sleep().totalMinutes());
        assertEquals(0, response.cards().sleep().daysWithData());
        assertEquals(8000L, response.cards().steps().total());
        assertEquals(1, response.cards().steps().daysWithData());
    }

    @Test
    void rejectsUnknownPeriodsAndTimezones() {
        assertThrows(IllegalArgumentException.class,
                () -> service.calculate(new OwnerContext(OWNER), "days_30", null, null));
        assertThrows(IllegalArgumentException.class,
                () -> service.calculate(new OwnerContext(OWNER), "today", "Not/AZone", null));
    }

    private static Entry entry(String id, EntryType type, String occurredAt, Map<String, Object> payload) {
        UUID entryId = UUID.nameUUIDFromBytes(id.getBytes());
        Instant instant = Instant.parse(occurredAt);
        return new Entry(entryId, OWNER, type, EntryStatus.CONFIRMED, SourceKind.SEED, Map.of(), instant,
                instant, instant, 1, payload, Map.of(), id, id);
    }

    private static Entry metric(String id, String code, Number value, String occurredAt, String localDate) {
        return metric(id, code, value, occurredAt, localDate, Map.of());
    }

    private static Entry metric(String id, String code, Number value, String occurredAt, String localDate,
                                Map<String, Object> additional) {
        Map<String, Object> payload = new HashMap<>(additional);
        payload.put("code", code);
        payload.put("value", value);
        payload.put("local_date", localDate);
        payload.put("unit", code.equals("steps") ? "count" : code.equals("heart_rate") ? "bpm" : "min");
        return entry(id, EntryType.METRICS, occurredAt, payload);
    }

    private static Entry checkin(String id, String category, int score) {
        return entry(id, EntryType.CHECKIN, "2026-09-19T20:30:00Z",
                Map.of("category", category, "score", score));
    }
}
