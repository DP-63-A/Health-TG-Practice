package org.healthtg.analytics;

import org.bson.Document;
import org.healthtg.auth.AuthFailureException;
import org.healthtg.core.entry.CheckinCategory;
import org.healthtg.core.entry.ConfirmEntryCommand;
import org.healthtg.core.entry.CreateCheckinCommand;
import org.healthtg.core.entry.CreateDraftCommand;
import org.healthtg.core.entry.Entry;
import org.healthtg.core.entry.EntryCoreService;
import org.healthtg.core.entry.EntryType;
import org.healthtg.core.entry.OwnerContext;
import org.healthtg.core.entry.SourceKind;
import org.healthtg.core.entry.TelegramUpdateKey;
import org.healthtg.session.SessionService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.context.annotation.Primary;
import org.springframework.data.mongodb.core.MongoTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.testcontainers.containers.MongoDBContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.utility.DockerImageName;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Map;
import java.util.UUID;

import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest
@AutoConfigureMockMvc
@Import(AnalyticsHttpIntegrationTest.FixedClockConfiguration.class)
@Testcontainers(disabledWithoutDocker = true)
class AnalyticsHttpIntegrationTest {
    private static final UUID OWNER_ID = UUID.fromString("11111111-1111-4111-8111-111111111101");
    private static final UUID OTHER_ID = UUID.fromString("22222222-2222-4222-8222-222222222202");
    private static final OwnerContext OWNER = new OwnerContext(OWNER_ID);
    private static final OwnerContext OTHER = new OwnerContext(OTHER_ID);
    private static final Instant NOW = Instant.parse("2026-09-19T20:05:00Z");

    @Container
    static final MongoDBContainer MONGO = new MongoDBContainer(
            DockerImageName.parse("mongodb/mongodb-community-server:8.0-ubi9-slim")
                    .asCompatibleSubstituteFor("mongo"));

    @DynamicPropertySource
    static void mongoProperties(DynamicPropertyRegistry registry) {
        registry.add("spring.data.mongodb.uri", MONGO::getReplicaSetUrl);
        registry.add("spring.data.mongodb.auto-index-creation", () -> "true");
    }

    @Autowired MockMvc mockMvc;
    @Autowired EntryCoreService entries;
    @Autowired MongoTemplate mongo;
    @MockitoBean SessionService sessions;

    @BeforeEach
    void prepare() {
        mongo.getCollectionNames()
                .forEach(collection -> mongo.getCollection(collection).deleteMany(new Document()));
        when(sessions.authenticate("owner-session")).thenReturn(OWNER_ID);
        when(sessions.authenticate("other-session")).thenReturn(OTHER_ID);
        when(sessions.authenticate("expired-session"))
                .thenThrow(new AuthFailureException("Session missing or expired"));
    }

    @Test
    void returnsOwnedAnalyticsAndReflectsPatchAndDelete() throws Exception {
        confirmMeal(OWNER, 1, Instant.parse("2026-09-18T22:30:00Z"), null, "per_serving", 600);
        Entry variable = confirmMeal(OWNER, 2, Instant.parse("2026-09-19T09:00:00Z"), 200,
                "per_100g", 165);
        confirmMeal(OTHER, 3, Instant.parse("2026-09-19T10:00:00Z"), null, "per_serving", 999);
        confirmMetric(OWNER, 4, "steps", 3000, "count", "2026-09-19", null, null,
                Instant.parse("2026-09-19T10:00:00Z"));
        Entry selectedSteps = confirmMetric(OWNER, 5, "steps", 5000, "count", "2026-09-19", null,
                null, Instant.parse("2026-09-19T18:00:00Z"));
        confirmMetric(OWNER, 6, "sleep_duration_min", 420, "min", "2026-09-19", "07:00", null,
                Instant.parse("2026-09-19T05:00:00Z"));
        Entry heart = confirmMetric(OWNER, 7, "heart_rate", 72, "bpm", "2026-09-19", "12:10",
                "resting", Instant.parse("2026-09-19T10:15:00Z"));
        for (CheckinCategory category : CheckinCategory.values()) {
            entries.createCheckin(new CreateCheckinCommand(OWNER, category, category.ordinal() + 2,
                    Instant.parse("2026-09-19T12:00:00Z"),
                    new TelegramUpdateKey("analytics", 20 + category.ordinal())));
        }

        analytics("owner-session")
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.period.from").value("2026-09-19"))
                .andExpect(jsonPath("$.cards.nutrition.energy_kcal").value(930))
                .andExpect(jsonPath("$.cards.nutrition.meals_with_energy").value(2))
                .andExpect(jsonPath("$.cards.meal_count.count").value(2))
                .andExpect(jsonPath("$.cards.sleep.total_minutes").value(420))
                .andExpect(jsonPath("$.cards.steps.total").value(5000))
                .andExpect(jsonPath("$.cards.steps.days_with_data").value(1))
                .andExpect(jsonPath("$.series.steps[0].source.entry_id").value(selectedSteps.id().toString()))
                .andExpect(jsonPath("$.cards.heart_rate.entry_id").value(heart.id().toString()))
                .andExpect(jsonPath("$.cards.checkins.sleep_quality.score").value(2))
                .andExpect(jsonPath("$.cards.checkins.digestion_comfort.score").value(3))
                .andExpect(jsonPath("$.cards.checkins.wellbeing.score").value(4))
                .andExpect(jsonPath("$.cards.checkins.mood.score").value(5))
                .andExpect(jsonPath("$.series.checkin.category").value("mood"))
                .andExpect(jsonPath("$.observations.days_with_any_data").value(1));

        mockMvc.perform(get("/api/v1/analytics").header("Authorization", "Bearer owner-session")
                        .param("period", "today").param("user_id", OTHER_ID.toString()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.cards.nutrition.energy_kcal").value(930));

        mockMvc.perform(get("/api/v1/analytics").header("Authorization", "Bearer owner-session")
                        .param("period", "days_7").param("checkin_category", "sleep_quality"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.period.from").value("2026-09-13"))
                .andExpect(jsonPath("$.period.to").value("2026-09-19"))
                .andExpect(jsonPath("$.series.checkin.category").value("sleep_quality"))
                .andExpect(jsonPath("$.series.checkin.points[0].value").value(2));

        mockMvc.perform(patch("/api/v1/entries/{id}", variable.id())
                        .header("Authorization", "Bearer owner-session")
                        .contentType("application/json")
                        .content("{\"expected_revision\":2,\"payload\":{\"mass_g\":150}}"))
                .andExpect(status().isOk());
        analytics("owner-session").andExpect(status().isOk())
                .andExpect(jsonPath("$.cards.nutrition.energy_kcal").value(847.5));

        mockMvc.perform(delete("/api/v1/entries/{id}", variable.id())
                        .header("Authorization", "Bearer owner-session").header("If-Match", "\"3\""))
                .andExpect(status().isOk());
        analytics("owner-session").andExpect(status().isOk())
                .andExpect(jsonPath("$.cards.nutrition.energy_kcal").value(600))
                .andExpect(jsonPath("$.cards.meal_count.count").value(1));

        analytics("other-session").andExpect(status().isOk())
                .andExpect(jsonPath("$.cards.nutrition.energy_kcal").value(999))
                .andExpect(jsonPath("$.sources.length()").value(1));
    }

    @Test
    void enforcesAuthenticationParametersAndNullSemantics() throws Exception {
        mockMvc.perform(get("/api/v1/analytics").param("period", "today"))
                .andExpect(status().isUnauthorized());
        mockMvc.perform(get("/api/v1/analytics").header("Authorization", "Bearer expired-session")
                        .param("period", "today"))
                .andExpect(status().isUnauthorized());
        mockMvc.perform(get("/api/v1/analytics").header("Authorization", "Bearer owner-session"))
                .andExpect(status().isUnprocessableEntity());
        mockMvc.perform(get("/api/v1/analytics").header("Authorization", "Bearer owner-session")
                        .param("period", "tomorrow"))
                .andExpect(status().isUnprocessableEntity());
        mockMvc.perform(get("/api/v1/analytics").header("Authorization", "Bearer owner-session")
                        .param("period", "today").param("timezone", "Mars/Olympus"))
                .andExpect(status().isUnprocessableEntity());
        mockMvc.perform(get("/api/v1/analytics").header("Authorization", "Bearer owner-session")
                        .param("period", "today").param("checkin_category", "energy"))
                .andExpect(status().isUnprocessableEntity());

        analytics("owner-session").andExpect(status().isOk())
                .andExpect(jsonPath("$.cards.nutrition.energy_kcal").isEmpty())
                .andExpect(jsonPath("$.cards.steps.total").isEmpty())
                .andExpect(jsonPath("$.cards.heart_rate.value_bpm").isEmpty())
                .andExpect(jsonPath("$.observations.days_with_any_data").value(0))
                .andExpect(jsonPath("$.sources.length()").value(0));
        mockMvc.perform(get("/api/v1/analytics").header("Authorization", "Bearer owner-session")
                        .param("period", "days_21").param("timezone", "UTC"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.period.from").value("2026-08-30"))
                .andExpect(jsonPath("$.period.to").value("2026-09-19"))
                .andExpect(jsonPath("$.period.timezone").value("UTC"))
                .andExpect(jsonPath("$.observations.days_in_period").value(21));
    }

    private org.springframework.test.web.servlet.ResultActions analytics(String session) throws Exception {
        return mockMvc.perform(get("/api/v1/analytics")
                .header("Authorization", "Bearer " + session)
                .param("period", "today").param("timezone", "Europe/Warsaw"));
    }

    private Entry confirmMeal(OwnerContext owner, long update, Instant occurredAt, Integer mass,
                              String basis, int energy) {
        Map<String, Object> payload = new java.util.LinkedHashMap<>();
        payload.put("description", "meal-" + update);
        if (mass != null) payload.put("mass_g", mass);
        payload.put("nutrients_basis", basis);
        payload.put("nutrients", Map.of("energy_kcal", energy, "protein_g", 10,
                "fat_g", 5, "carbs_g", 20));
        Entry draft = entries.createDraft(new CreateDraftCommand(owner, EntryType.MEAL, SourceKind.TEXT,
                Map.of(), occurredAt, payload, Map.of(), new TelegramUpdateKey("analytics", update))).entry();
        return entries.confirm(new ConfirmEntryCommand(owner, draft.id(), "meal-" + update, draft.revision()));
    }

    private Entry confirmMetric(OwnerContext owner, long update, String code, int value, String unit,
                                String date, String time, String qualifier, Instant occurredAt) {
        Map<String, Object> payload = new java.util.LinkedHashMap<>();
        payload.put("code", code);
        payload.put("value", value);
        payload.put("unit", unit);
        payload.put("local_date", date);
        if (time != null) payload.put("local_time", time);
        if (qualifier != null) payload.put("qualifier", qualifier);
        Entry draft = entries.createDraft(new CreateDraftCommand(owner, EntryType.METRICS, SourceKind.TEXT,
                Map.of(), occurredAt, payload, Map.of(), new TelegramUpdateKey("analytics", update))).entry();
        return entries.confirm(new ConfirmEntryCommand(owner, draft.id(), "metric-" + update, draft.revision()));
    }

    @TestConfiguration
    static class FixedClockConfiguration {
        @Bean
        @Primary
        Clock fixedClock() {
            return Clock.fixed(NOW, ZoneOffset.UTC);
        }
    }
}
