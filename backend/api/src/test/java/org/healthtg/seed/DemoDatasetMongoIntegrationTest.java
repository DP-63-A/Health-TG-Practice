package org.healthtg.seed;

import org.bson.Document;
import org.healthtg.core.entry.ConfirmEntryCommand;
import org.healthtg.core.entry.CreateDraftCommand;
import org.healthtg.core.entry.EntryCoreService;
import org.healthtg.core.entry.EntryType;
import org.healthtg.core.entry.OwnerContext;
import org.healthtg.core.entry.SourceKind;
import org.healthtg.core.entry.TelegramUpdateKey;
import org.healthtg.user.UserAccount;
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
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

@SpringBootTest
@Testcontainers(disabledWithoutDocker = true)
class DemoDatasetMongoIntegrationTest {
    private static final String DEMO_URI = "mongodb://localhost:27017/health_tg_demo";
    private static final LocalDate START = LocalDate.of(2026, 9, 1);
    private static final long SEED = 24680L;
    private static final UUID REGULAR_ID = UUID.fromString("11111111-1111-4111-8111-111111111101");
    private static final UUID IRREGULAR_ID = UUID.fromString("11111111-1111-4111-8111-111111111102");
    private static final UUID INCOMPLETE_ID = UUID.fromString("11111111-1111-4111-8111-111111111103");
    private static final UUID UNRELATED_ID = UUID.fromString("11111111-1111-4111-8111-111111111104");

    @Container
    static final MongoDBContainer MONGO = new MongoDBContainer(
            DockerImageName.parse("mongodb/mongodb-community-server:8.0-ubi9-slim")
                    .asCompatibleSubstituteFor("mongo"));

    @DynamicPropertySource
    static void mongoProperties(DynamicPropertyRegistry registry) {
        registry.add("spring.data.mongodb.uri", MONGO::getReplicaSetUrl);
        registry.add("spring.data.mongodb.auto-index-creation", () -> "true");
    }

    @Autowired DemoDatasetService dataset;
    @Autowired EntryCoreService entries;
    @Autowired UserStore users;
    @Autowired MongoTemplate mongoTemplate;

    @BeforeEach
    void clearCollections() {
        mongoTemplate.getCollection("entries").deleteMany(new Document());
        mongoTemplate.getCollection("users").deleteMany(new Document());
        users.save(new UserAccount(REGULAR_ID, 1L, ZoneId.of("Europe/Warsaw"), true));
        users.save(new UserAccount(IRREGULAR_ID, 2L, ZoneId.of("UTC"), true));
        users.save(new UserAccount(INCOMPLETE_ID, 3L, ZoneId.of("America/New_York"), true));
    }

    @Test
    void seedsIdempotentlyAndPreservesTheFullMongoContentOnRepeat() {
        DemoProfileOwners owners = owners();

        int expectedCount = dataset.seed("true", DEMO_URI, owners, SEED, START);
        List<Document> firstContents = demoEntries();
        int repeatedCount = dataset.seed("true", DEMO_URI, owners, SEED, START);
        List<Document> repeatedContents = demoEntries();

        assertEquals(expectedCount, repeatedCount);
        assertEquals(expectedCount, firstContents.size());
        assertEquals(firstContents, repeatedContents);
        assertEquals(firstContents.size(), firstContents.stream()
                .map(entry -> entry.getString("_id")).distinct().count());
        assertTrue(firstContents.stream().allMatch(entry ->
                SourceKind.SEED.code().equals(entry.getString("sourceKind"))
                        && DemoDatasetService.DATASET_MARKER.equals(
                        entry.get("sourceRef", Document.class).getString("demo_dataset"))
                        && entry.containsKey("ownerId")
                        && entry.containsKey("occurredAt")
                        && entry.get("payload") instanceof Document
                        && entry.get("fieldOrigins") instanceof Document));
    }

    @Test
    void resetRemovesOnlyAllowedBe305Records() {
        int seededCount = dataset.seed("true", DEMO_URI, owners(), SEED, START);
        UUID unrelatedEntry = createUnrelatedEntry();
        mongoTemplate.getCollection("entries").insertOne(new Document("_id", "unrelated-seed-entry")
                .append("sourceKind", SourceKind.SEED.code())
                .append("sourceRef", new Document("demo_dataset", DemoDatasetService.DATASET_MARKER)
                        .append("profile", "other")));

        long removed = dataset.reset("true", DEMO_URI);

        assertEquals(seededCount, removed);
        assertEquals(1, demoEntries().size());
        assertEquals(0, mongoTemplate.getCollection("entries").countDocuments(new Document(
                "sourceRef.demo_dataset", DemoDatasetService.DATASET_MARKER)
                .append("sourceRef.profile", new Document("$in", List.of("regular", "irregular", "incomplete")))));
        assertEquals(1, mongoTemplate.getCollection("entries").countDocuments(
                new Document("_id", unrelatedEntry.toString())));
        assertEquals(1, mongoTemplate.getCollection("entries").countDocuments(
                new Document("_id", "unrelated-seed-entry")));
    }

    private UUID createUnrelatedEntry() {
        OwnerContext owner = new OwnerContext(UNRELATED_ID);
        var draft = entries.createDraft(new CreateDraftCommand(owner, EntryType.NOTE, SourceKind.TEXT,
                Map.of("unrelated", true), Instant.parse("2026-09-01T12:00:00Z"),
                Map.of("text", "Unrelated record"), Map.of("text", "reported"),
                new TelegramUpdateKey("unrelated-integration", 1))).entry();
        return entries.confirm(new ConfirmEntryCommand(owner, draft.id(), "unrelated-submission",
                draft.revision())).id();
    }

    private List<Document> demoEntries() {
        return mongoTemplate.getCollection("entries")
                .find(new Document("sourceRef.demo_dataset", DemoDatasetService.DATASET_MARKER))
                .sort(new Document("_id", 1))
                .into(new ArrayList<>());
    }

    private static DemoProfileOwners owners() {
        return new DemoProfileOwners(REGULAR_ID, IRREGULAR_ID, INCOMPLETE_ID);
    }
}
