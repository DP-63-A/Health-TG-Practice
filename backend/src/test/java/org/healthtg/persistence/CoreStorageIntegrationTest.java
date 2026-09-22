package org.healthtg.persistence;

import org.healthtg.core.dialog.DialogState;
import org.healthtg.core.dialog.DialogStateService;
import org.healthtg.core.dialog.SaveDialogStateCommand;
import org.healthtg.core.entry.CheckinCategory;
import org.healthtg.core.entry.CreateCheckinCommand;
import org.healthtg.core.entry.CreateDraftCommand;
import org.healthtg.core.entry.DraftCreationResult;
import org.healthtg.core.entry.Entry;
import org.healthtg.core.entry.EntryCoreService;
import org.healthtg.core.entry.EntryStatus;
import org.healthtg.core.entry.EntryType;
import org.healthtg.core.entry.OwnerContext;
import org.healthtg.core.entry.SourceKind;
import org.healthtg.core.entry.TelegramUpdateKey;
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
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.Callable;
import java.util.concurrent.Executors;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

@SpringBootTest
@Testcontainers(disabledWithoutDocker = true)
class CoreStorageIntegrationTest {
    @Container
    static final MongoDBContainer MONGO = new MongoDBContainer(
            DockerImageName.parse("mongodb/mongodb-community-server:8.0-ubi9-slim")
                    .asCompatibleSubstituteFor("mongo"));

    @DynamicPropertySource
    static void mongoProperties(DynamicPropertyRegistry registry) {
        registry.add("spring.data.mongodb.uri", MONGO::getReplicaSetUrl);
        registry.add("spring.data.mongodb.auto-index-creation", () -> "true");
    }

    @Autowired EntryCoreService entries;
    @Autowired DialogStateService dialogs;
    @Autowired MongoTemplate mongoTemplate;

    @BeforeEach
    void clearCollections() {
        mongoTemplate.getDb().getCollection("entries").deleteMany(new org.bson.Document());
        mongoTemplate.getDb().getCollection("dialog_states").deleteMany(new org.bson.Document());
    }

    @Test
    void createsConfirmedCheckinAndDeduplicatesConcurrentTelegramDelivery() throws Exception {
        OwnerContext owner = new OwnerContext(UUID.randomUUID());
        CreateCheckinCommand command = new CreateCheckinCommand(owner, CheckinCategory.MOOD, 5,
                Instant.parse("2026-09-23T08:00:00Z"), new TelegramUpdateKey("main", 42));

        List<Callable<Entry>> calls = new ArrayList<>();
        for (int index = 0; index < 8; index++) calls.add(() -> entries.createCheckin(command));
        try (var executor = Executors.newFixedThreadPool(8)) {
            var ids = new HashSet<UUID>();
            for (var result : executor.invokeAll(calls)) ids.add(result.get().id());
            assertEquals(1, ids.size());
        }

        Entry saved = entries.createCheckin(command);
        assertEquals(EntryStatus.CONFIRMED, saved.status());
        assertEquals(Map.of("category", "mood", "score", 5), saved.payload());
        assertEquals(1, mongoTemplate.getCollection("entries").countDocuments());
    }

    @Test
    void rejectsInvalidCheckinBoundariesAndUnknownCategory() {
        OwnerContext owner = new OwnerContext(UUID.randomUUID());
        TelegramUpdateKey key = new TelegramUpdateKey("main", 1);
        assertThrows(IllegalArgumentException.class,
                () -> new CreateCheckinCommand(owner, CheckinCategory.MOOD, 0, Instant.now(), key));
        assertThrows(IllegalArgumentException.class,
                () -> new CreateCheckinCommand(owner, CheckinCategory.MOOD, 6, Instant.now(), key));
        assertThrows(IllegalArgumentException.class, () -> CheckinCategory.fromCode("unknown"));
    }

    @Test
    void keepsOneActiveDraftAndDeduplicatesSubmissionId() {
        OwnerContext owner = new OwnerContext(UUID.randomUUID());
        CreateDraftCommand first = draft(owner, 10, "submission-1", Map.of("steps", 1000));
        DraftCreationResult created = entries.createDraft(first);
        DraftCreationResult repeated = entries.createDraft(draft(owner, 11, "submission-1", Map.of("steps", 9000)));
        DraftCreationResult another = entries.createDraft(draft(owner, 12, "submission-2", Map.of("steps", 2000)));

        assertEquals(DraftCreationResult.Outcome.CREATED, created.outcome());
        assertEquals(DraftCreationResult.Outcome.EXISTING_UPDATE, repeated.outcome());
        assertEquals(created.entry().id(), repeated.entry().id());
        assertEquals(DraftCreationResult.Outcome.ACTIVE_DRAFT_EXISTS, another.outcome());
        assertEquals(created.entry().id(), another.entry().id());
        assertEquals(created.entry(), entries.findActiveDraft(owner).orElseThrow());
    }

    @Test
    void allowsIncompleteMetricsDraftButRejectsInvalidNumbers() {
        OwnerContext owner = new OwnerContext(UUID.randomUUID());
        Entry incomplete = entries.createDraft(draft(owner, 20, null, Map.of("weight", 72.4))).entry();
        assertFalse(incomplete.payload().containsKey("local_date"));
        assertFalse(incomplete.payload().containsKey("unit"));

        OwnerContext secondOwner = new OwnerContext(UUID.randomUUID());
        assertThrows(IllegalArgumentException.class,
                () -> entries.createDraft(draft(secondOwner, 21, null, Map.of("steps", -1))));
        assertThrows(IllegalArgumentException.class,
                () -> entries.createDraft(draft(secondOwner, 22, null, Map.of("weight", Double.NaN))));
    }

    @Test
    void scopesDraftsByOwnerAndRejectsCrossOwnerUpdateCollision() {
        OwnerContext first = new OwnerContext(UUID.randomUUID());
        OwnerContext second = new OwnerContext(UUID.randomUUID());
        entries.createDraft(draft(first, 30, null, Map.of("text", "private")));

        assertTrue(entries.findActiveDraft(first).isPresent());
        assertTrue(entries.findActiveDraft(second).isEmpty());
        assertThrows(IllegalStateException.class,
                () -> entries.createDraft(draft(second, 30, null, Map.of("text", "collision"))));
    }

    @Test
    void restoresDialogStateAndMakesRepeatedUpdateIdempotent() {
        OwnerContext owner = new OwnerContext(UUID.randomUUID());
        UUID draftId = UUID.randomUUID();
        SaveDialogStateCommand first = new SaveDialogStateCommand(owner, draftId, "awaiting_weight",
                Map.of("unit", "kg"), new TelegramUpdateKey("main", 50));
        DialogState saved = dialogs.save(first);
        DialogState repeated = dialogs.save(new SaveDialogStateCommand(owner, null, "wrong_step", Map.of(),
                new TelegramUpdateKey("main", 50)));

        assertEquals(saved, repeated);
        assertEquals(saved, dialogs.find(owner).orElseThrow());
        assertEquals(1, saved.revision());

        DialogState advanced = dialogs.save(new SaveDialogStateCommand(owner, draftId, "awaiting_date",
                Map.of("unit", "kg"), new TelegramUpdateKey("main", 51)));
        assertEquals(2, advanced.revision());
        assertEquals("awaiting_date", dialogs.find(owner).orElseThrow().step());
    }

    private static CreateDraftCommand draft(OwnerContext owner, long updateId, String submissionId,
                                             Map<String, Object> payload) {
        return new CreateDraftCommand(owner, EntryType.METRICS, SourceKind.TEXT, Map.of(),
                Instant.parse("2026-09-23T08:00:00Z"), payload, Map.of(), submissionId,
                new TelegramUpdateKey("main", updateId));
    }
}
