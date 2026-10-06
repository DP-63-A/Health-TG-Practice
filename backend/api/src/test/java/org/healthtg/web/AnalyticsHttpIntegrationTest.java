package org.healthtg.web;

import org.healthtg.core.entry.EntryCoreService;
import org.healthtg.core.entry.ListConfirmedEntriesQuery;
import org.healthtg.session.SessionService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

import java.time.ZoneId;
import java.util.List;
import java.util.UUID;

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
        when(entries.listConfirmedEntries(any())).thenReturn(List.of());
    }

    @Test
    void servesOwnerScopedAnalyticsUsingTheOpenApiResponseShape() throws Exception {
        mockMvc.perform(get("/api/v1/analytics").queryParam("period", "days_7")
                        .queryParam("timezone", "Europe/Warsaw")
                        .header("Authorization", "Bearer " + "test-session"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.period.kind").value("days_7"))
                .andExpect(jsonPath("$.period.timezone").value("Europe/Warsaw"))
                .andExpect(jsonPath("$.cards.nutrition.energy_kcal").value(org.hamcrest.Matchers.nullValue()))
                .andExpect(jsonPath("$.cards.steps.days_with_data").value(0))
                .andExpect(jsonPath("$.series.checkin.category").value("mood"))
                .andExpect(jsonPath("$.observations.days_in_period").value(7))
                .andExpect(jsonPath("$.sources").isArray());

        org.mockito.ArgumentCaptor<ListConfirmedEntriesQuery> query =
                org.mockito.ArgumentCaptor.forClass(ListConfirmedEntriesQuery.class);
        verify(entries).listConfirmedEntries(query.capture());
        org.junit.jupiter.api.Assertions.assertEquals(OWNER, query.getValue().owner().userId());
        org.junit.jupiter.api.Assertions.assertEquals(ZoneId.of("Europe/Warsaw"), query.getValue().timezone());
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
}
