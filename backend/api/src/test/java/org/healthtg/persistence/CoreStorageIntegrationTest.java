package org.healthtg.persistence;

import com.mongodb.client.MongoClient;
import com.mongodb.client.MongoClients;

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
import org.healthtg.core.entry.EntryStore;
import org.healthtg.core.entry.EntryType;
import org.healthtg.core.entry.ListConfirmedEntriesQuery;
import org.healthtg.core.entry.OwnerContext;
import org.healthtg.core.entry.SourceKind;
import org.healthtg.core.entry.TelegramUpdateKey;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.AnnotationConfigApplicationContext;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.ComponentScan;
import org.springframework.data.mongodb.core.MongoTemplate;
import org.springframework.data.mongodb.repository.config.EnableMongoRepositories;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.containers.MongoDBContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.utility.DockerImageName;

import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
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
    private static String restartDatabaseName;

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
    @Autowired EntryStore entryStore;
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
    void storesAllCheckinCategoriesAtAcceptedScoreBoundaries() {
        OwnerContext owner = new OwnerContext(UUID.randomUUID());
        int updateId = 600;
        for (CheckinCategory category : CheckinCategory.values()) {
            int score = updateId % 2 == 0 ? 1 : 5;
            Entry saved = entries.createCheckin(new CreateCheckinCommand(owner, category, score,
                    Instant.parse("2026-09-23T08:00:00Z"), new TelegramUpdateKey("main", updateId++)));
            assertEquals(category.code(), saved.payload().get("category"));
            assertEquals(score, saved.payload().get("score"));
            assertEquals(EntryStatus.CONFIRMED, saved.status());
        }
    }

    @Test
    void keepsOneActiveDraftAndDeduplicatesSubmissionId() {
        OwnerContext owner = new OwnerContext(UUID.randomUUID());
        CreateDraftCommand first = draft(owner, 10, "submission-1", metrics("steps", 1000));
        DraftCreationResult created = entries.createDraft(first);
        DraftCreationResult repeated = entries.createDraft(
                draft(owner, 11, "submission-1", metrics("steps", 9000)));
        DraftCreationResult another = entries.createDraft(
                draft(owner, 12, "submission-2", metrics("steps", 2000)));

        assertEquals(DraftCreationResult.Outcome.CREATED, created.outcome());
        assertEquals(DraftCreationResult.Outcome.EXISTING_UPDATE, repeated.outcome());
        assertEquals(created.entry().id(), repeated.entry().id());
        assertEquals(DraftCreationResult.Outcome.ACTIVE_DRAFT_EXISTS, another.outcome());
        assertEquals(created.entry().id(), another.entry().id());
        assertEquals(created.entry(), another.entry());
        assertEquals(created.entry(), entries.findActiveDraft(owner).orElseThrow());
        assertEquals(1, mongoTemplate.getCollection("entries").countDocuments());
    }

    @Test
    void concurrentSubmissionIdReturnsOneDraft() throws Exception {
        OwnerContext owner = new OwnerContext(UUID.randomUUID());
        List<Callable<DraftCreationResult>> calls = new ArrayList<>();
        for (int index = 0; index < 8; index++) {
            int updateId = 700 + index;
            calls.add(() -> entries.createDraft(
                    draft(owner, updateId, "same-submission", metrics("steps", 1000))));
        }

        try (var executor = Executors.newFixedThreadPool(8)) {
            var ids = new HashSet<UUID>();
            for (var result : executor.invokeAll(calls)) ids.add(result.get().entry().id());
            assertEquals(1, ids.size());
        }
        assertEquals(1, mongoTemplate.getCollection("entries").countDocuments());
    }

    @Test
    void allowsIncompleteMetricsDraftButRejectsInvalidNumbers() {
        OwnerContext owner = new OwnerContext(UUID.randomUUID());
        Entry incomplete = entries.createDraft(draft(owner, 20, null, metrics("heart_rate", 72.4))).entry();
        assertFalse(incomplete.payload().containsKey("local_date"));
        assertFalse(incomplete.payload().containsKey("unit"));

        OwnerContext secondOwner = new OwnerContext(UUID.randomUUID());
        assertThrows(IllegalArgumentException.class,
                () -> entries.createDraft(draft(secondOwner, 21, null, metrics("steps", -1))));
        assertThrows(IllegalArgumentException.class,
                () -> entries.createDraft(draft(secondOwner, 22, null, metrics("heart_rate", Double.NaN))));
        assertThrows(IllegalArgumentException.class,
                () -> entries.createDraft(
                        draft(secondOwner, 23, null, metrics("heart_rate", Double.POSITIVE_INFINITY))));
        assertThrows(IllegalArgumentException.class,
                () -> entries.createDraft(draft(secondOwner, 24, null, metrics("sleep_duration_min", -1))));
        assertThrows(IllegalArgumentException.class,
                () -> entries.createDraft(draft(secondOwner, 25, null, Map.of("steps", 1000))));

        CreateDraftCommand negativeMeal = new CreateDraftCommand(secondOwner, EntryType.MEAL, SourceKind.TEXT,
                Map.of(), Instant.parse("2026-09-23T08:00:00Z"),
                Map.of("description", "meal", "mass_g", -1), Map.of(), null,
                new TelegramUpdateKey("main", 26));
        assertThrows(IllegalArgumentException.class, () -> entries.createDraft(negativeMeal));

        Map<String, Object> nullBasisPayload = new LinkedHashMap<>();
        nullBasisPayload.put("description", "meal");
        nullBasisPayload.put("nutrients_basis", null);
        CreateDraftCommand nullBasis = new CreateDraftCommand(secondOwner, EntryType.MEAL, SourceKind.TEXT,
                Map.of(), Instant.parse("2026-09-23T08:00:00Z"), nullBasisPayload, Map.of(), null,
                new TelegramUpdateKey("main", 27));
        assertThrows(IllegalArgumentException.class, () -> entries.createDraft(nullBasis));

        OwnerContext thirdOwner = new OwnerContext(UUID.randomUUID());
        CreateDraftCommand omittedBasis = new CreateDraftCommand(thirdOwner, EntryType.MEAL, SourceKind.TEXT,
                Map.of(), Instant.parse("2026-09-23T08:00:00Z"), Map.of("description", "meal"), Map.of(), null,
                new TelegramUpdateKey("main", 28));
        assertFalse(entries.createDraft(omittedBasis).entry().payload().containsKey("nutrients_basis"));
    }

    @Test
    void scopesDraftsByOwnerAndRejectsCrossOwnerUpdateCollision() {
        OwnerContext first = new OwnerContext(UUID.randomUUID());
        OwnerContext second = new OwnerContext(UUID.randomUUID());
        entries.createDraft(draft(first, 30, null, metrics("steps", 1000)));

        assertTrue(entries.findActiveDraft(first).isPresent());
        assertTrue(entries.findActiveDraft(second).isEmpty());
        assertThrows(IllegalStateException.class,
                () -> entries.createDraft(draft(second, 30, null, metrics("steps", 2000))));
    }

    @Test
    void restoresDialogStateAndMakesRepeatedUpdateIdempotent() {
        OwnerContext owner = new OwnerContext(UUID.randomUUID());
        SaveDialogStateCommand first = new SaveDialogStateCommand(owner, null, "awaiting_weight",
                Map.of("unit", "kg"), new TelegramUpdateKey("main", 50));
        DialogState saved = dialogs.save(first);
        DialogState repeated = dialogs.save(new SaveDialogStateCommand(owner, null, "wrong_step", Map.of(),
                new TelegramUpdateKey("main", 50)));

        assertEquals(saved, repeated);
        assertEquals(saved, dialogs.find(owner).orElseThrow());
        assertEquals(1, saved.revision());

        DialogState advanced = dialogs.save(new SaveDialogStateCommand(owner, null, "awaiting_date",
                Map.of("unit", "kg"), new TelegramUpdateKey("main", 51)));
        assertEquals(2, advanced.revision());
        assertEquals("awaiting_date", dialogs.find(owner).orElseThrow().step());
    }

    @Test
    void restoresDraftAndDialogStateAfterApplicationContextRestart() {
        restartDatabaseName = "restart_" + UUID.randomUUID().toString().replace("-", "");
        OwnerContext owner = new OwnerContext(UUID.randomUUID());
        Entry savedDraft;
        DialogState savedDialog;

        try (AnnotationConfigApplicationContext first = restartContext()) {
            EntryCoreService firstEntries = first.getBean(EntryCoreService.class);
            DialogStateService firstDialogs = first.getBean(DialogStateService.class);
            savedDraft = firstEntries.createDraft(draft(owner, 60, "restart-submission", metrics("steps", 1234)))
                    .entry();
            savedDialog = firstDialogs.save(new SaveDialogStateCommand(owner, savedDraft.id(), "awaiting_unit",
                    Map.of("metric", "steps"), new TelegramUpdateKey("main", 61)));
        }

        try (AnnotationConfigApplicationContext second = restartContext()) {
            EntryCoreService secondEntries = second.getBean(EntryCoreService.class);
            DialogStateService secondDialogs = second.getBean(DialogStateService.class);
            assertEquals(savedDraft, secondEntries.findActiveDraft(owner).orElseThrow());
            assertEquals(savedDialog, secondDialogs.find(owner).orElseThrow());
            second.getBean(MongoTemplate.class).getDb().drop();
        }
    }

    @Test
    void oldTelegramUpdateCannotRollBackNewerDialogState() {
        OwnerContext owner = new OwnerContext(UUID.randomUUID());
        dialogs.save(new SaveDialogStateCommand(owner, null, "awaiting_score", Map.of(),
                new TelegramUpdateKey("main", 201)));
        DialogState completed = dialogs.save(new SaveDialogStateCommand(owner, null, "completed", Map.of(),
                new TelegramUpdateKey("main", 202)));

        DialogState repeatedOld = dialogs.save(new SaveDialogStateCommand(owner, null, "awaiting_score", Map.of(),
                new TelegramUpdateKey("main", 201)));

        assertEquals(completed, repeatedOld);
        assertEquals("completed", dialogs.find(owner).orElseThrow().step());
        assertEquals(2, dialogs.find(owner).orElseThrow().revision());
    }

    @Test
    void legacyDialogStateRejectsOlderUpdateAndMigratesHighWaterMark() {
        OwnerContext owner = new OwnerContext(UUID.randomUUID());
        mongoTemplate.getCollection("dialog_states").insertOne(new org.bson.Document()
                .append("_id", owner.userId().toString())
                .append("activeEntryId", null)
                .append("step", "completed")
                .append("context", new org.bson.Document())
                .append("revision", 2L)
                .append("updatedAt", java.util.Date.from(Instant.parse("2026-09-23T08:00:00Z")))
                .append("telegramUpdateKey", "review:202")
                .append("mongoVersion", 0L));

        DialogState repeatedOld = dialogs.save(new SaveDialogStateCommand(owner, null, "awaiting_score", Map.of(),
                new TelegramUpdateKey("review", 201)));
        assertEquals("completed", repeatedOld.step());
        assertEquals(2, repeatedOld.revision());

        DialogState advanced = dialogs.save(new SaveDialogStateCommand(owner, null, "next", Map.of(),
                new TelegramUpdateKey("review", 203)));
        assertEquals("next", advanced.step());
        assertEquals(3, advanced.revision());
        assertEquals(203L, mongoTemplate.getCollection("dialog_states")
                .find(new org.bson.Document("_id", owner.userId().toString()))
                .first().get("processedUpdateIds", org.bson.Document.class).getLong("review"));
    }

    @Test
    void rejectsMissingAndForeignActiveEntries() {
        OwnerContext first = new OwnerContext(UUID.randomUUID());
        OwnerContext second = new OwnerContext(UUID.randomUUID());
        Entry secondDraft = entries.createDraft(
                draft(second, 301, null, metrics("steps", 1000))).entry();

        assertThrows(IllegalArgumentException.class, () -> dialogs.save(new SaveDialogStateCommand(
                first, UUID.randomUUID(), "awaiting_value", Map.of(), new TelegramUpdateKey("main", 302))));
        assertThrows(IllegalStateException.class, () -> dialogs.save(new SaveDialogStateCommand(
                first, secondDraft.id(), "awaiting_value", Map.of(), new TelegramUpdateKey("main", 303))));
        assertTrue(dialogs.find(first).isEmpty());
    }

    @Test
    void concurrentFirstDialogSaveIsIdempotent() throws Exception {
        OwnerContext owner = new OwnerContext(UUID.randomUUID());
        SaveDialogStateCommand command = new SaveDialogStateCommand(owner, null, "awaiting_score", Map.of(),
                new TelegramUpdateKey("main", 401));
        List<Callable<DialogState>> calls = new ArrayList<>();
        for (int index = 0; index < 8; index++) calls.add(() -> dialogs.save(command));

        try (var executor = Executors.newFixedThreadPool(8)) {
            var states = executor.invokeAll(calls);
            for (var state : states) {
                assertEquals(1, state.get().revision());
                assertEquals("awaiting_score", state.get().step());
            }
        }

        assertEquals(1, mongoTemplate.getCollection("dialog_states").countDocuments());
    }

    @Test
    void listsOnlyOwnedConfirmedEntriesInInclusiveLocalPeriod() {
        OwnerContext owner = new OwnerContext(UUID.randomUUID());
        OwnerContext anotherOwner = new OwnerContext(UUID.randomUUID());
        ZoneId warsaw = ZoneId.of("Europe/Warsaw");

        Entry boundary = entries.createCheckin(new CreateCheckinCommand(owner, CheckinCategory.MOOD, 4,
                Instant.parse("2026-09-18T22:00:00Z"), new TelegramUpdateKey("main", 501)));
        entries.createCheckin(new CreateCheckinCommand(owner, CheckinCategory.MOOD, 2,
                Instant.parse("2026-09-18T21:59:59Z"), new TelegramUpdateKey("main", 502)));
        entries.createCheckin(new CreateCheckinCommand(anotherOwner, CheckinCategory.MOOD, 5,
                Instant.parse("2026-09-19T08:00:00Z"), new TelegramUpdateKey("main", 503)));
        entries.createDraft(draft(owner, 504, null, metrics("steps", 1000)));

        entryStore.save(stored(owner, EntryStatus.CANCELLED, EntryType.NOTE, "cancelled-505",
                Instant.parse("2026-09-19T09:00:00Z"), Map.of("text", "cancelled")));
        entryStore.save(stored(owner, EntryStatus.DELETED, EntryType.NOTE, "deleted-506",
                Instant.parse("2026-09-19T10:00:00Z"), Map.of("text", "deleted")));
        entryStore.save(stored(owner, EntryStatus.CONFIRMED, EntryType.METRICS,
                "metric-507", Instant.parse("2026-09-10T10:00:00Z"),
                Map.of("code", "steps", "value", 3000, "unit", "count", "local_date", "2026-09-19")));

        List<Entry> allTypes = entries.listConfirmedEntries(new ListConfirmedEntriesQuery(owner,
                LocalDate.of(2026, 9, 19), LocalDate.of(2026, 9, 19), warsaw, Set.of()));

        assertEquals(List.of(boundary.id()), allTypes.stream().map(Entry::id).toList());
        assertTrue(allTypes.stream().allMatch(entry -> entry.ownerId().equals(owner.userId())));
        assertTrue(allTypes.stream().allMatch(entry -> entry.status() == EntryStatus.CONFIRMED));

        List<Entry> checkins = entries.listConfirmedEntries(new ListConfirmedEntriesQuery(owner,
                LocalDate.of(2026, 9, 19), LocalDate.of(2026, 9, 19), warsaw, Set.of(EntryType.CHECKIN)));
        assertEquals(List.of(boundary.id()), checkins.stream().map(Entry::id).toList());
    }

    private static CreateDraftCommand draft(OwnerContext owner, long updateId, String submissionId,
                                             Map<String, Object> payload) {
        return new CreateDraftCommand(owner, EntryType.METRICS, SourceKind.TEXT, Map.of(),
                Instant.parse("2026-09-23T08:00:00Z"), payload, Map.of(), submissionId,
                new TelegramUpdateKey("main", updateId));
    }

    private static Map<String, Object> metrics(String code, Number value) {
        return Map.of("code", code, "value", value);
    }

    private static Entry stored(OwnerContext owner, EntryStatus status, EntryType type, String updateKey,
                                Instant occurredAt, Map<String, Object> payload) {
        Instant persistedAt = Instant.parse("2026-09-23T08:00:00Z");
        return new Entry(UUID.randomUUID(), owner.userId(), type, status, SourceKind.TEXT, Map.of(), occurredAt,
                persistedAt, persistedAt, 1, payload, Map.of(), null, updateKey);
    }

    private static AnnotationConfigApplicationContext restartContext() {
        return new AnnotationConfigApplicationContext(RestartStorageConfiguration.class);
    }

    @TestConfiguration(proxyBeanMethods = false)
    @ComponentScan(basePackages = {"org.healthtg.core.entry", "org.healthtg.core.dialog"})
    @EnableMongoRepositories(basePackages = {"org.healthtg.core.entry", "org.healthtg.core.dialog"})
    static class RestartStorageConfiguration {
        @Bean
        MongoClient mongoClient() {
            return MongoClients.create(MONGO.getReplicaSetUrl());
        }

        @Bean
        MongoTemplate mongoTemplate(MongoClient mongoClient) {
            return new MongoTemplate(mongoClient, restartDatabaseName);
        }

        @Bean
        Clock clock() {
            return Clock.systemUTC();
        }
    }
}
