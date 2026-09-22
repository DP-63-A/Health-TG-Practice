package org.healthtg.web;

import com.mongodb.client.MongoDatabase;
import org.bson.Document;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.data.mongodb.core.MongoTemplate;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class HealthControllerTest {
    @Test
    void reportsReadyOnlyWhenMongoResponds() {
        MongoTemplate template = mock(MongoTemplate.class);
        MongoDatabase database = mock(MongoDatabase.class);
        when(template.getDb()).thenReturn(database);
        when(database.runCommand(any(Document.class))).thenReturn(new Document("ok", 1.0));

        ResponseEntity<HealthController.HealthResponse> response = new HealthController(template).health();

        assertEquals(HttpStatus.OK, response.getStatusCode());
        assertEquals("ok", response.getBody().status());
        assertEquals("ok", response.getBody().checks().get("mongo"));
    }

    @Test
    void reportsDegradedWithoutLeakingFailureDetails() {
        MongoTemplate template = mock(MongoTemplate.class);
        when(template.getDb()).thenThrow(new IllegalStateException("secret connection details"));

        ResponseEntity<HealthController.HealthResponse> response = new HealthController(template).health();

        assertEquals(HttpStatus.SERVICE_UNAVAILABLE, response.getStatusCode());
        assertEquals("degraded", response.getBody().status());
        assertEquals("fail", response.getBody().checks().get("mongo"));
    }
}
