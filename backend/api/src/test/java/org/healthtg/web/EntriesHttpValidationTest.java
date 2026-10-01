package org.healthtg.web;

import org.healthtg.core.entry.Entry;
import org.healthtg.core.entry.EntryCoreService;
import org.healthtg.core.entry.EntryStatus;
import org.healthtg.core.entry.EntryType;
import org.healthtg.core.entry.EntryValidationException;
import org.healthtg.core.entry.SourceKind;
import org.healthtg.session.SessionService;
import org.healthtg.user.UserService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.argThat;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest(properties = "spring.data.mongodb.auto-index-creation=false")
@AutoConfigureMockMvc
class EntriesHttpValidationTest {
    private static final UUID OWNER = UUID.randomUUID();
    private static final UUID ENTRY = UUID.randomUUID();

    @Autowired MockMvc mockMvc;
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
                .andExpect(jsonPath("$.payload.mass_g").value(org.hamcrest.Matchers.nullValue()));
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

    private static Entry entryWithNullMass() {
        Instant now = Instant.parse("2026-09-23T08:00:00Z");
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("description", "meal");
        payload.put("mass_g", null);
        return new Entry(ENTRY, OWNER, EntryType.MEAL, EntryStatus.DRAFT, SourceKind.TEXT, Map.of(),
                now, now, now, 2, payload, Map.of(), null, "http:test");
    }
}
