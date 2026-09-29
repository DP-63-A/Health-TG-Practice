package org.healthtg.web;

import com.fasterxml.jackson.databind.JsonNode;
import com.mongodb.client.MongoDatabase;
import org.bson.Document;
import org.healthtg.session.SessionStore;
import org.healthtg.user.UserStore;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.context.ApplicationContext;
import org.springframework.data.mongodb.core.MongoTemplate;
import org.springframework.test.context.bean.override.mockito.MockitoBean;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT, properties = {
        "spring.autoconfigure.exclude=org.springframework.boot.autoconfigure.mongo.MongoAutoConfiguration,"
                + "org.springframework.boot.autoconfigure.data.mongo.MongoDataAutoConfiguration,"
                + "org.springframework.boot.autoconfigure.data.mongo.MongoRepositoriesAutoConfiguration",
        "health-tg.core.storage.enabled=false",
        "health-tg.auth.telegram-bot-token=",
        "health-tg.auth.allowed-telegram-ids="
})
class ApiApplicationIsolationTest {
    @MockitoBean UserStore userStore;
    @MockitoBean SessionStore sessionStore;
    @MockitoBean MongoTemplate mongoTemplate;
    @Autowired TestRestTemplate http;
    @Autowired ApplicationContext context;

    @Test
    void apiServesHttpWithoutBotConfigurationOrBotClasses() {
        assertThrows(ClassNotFoundException.class, () -> Class.forName("org.healthtg.bot.BotApplication"));
        assertThrows(ClassNotFoundException.class, () -> Class.forName("org.telegram.telegrambots.meta.generics.TelegramClient"));
        assertFalse(context.containsBean("botRuntime"));
        assertFalse(context.containsBean("runtimeSettings"));
        assertFalse(context.containsBean("entriesController"));
        var response = http.getForEntity("/api/v1/me", String.class);
        assertEquals(401, response.getStatusCode().value());
        assertNotNull(response.getHeaders().getFirst("X-Request-Id"));
        assertTrue(response.getBody().contains("UNAUTHORIZED"));
    }

    @Test
    void publicReadinessUsesMongoPingAndPreservesRequestIdOnFailure() {
        var database = mock(MongoDatabase.class);
        when(mongoTemplate.getDb()).thenReturn(database);
        when(database.runCommand(any(Document.class)))
                .thenReturn(new Document("ok", 1.0))
                .thenThrow(new IllegalStateException("synthetic-private-connection-details"));

        var ready = http.getForEntity("/api/v1/healthz", JsonNode.class);
        assertEquals(200, ready.getStatusCode().value());
        assertEquals("ok", ready.getBody().path("status").asText());
        assertEquals("ok", ready.getBody().path("checks").path("mongo").asText());
        assertNotNull(ready.getHeaders().getFirst(RequestIdFilter.HEADER));

        var unavailable = http.getForEntity("/api/v1/healthz", JsonNode.class);
        assertEquals(503, unavailable.getStatusCode().value());
        var body = unavailable.getBody();
        assertEquals("SERVICE_UNAVAILABLE", body.path("code").asText());
        assertEquals("Required service is unavailable", body.path("message").asText());
        var requestId = unavailable.getHeaders().getFirst(RequestIdFilter.HEADER);
        assertNotNull(requestId);
        assertFalse(requestId.isBlank());
        assertEquals(requestId, body.path("request_id").asText());
        assertFalse(body.toString().contains("synthetic-private-connection-details"));
        verify(database, times(2)).runCommand(new Document("ping", 1));
    }
}
