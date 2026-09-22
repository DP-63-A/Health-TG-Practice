package org.healthtg.web;

import org.bson.Document;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.data.mongodb.core.MongoTemplate;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.Map;

@RestController
@RequestMapping("/api/v1")
public class HealthController {
    private final MongoTemplate mongoTemplate;

    public HealthController(MongoTemplate mongoTemplate) {
        this.mongoTemplate = mongoTemplate;
    }

    @GetMapping("/healthz")
    public ResponseEntity<HealthResponse> health() {
        try {
            Document result = mongoTemplate.getDb().runCommand(new Document("ping", 1));
            Object ok = result.get("ok");
            if (ok instanceof Number number && number.doubleValue() == 1.0) {
                return ResponseEntity.ok(new HealthResponse("ok", Map.of("mongo", "ok")));
            }
        } catch (RuntimeException ignored) {
            // Readiness responses intentionally contain no dependency details or secrets.
        }
        return ResponseEntity.status(HttpStatus.SERVICE_UNAVAILABLE)
                .body(new HealthResponse("degraded", Map.of("mongo", "fail")));
    }

    public record HealthResponse(String status, Map<String, String> checks) {
    }
}
