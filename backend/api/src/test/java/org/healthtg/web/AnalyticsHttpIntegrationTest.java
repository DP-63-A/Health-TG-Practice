package org.healthtg.web;

import org.healthtg.core.entry.Entry;
import org.healthtg.core.entry.EntryCoreService;
import org.healthtg.core.entry.EntryStatus;
import org.healthtg.core.entry.EntryType;
import org.healthtg.core.entry.ListEntriesQuery;
import org.healthtg.core.entry.SourceKind;
import org.healthtg.session.SessionService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.hamcrest.Matchers.nullValue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest(properties = "spring.data.mongodb.auto-index-creation=false")
@AutoConfigureMockMvc
class AnalyticsHttpIntegrationTest {
    private static final UUID OWNER = UUID.fromString("11111111-1111-4111-8111-111111111101");

    @Autowired MockMvc mockMvc;
    @MockitoBean SessionService sessions;
    @MockitoBean EntryCoreService entries;

    @BeforeEach
    void authenticate() {
        when(sessions.authenticate("test-session")).thenReturn(OWNER);
        when(entries.listEntries(any())).thenReturn(List.of());
    }

    @Test
    void servesOwnerScopedAnalyticsUsingTheOpenApiResponseShape() throws Exception {
        mockMvc.perform(get("/api/v1/analytics").queryParam("period", "days_7")
                        .queryParam("timezone", "Europe/Warsaw")
                        .header("Authorization", "Bearer " + "test-session"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.period.kind").value("days_7"))
                .andExpect(jsonPath("$.period.timezone").value("Europe/Warsaw"))
                .andExpect(jsonPath("$.cards.nutrition.energy_kcal").value(nullValue()))
                .andExpect(jsonPath("$.cards.sleep.total_minutes").value(nullValue()))
                .andExpect(jsonPath("$.cards.sleep.average_minutes").value(nullValue()))
                .andExpect(jsonPath("$.cards.sleep.days_with_data").value(0))
                .andExpect(jsonPath("$.cards.sleep.total").doesNotExist())
                .andExpect(jsonPath("$.cards.steps.total").value(nullValue()))
                .andExpect(jsonPath("$.cards.steps.days_with_data").value(0))
                .andExpect(jsonPath("$.series.checkin.category").value("mood"))
                .andExpect(jsonPath("$.observations.days_in_period").value(7))
                .andExpect(jsonPath("$.sources").isArray());

        org.mockito.ArgumentCaptor<ListEntriesQuery> query =
                org.mockito.ArgumentCaptor.forClass(ListEntriesQuery.class);
        verify(entries).listEntries(query.capture());
        org.junit.jupiter.api.Assertions.assertEquals(OWNER, query.getValue().owner().userId());
        org.junit.jupiter.api.Assertions.assertEquals(EntryStatus.CONFIRMED, query.getValue().status());
        org.junit.jupiter.api.Assertions.assertEquals(ZoneId.of("Europe/Warsaw"), query.getValue().timezone());
    }

    @Test
    void sleepCardUsesCanonicalMinuteFieldNames() throws Exception {
        String today = LocalDate.now(ZoneId.of("Europe/Warsaw")).toString();
        when(entries.listEntries(any())).thenReturn(List.of(
                metric("sleep_duration_min", 420, today), metric("steps", 5000, today)));

        mockMvc.perform(get("/api/v1/analytics").queryParam("period", "today")
                        .header("Authorization", "Bearer " + "test-session"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.cards.sleep.total_minutes").value(420))
                .andExpect(jsonPath("$.cards.sleep.average_minutes").value(420))
                .andExpect(jsonPath("$.cards.sleep.days_with_data").value(1))
                .andExpect(jsonPath("$.cards.sleep.total").doesNotExist())
                .andExpect(jsonPath("$.cards.sleep.average").doesNotExist())
                .andExpect(jsonPath("$.cards.steps.total").value(5000))
                .andExpect(jsonPath("$.cards.steps.average").value(5000))
                .andExpect(jsonPath("$.cards.steps.total_minutes").doesNotExist());
    }

    @Test
    void fractionalStepsAndSleepDoNotCauseServerError() throws Exception {
        String today = LocalDate.now(ZoneId.of("Europe/Warsaw")).toString();
        when(entries.listEntries(any())).thenReturn(List.of(
                metric("sleep_duration_min", 420.5, today), metric("steps", 1.0e12, today)));

        mockMvc.perform(get("/api/v1/analytics").queryParam("period", "today")
                        .header("Authorization", "Bearer " + "test-session"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.cards.sleep.total_minutes").value(421))
                .andExpect(jsonPath("$.cards.sleep.days_with_data").value(1))
                .andExpect(jsonPath("$.cards.steps.total").value(nullValue()))
                .andExpect(jsonPath("$.cards.steps.days_with_data").value(0));
    }

    @Test
    void rejectsUnknownPeriodAndInvalidTimezone() throws Exception {
        mockMvc.perform(get("/api/v1/analytics").queryParam("period", "days_30")
                        .header("Authorization", "Bearer " + "test-session"))
                .andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.code").value("VALIDATION_ERROR"));

        mockMvc.perform(get("/api/v1/analytics").queryParam("period", "today")
                        .queryParam("timezone", "Not/AZone")
                        .header("Authorization", "Bearer " + "test-session"))
                .andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.code").value("VALIDATION_ERROR"));
    }

    private static Entry metric(String code, Number value, String localDate) {
        Instant now = Instant.now();
        Map<String, Object> payload = Map.of("code", code, "value", value, "local_date", localDate,
                "unit", code.equals("steps") ? "count" : "min");
        return new Entry(UUID.nameUUIDFromBytes((code + value).getBytes()), OWNER, EntryType.METRICS,
                EntryStatus.CONFIRMED, SourceKind.SEED, Map.of(), now, now, now, 1, payload, Map.of(),
                "sub-" + code, "key-" + code);
    }
}
