package org.healthtg.persistence;

import org.healthtg.session.SessionRecord;
import org.healthtg.session.SessionStore;
import org.healthtg.user.UserAccount;
import org.healthtg.user.UserService;
import org.healthtg.user.UserStore;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.data.mongodb.core.MongoTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.containers.MongoDBContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.utility.DockerImageName;

import java.time.Instant;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.Callable;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

@SpringBootTest
@Testcontainers(disabledWithoutDocker = true)
class MongoPersistenceIntegrationTest {
    @Container
    static final MongoDBContainer MONGO = new MongoDBContainer(
            DockerImageName.parse("mongodb/mongodb-community-server:8.0-ubi9-slim")
                    .asCompatibleSubstituteFor("mongo"));

    @DynamicPropertySource
    static void mongoProperties(DynamicPropertyRegistry registry) {
        registry.add("spring.data.mongodb.uri", MONGO::getReplicaSetUrl);
        registry.add("spring.data.mongodb.auto-index-creation", () -> "true");
    }

    @Autowired UserStore userStore;
    @Autowired UserService userService;
    @Autowired SessionStore sessionStore;
    @Autowired MongoTemplate mongoTemplate;

    @BeforeEach
    void clearCollections() {
        mongoTemplate.getDb().getCollection("sessions").deleteMany(new org.bson.Document());
        mongoTemplate.getDb().getCollection("users").deleteMany(new org.bson.Document());
    }

    @Test
    void persistsUserAndHasUniqueTelegramIndex() {
        UserAccount user = new UserAccount(UUID.randomUUID(), 10001,
                ZoneId.of("Europe/Warsaw"), true);
        userStore.save(user);

        assertEquals(user, userStore.findByTelegramId(10001).orElseThrow());
        assertTrue(mongoTemplate.indexOps("users").getIndexInfo().stream()
                .anyMatch(index -> index.isUnique() && index.getIndexFields().stream()
                        .anyMatch(field -> field.getKey().equals("telegramId"))));
    }

    @Test
    void persistsOnlySessionHashAndHasTtlIndex() {
        UUID userId = UUID.randomUUID();
        SessionRecord session = new SessionRecord("sha256-hash", userId,
                Instant.parse("2026-09-22T12:00:00Z"), Instant.parse("2026-09-22T13:00:00Z"));
        sessionStore.save(session);

        assertEquals(session, sessionStore.findByTokenHash("sha256-hash").orElseThrow());
        org.bson.Document stored = mongoTemplate.getDb().getCollection("sessions")
                .find(new org.bson.Document("_id", "sha256-hash")).first();
        assertFalse(stored == null || stored.containsKey("token"));
        assertTrue(mongoTemplate.indexOps("sessions").getIndexInfo().stream()
                .anyMatch(index -> index.getExpireAfter() != null && index.getIndexFields().stream()
                        .anyMatch(field -> field.getKey().equals("expiresAt"))));
    }

    @Test
    void concurrentFirstLoginCreatesOneUser() throws Exception {
        int workers = 12;
        CountDownLatch ready = new CountDownLatch(workers);
        CountDownLatch start = new CountDownLatch(1);
        List<Callable<UserAccount>> calls = new ArrayList<>();
        for (int index = 0; index < workers; index++) {
            calls.add(() -> {
                ready.countDown();
                start.await();
                return userService.findOrCreate(10001);
            });
        }

        try (var executor = Executors.newFixedThreadPool(workers)) {
            var futures = calls.stream().map(executor::submit).toList();
            ready.await();
            start.countDown();
            var userIds = new HashSet<UUID>();
            for (var future : futures) userIds.add(future.get().id());

            assertEquals(1, userIds.size());
            assertEquals(1, mongoTemplate.getCollection("users")
                    .countDocuments(new org.bson.Document("telegramId", 10001L)));
        }
    }
}
