package org.healthtg.web;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.PropertyNamingStrategies;
import com.mongodb.client.MongoDatabase;
import org.bson.Document;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.data.mongodb.core.MongoTemplate;
import org.springframework.http.converter.json.MappingJackson2HttpMessageConverter;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

class HealthControllerTest {
    private MongoTemplate template;
    private MockMvc mockMvc;

    @BeforeEach
    void setUp() {
        template = mock(MongoTemplate.class);
        ObjectMapper objectMapper = new ObjectMapper()
                .setPropertyNamingStrategy(PropertyNamingStrategies.SNAKE_CASE);
        mockMvc = MockMvcBuilders.standaloneSetup(new HealthController(template))
                .addFilters(new RequestIdFilter())
                .setMessageConverters(new MappingJackson2HttpMessageConverter(objectMapper))
                .build();
    }

    @Test
    void reportsReadyOnlyWhenMongoResponds() throws Exception {
        MongoDatabase database = mock(MongoDatabase.class);
        when(template.getDb()).thenReturn(database);
        when(database.runCommand(any(Document.class))).thenReturn(new Document("ok", 1.0));

        mockMvc.perform(get("/api/v1/healthz"))
                .andExpect(status().isOk())
                .andExpect(header().exists(RequestIdFilter.HEADER))
                .andExpect(content().contentTypeCompatibleWith("application/json"))
                .andExpect(jsonPath("$.status").value("ok"))
                .andExpect(jsonPath("$.checks.mongo").value("ok"));
    }

    @Test
    void returnsContractErrorWithoutLeakingFailureDetails() throws Exception {
        when(template.getDb()).thenThrow(new IllegalStateException("secret connection details"));

        mockMvc.perform(get("/api/v1/healthz"))
                .andExpect(status().isServiceUnavailable())
                .andExpect(header().exists(RequestIdFilter.HEADER))
                .andExpect(content().contentTypeCompatibleWith("application/json"))
                .andExpect(jsonPath("$.code").value("SERVICE_UNAVAILABLE"))
                .andExpect(jsonPath("$.message").value("Required service is unavailable"))
                .andExpect(jsonPath("$.request_id").isNotEmpty())
                .andExpect(jsonPath("$.status").doesNotExist())
                .andExpect(content().string(org.hamcrest.Matchers.not(
                        org.hamcrest.Matchers.containsString("secret connection details"))));
    }
}
