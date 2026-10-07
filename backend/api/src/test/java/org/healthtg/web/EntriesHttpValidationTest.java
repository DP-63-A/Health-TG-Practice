package org.healthtg.web;

import org.healthtg.core.entry.Entry;
import org.healthtg.core.entry.EntryCoreService;
import org.healthtg.core.entry.EntryStatus;
import org.healthtg.core.entry.EntryType;
import org.healthtg.core.entry.EntryValidationException;
import org.healthtg.core.entry.PatchEntryCommand;
import org.healthtg.core.entry.SourceKind;
import org.healthtg.session.SessionService;
import org.healthtg.user.UserService;
import org.hamcrest.Matchers;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.mockito.Mockito;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.argThat;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest(properties = "spring.data.mongodb.auto-index-creation=false")
@AutoConfigureMockMvc
class EntriesHttpValidationTest {
    private static final UUID OWNER = UUID.randomUUID();
    private static final UUID ENTRY = UUID.randomUUID();

    @Autowired MockMvc mockMvc;
    @Autowired com.fasterxml.jackson.databind.ObjectMapper objectMapper;
    @MockitoBean SessionService sessions;
    @MockitoBean UserService users;
    @MockitoBean EntryCoreService entries;

    @BeforeEach
    void authenticate() {
        when(sessions.authenticate("test-session")).thenReturn(OWNER);
        when(entries.patch(any())).thenReturn(entryWithNullMass());
    }

    @Test
    void invalidLimitsReturnContractValidationError() throws Exception {
        for (int limit : new int[]{0, 101}) {
            mockMvc.perform(get("/api/v1/entries").queryParam("limit", Integer.toString(limit))
                            .header("Authorization", "Bearer test-session"))
                    .andExpect(status().isUnprocessableEntity())
                    .andExpect(jsonPath("$.code").value("VALIDATION_ERROR"));
        }
    }

    @Test
    void nullableMassPatchSucceedsOverHttp() throws Exception {
        mockMvc.perform(patch("/api/v1/entries/{id}", ENTRY)
                        .header("Authorization", "Bearer test-session")
                        .contentType("application/json")
                        .content("{\"expected_revision\":1,\"payload\":{\"mass_g\":null}}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.payload.mass_g").value(Matchers.nullValue()));
    }

    @Test
    void nullFieldOriginReturnsValidationError() throws Exception {
        when(entries.patch(argThat(command -> command.fieldOrigins() != null
                && command.fieldOrigins().containsKey("mass_g")
                && command.fieldOrigins().get("mass_g") == null)))
                .thenThrow(new EntryValidationException("Unknown field origin"));

        mockMvc.perform(patch("/api/v1/entries/{id}", ENTRY)
                        .header("Authorization", "Bearer test-session")
                        .contentType("application/json")
                        .content("{\"expected_revision\":1,\"field_origins\":{\"mass_g\":null}}"))
                .andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.code").value("VALIDATION_ERROR"));
    }

    @Test
    void payloadDecimalsReachCoreExactlyWithoutChangingOtherJsonNumbers() throws Exception {
        mockMvc.perform(patch("/api/v1/entries/{id}", ENTRY)
                        .header("Authorization", "Bearer test-session").contentType("application/json")
                        .content("{\"expected_revision\":1,\"payload\":{\"value\":-1E-400,\"score\":3,\"nutrients\":{\"energy_kcal\":12.34567890123456789}}}"))
                .andExpect(status().isOk());
        var command = ArgumentCaptor.forClass(PatchEntryCommand.class);
        Mockito.verify(entries).patch(command.capture());
        assertEquals(new BigDecimal("-1E-400"), command.getValue().payload().get("value"));
        assertEquals(3, command.getValue().payload().get("score"));
        assertEquals(new BigDecimal("12.34567890123456789"),
                ((Map<?, ?>) command.getValue().payload().get("nutrients")).get("energy_kcal"));
        assertInstanceOf(Double.class, objectMapper.readValue("{\"value\":1.25}", Map.class).get("value"));
    }

    @Test
    void exponentOutsideBigDecimalRangeReturnsValidationErrorBeforeCore() throws Exception {
        for (String value : List.of("1E9999999999", "1E-9999999999")) {
            mockMvc.perform(patch("/api/v1/entries/{id}", ENTRY)
                            .header("Authorization", "Bearer test-session").contentType("application/json")
                            .content("{\"expected_revision\":1,\"payload\":{\"mass_g\":" + value + "}}"))
                    .andExpect(status().isUnprocessableEntity())
                    .andExpect(jsonPath("$.code").value("VALIDATION_ERROR"));
        }
        Mockito.verify(entries, Mockito.never()).patch(any());
    }

    @Test
    void patchEntry_metricsValueValidation() throws Exception {
        // Настраиваем мок сервиса на реальное поведение: сервис выбрасывает EntryValidationException при невалидном значении
        when(entries.patch(argThat(cmd -> cmd != null 
                && cmd.payload() != null 
                && cmd.payload().containsKey("value")
                && new BigDecimal(cmd.payload().get("value").toString()).compareTo(new BigDecimal("1000000000")) > 0)))
                .thenThrow(new EntryValidationException("value must not be greater than 1000000000"));

        // 1. Невалидное значение транслируется HTTP-слоем в 422 Unprocessable Entity
        String invalidPayload = """
                {
                  "expected_revision": 1,
                  "payload": {
                    "code": "steps",
                    "value": 1000000001
                  }
                }
                """;

        mockMvc.perform(patch("/api/v1/entries/{id}", ENTRY)
                        .header("Authorization", "Bearer test-session")
                        .contentType("application/json")
                        .content(invalidPayload))
                .andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.code").value("VALIDATION_ERROR"));

        // 2. Валидное граничное значение (1000000000) передается в сервис и возвращает 200 OK
        String validMaxPayload = """
                {
                  "expected_revision": 1,
                  "payload": {
                    "code": "steps",
                    "value": 1000000000
                  }
                }
                """;

        mockMvc.perform(patch("/api/v1/entries/{id}", ENTRY)
                        .header("Authorization", "Bearer test-session")
                        .contentType("application/json")
                        .content(validMaxPayload))
                .andExpect(status().isOk());

        // 3. Дробное значение (10.5) передается в сервис и возвращает 200 OK
        String validFractionalPayload = """
                {
                  "expected_revision": 1,
                  "payload": {
                    "code": "steps",
                    "value": 10.5
                  }
                }
                """;

        mockMvc.perform(patch("/api/v1/entries/{id}", ENTRY)
                        .header("Authorization", "Bearer test-session")
                        .contentType("application/json")
                        .content(validFractionalPayload))
                .andExpect(status().isOk());
    }
    
    private static Entry entryWithNullMass() {
        Instant now = Instant.parse("2026-09-23T08:00:00Z");
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("description", "meal");
        payload.put("mass_g", null);
        return new Entry(ENTRY, OWNER, EntryType.MEAL, EntryStatus.DRAFT, SourceKind.TEXT, Map.of(),
                now, now, now, 2, payload, Map.of(), null, "http:test");
    }
}
