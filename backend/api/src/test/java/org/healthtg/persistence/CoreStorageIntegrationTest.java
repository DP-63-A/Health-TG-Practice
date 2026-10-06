package org.healthtg.persistence;

import org.healthtg.core.dialog.DialogState;
import org.healthtg.core.dialog.DialogStateService;
import org.healthtg.core.dialog.SaveDialogStateCommand;
import org.healthtg.core.entry.CheckinCategory;
import org.healthtg.core.entry.ConfirmEntryCommand;
import org.healthtg.core.entry.CreateCheckinCommand;
import org.healthtg.core.entry.CreateDraftCommand;
import org.healthtg.core.entry.DraftCreationResult;
import org.healthtg.core.entry.Entry;
import org.healthtg.core.entry.EntryCoreService;
import org.healthtg.core.entry.EntryStatus;
import org.healthtg.core.entry.EntryStore;
import org.healthtg.core.entry.EntryType;
import org.healthtg.core.entry.EntryStatusConflictException;
import org.healthtg.core.entry.EntryValidationException;
import org.healthtg.core.entry.ListConfirmedEntriesQuery;
import org.healthtg.core.entry.ListEntriesQuery;
import org.healthtg.core.entry.OwnerContext;
import org.healthtg.core.entry.PatchEntryCommand;
import org.healthtg.core.entry.SourceKind;
import org.healthtg.core.entry.TelegramUpdateKey;
import org.healthtg.core.entry.EntryVersionConflictException;
import org.healthtg.core.entry.EntryNotFoundException;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.util.TestPropertyValues;
import org.springframework.context.annotation.AnnotationConfigApplicationContext;
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
    void keepsOneActiveDraftAndDeduplicatesTelegramUpdate() {
        OwnerContext owner = new OwnerContext(UUID.randomUUID());
        CreateDraftCommand first = draft(owner, 10, metrics("steps", 1000));
        DraftCreationResult created = entries.createDraft(first);
        DraftCreationResult repeated = entries.createDraft(
                draft(owner, 10, metrics("steps", 9000)));
        DraftCreationResult another = entries.createDraft(
                draft(owner, 12, metrics("steps", 2000)));

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
    void concurrentDraftCreationReturnsOneActiveDraft() throws Exception {
        OwnerContext owner = new OwnerContext(UUID.randomUUID());
        List<Callable<DraftCreationResult>> calls = new ArrayList<>();
        for (int index = 0; index < 8; index++) {
            int updateId = 700 + index;
            calls.add(() -> entries.createDraft(
                    draft(owner, updateId, metrics("steps", 1000))));
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
        Entry incomplete = entries.createDraft(draft(owner, 20, metrics("heart_rate", 72.4))).entry();
        assertFalse(incomplete.payload().containsKey("local_date"));
        assertFalse(incomplete.payload().containsKey("unit"));

        OwnerContext secondOwner = new OwnerContext(UUID.randomUUID());
        assertThrows(IllegalArgumentException.class,
                () -> entries.createDraft(draft(secondOwner, 21, metrics("steps", -1))));
        assertThrows(IllegalArgumentException.class,
                () -> entries.createDraft(draft(secondOwner, 22, metrics("heart_rate", Double.NaN))));
        assertThrows(IllegalArgumentException.class,
                () -> entries.createDraft(
                        draft(secondOwner, 23, metrics("heart_rate", Double.POSITIVE_INFINITY))));
        assertThrows(IllegalArgumentException.class,
                () -> entries.createDraft(draft(secondOwner, 24, metrics("sleep_duration_min", -1))));
        assertThrows(IllegalArgumentException.class,
                () -> entries.createDraft(draft(secondOwner, 25, Map.of("steps", 1000))));

        CreateDraftCommand negativeMeal = new CreateDraftCommand(secondOwner, EntryType.MEAL, SourceKind.TEXT,
                Map.of(), Instant.parse("2026-09-23T08:00:00Z"),
                Map.of("description", "meal", "mass_g", -1), Map.of(),
                new TelegramUpdateKey("main", 26));
        assertThrows(IllegalArgumentException.class, () -> entries.createDraft(negativeMeal));

        Map<String, Object> nullBasisPayload = new LinkedHashMap<>();
        nullBasisPayload.put("description", "meal");
        nullBasisPayload.put("nutrients_basis", null);
        CreateDraftCommand nullBasis = new CreateDraftCommand(secondOwner, EntryType.MEAL, SourceKind.TEXT,
                Map.of(), Instant.parse("2026-09-23T08:00:00Z"), nullBasisPayload, Map.of(),
                new TelegramUpdateKey("main", 27));
        assertThrows(IllegalArgumentException.class, () -> entries.createDraft(nullBasis));

        OwnerContext thirdOwner = new OwnerContext(UUID.randomUUID());
        CreateDraftCommand omittedBasis = new CreateDraftCommand(thirdOwner, EntryType.MEAL, SourceKind.TEXT,
                Map.of(), Instant.parse("2026-09-23T08:00:00Z"), Map.of("description", "meal"), Map.of(),
                new TelegramUpdateKey("main", 28));
        assertFalse(entries.createDraft(omittedBasis).entry().payload().containsKey("nutrients_basis"));
    }

    @Test
    void scopesDraftsByOwnerAndRejectsCrossOwnerUpdateCollision() {
        OwnerContext first = new OwnerContext(UUID.randomUUID());
        OwnerContext second = new OwnerContext(UUID.randomUUID());
        entries.createDraft(draft(first, 30, metrics("steps", 1000)));

        assertTrue(entries.findActiveDraft(first).isPresent());
        assertTrue(entries.findActiveDraft(second).isEmpty());
        assertThrows(IllegalStateException.class,
                () -> entries.createDraft(draft(second, 30, metrics("steps", 2000))));
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
        String restartDatabaseName = "restart_" + UUID.randomUUID().toString().replace("-", "");
        OwnerContext owner = new OwnerContext(UUID.randomUUID());
        Entry savedDraft;
        DialogState savedDialog;

        try (AnnotationConfigApplicationContext first = restartContext(restartDatabaseName)) {
            EntryCoreService firstEntries = first.getBean(EntryCoreService.class);
            DialogStateService firstDialogs = first.getBean(DialogStateService.class);
            savedDraft = firstEntries.createDraft(draft(owner, 60, metrics("steps", 1234)))
                    .entry();
            savedDialog = firstDialogs.save(new SaveDialogStateCommand(owner, savedDraft.id(), "awaiting_unit",
                    Map.of("metric", "steps"), new TelegramUpdateKey("main", 61)));
        }

        try (AnnotationConfigApplicationContext second = restartContext(restartDatabaseName)) {
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
                draft(second, 301, metrics("steps", 1000))).entry();

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
        entries.createDraft(draft(owner, 504, metrics("steps", 1000)));

        entryStore.save(stored(owner, EntryStatus.CANCELLED, EntryType.NOTE, "cancelled-505",
                Instant.parse("2026-09-19T09:00:00Z"), Map.of("text", "cancelled")));
        entryStore.save(stored(owner, EntryStatus.DELETED, EntryType.NOTE, "deleted-506",
                Instant.parse("2026-09-19T10:00:00Z"), Map.of("text", "deleted")));
        Entry steps = entryStore.save(stored(owner, EntryStatus.CONFIRMED, EntryType.METRICS,
                "metric-507", Instant.parse("2026-09-10T10:00:00Z"),
                Map.of("code", "steps", "value", 3000, "unit", "count", "local_date", "2026-09-19")));

        List<Entry> allTypes = entries.listConfirmedEntries(new ListConfirmedEntriesQuery(owner,
                LocalDate.of(2026, 9, 19), LocalDate.of(2026, 9, 19), warsaw, Set.of()));

        assertEquals(List.of(steps.id(), boundary.id()), allTypes.stream().map(Entry::id).toList());
        assertTrue(allTypes.stream().allMatch(entry -> entry.ownerId().equals(owner.userId())));
        assertTrue(allTypes.stream().allMatch(entry -> entry.status() == EntryStatus.CONFIRMED));

        List<Entry> checkins = entries.listConfirmedEntries(new ListConfirmedEntriesQuery(owner,
                LocalDate.of(2026, 9, 19), LocalDate.of(2026, 9, 19), warsaw, Set.of(EntryType.CHECKIN)));
        assertEquals(List.of(boundary.id()), checkins.stream().map(Entry::id).toList());
    }

    @Test
    void concurrentPatchUsesRevisionAndKeepsBoundedHistory() throws Exception {
        OwnerContext owner = new OwnerContext(UUID.randomUUID());
        Entry draft = entries.createDraft(draft(owner, 800, metrics("steps", 1000))).entry();
        PatchEntryCommand first = new PatchEntryCommand(owner, draft.id(), 1, null,
                Map.of("value", 2000), Map.of("value", "reported"));
        PatchEntryCommand second = new PatchEntryCommand(owner, draft.id(), 1, null,
                Map.of("value", 3000), Map.of("value", "reported"));

        try (var executor = Executors.newFixedThreadPool(2)) {
            var results = executor.invokeAll(List.of(() -> entries.patch(first), () -> entries.patch(second)));
            int successes = 0;
            int conflicts = 0;
            for (var result : results) {
                try {
                    result.get();
                    successes++;
                } catch (java.util.concurrent.ExecutionException exception) {
                    if (exception.getCause() instanceof EntryVersionConflictException) conflicts++;
                    else throw exception;
                }
            }
            assertEquals(1, successes);
            assertEquals(1, conflicts);
        }

        Entry current = entries.requireEntry(owner, draft.id());
        assertEquals(2, current.revision());
        for (int revision = 2; revision <= 12; revision++) {
            current = entries.patch(new PatchEntryCommand(owner, draft.id(), revision, null,
                    Map.of("value", 3000 + revision), Map.of("value", "reported")));
        }
        org.bson.Document stored = mongoTemplate.getCollection("entries")
                .find(new org.bson.Document("_id", draft.id().toString())).first();
        assertEquals(10, stored.getList("history", org.bson.Document.class).size());
        assertEquals(13, current.revision());
    }

    @Test
    void concurrentConfirmIsIdempotentAndTransitionsRemainExcludedFromDiary() throws Exception {
        OwnerContext owner = new OwnerContext(UUID.randomUUID());
        Map<String, Object> complete = new LinkedHashMap<>(metrics("steps", 1000));
        complete.put("unit", "count");
        complete.put("local_date", "2026-09-23");
        Entry draft = entries.createDraft(draft(owner, 900, complete)).entry();
        ConfirmEntryCommand command = new ConfirmEntryCommand(owner, draft.id(), "confirm-900", 1);

        try (var executor = Executors.newFixedThreadPool(8)) {
            List<Callable<Entry>> calls = new ArrayList<>();
            for (int i = 0; i < 8; i++) calls.add(() -> entries.confirm(command));
            Set<UUID> ids = new HashSet<>();
            for (var result : executor.invokeAll(calls)) ids.add(result.get().id());
            assertEquals(Set.of(draft.id()), ids);
        }
        Entry confirmed = entries.confirm(command);
        assertEquals(EntryStatus.CONFIRMED, confirmed.status());
        assertEquals(2, confirmed.revision());

        Entry deleted = entries.delete(owner, draft.id(), confirmed.revision());
        assertEquals(EntryStatus.DELETED, deleted.status());
        assertTrue(entries.listEntries(new ListEntriesQuery(owner, EntryStatus.CONFIRMED, null,
                null, null, ZoneId.of("UTC"))).isEmpty());

        OwnerContext secondOwner = new OwnerContext(UUID.randomUUID());
        Entry secondDraft = entries.createDraft(draft(secondOwner, 901, complete)).entry();
        Entry cancelled = entries.cancel(secondOwner, secondDraft.id(), secondDraft.revision());
        assertEquals(EntryStatus.CANCELLED, cancelled.status());
        assertEquals(cancelled, entries.cancel(secondOwner, secondDraft.id(), secondDraft.revision()));
        assertTrue(entries.findActiveDraft(secondOwner).isEmpty());
    }

    @Test
    void ownerIsolationAppliesToReadsAndMutations() {
        OwnerContext owner = new OwnerContext(UUID.randomUUID());
        OwnerContext stranger = new OwnerContext(UUID.randomUUID());
        Entry draft = entries.createDraft(draft(owner, 950, metrics("steps", 1000))).entry();

        assertThrows(EntryNotFoundException.class, () -> entries.requireEntry(stranger, draft.id()));
        assertThrows(EntryNotFoundException.class, () -> entries.patch(new PatchEntryCommand(
                stranger, draft.id(), draft.revision(), null, Map.of("value", 9999), null)));
        assertThrows(EntryNotFoundException.class, () -> entries.cancel(stranger, draft.id(), draft.revision()));
        assertThrows(EntryNotFoundException.class, () -> entries.confirm(new ConfirmEntryCommand(
                stranger, draft.id(), "secret-submission", draft.revision())));
        assertEquals(1000, entries.requireEntry(owner, draft.id()).payload().get("value"));
    }

    @Test
    void changingMealMassKeepsPerHundredGramNutrientsUnscaled() {
        OwnerContext owner = new OwnerContext(UUID.randomUUID());
        Map<String, Object> nutrients = Map.of("energy_kcal", 165, "protein_g", 8,
                "fat_g", 5, "carbs_g", 22);
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("description", "meal");
        payload.put("mass_g", 200);
        payload.put("nutrients", nutrients);
        payload.put("nutrients_basis", "per_100g");
        Entry draft = entries.createDraft(new CreateDraftCommand(owner, EntryType.MEAL, SourceKind.TEXT,
                Map.of(), Instant.parse("2026-09-23T08:00:00Z"), payload, Map.of(),
                new TelegramUpdateKey("main", 960))).entry();

        Entry changed = entries.patch(new PatchEntryCommand(owner, draft.id(), draft.revision(), null,
                Map.of("mass_g", 150), Map.of("mass_g", "reported")));

        assertEquals(150, changed.payload().get("mass_g"));
        assertEquals(nutrients, changed.payload().get("nutrients"));
        assertEquals("per_100g", changed.payload().get("nutrients_basis"));
    }

    @Test
    void partialNutrientPatchPreservesNestedFieldsForDraftAndConfirmedEntry() {
        OwnerContext owner = new OwnerContext(UUID.randomUUID());
        Map<String, Object> nutrients = Map.of("energy_kcal", 165, "protein_g", 8,
                "fat_g", 5, "carbs_g", 22);
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("description", "meal");
        payload.put("mass_g", 200);
        payload.put("nutrients", nutrients);
        payload.put("nutrients_basis", "per_100g");
        Entry draft = entries.createDraft(new CreateDraftCommand(owner, EntryType.MEAL, SourceKind.TEXT,
                Map.of(), Instant.parse("2026-09-23T08:00:00Z"), payload, Map.of(),
                new TelegramUpdateKey("main", 970))).entry();

        Entry patchedDraft = entries.patch(new PatchEntryCommand(owner, draft.id(), 1, null,
                Map.of("nutrients", Map.of("energy_kcal", 180)), null));
        assertEquals(Map.of("energy_kcal", 180, "protein_g", 8, "fat_g", 5, "carbs_g", 22),
                patchedDraft.payload().get("nutrients"));

        Entry confirmed = entries.confirm(new ConfirmEntryCommand(owner, draft.id(), "confirm-970", 2));
        Entry patchedConfirmed = entries.patch(new PatchEntryCommand(owner, draft.id(), confirmed.revision(), null,
                Map.of("nutrients", Map.of("energy_kcal", 190)), null));
        assertEquals(Map.of("energy_kcal", 190, "protein_g", 8, "fat_g", 5, "carbs_g", 22),
                patchedConfirmed.payload().get("nutrients"));
    }

    @Test
    void nullablePatchValuesRemainValidAndInvalidOriginsDoNotMutateEntry() {
        OwnerContext owner = new OwnerContext(UUID.randomUUID());
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("description", "meal");
        payload.put("mass_g", 200);
        Entry draft = entries.createDraft(new CreateDraftCommand(owner, EntryType.MEAL, SourceKind.TEXT,
                Map.of(), Instant.parse("2026-09-23T08:00:00Z"), payload, Map.of(),
                new TelegramUpdateKey("main", 980))).entry();

        Map<String, Object> clearMass = new LinkedHashMap<>();
        clearMass.put("mass_g", null);
        Entry cleared = entries.patch(new PatchEntryCommand(owner, draft.id(), 1, null, clearMass,
                Map.of("mass_g", "reported")));
        assertTrue(cleared.payload().containsKey("mass_g"));
        assertEquals(null, cleared.payload().get("mass_g"));

        assertThrows(IllegalArgumentException.class, () -> entries.patch(new PatchEntryCommand(
                owner, draft.id(), cleared.revision(), null, null, Map.of("mass_g", "made_up"))));
        assertEquals(cleared, entries.requireEntry(owner, draft.id()));

        Map<String, String> nullOrigin = new LinkedHashMap<>();
        nullOrigin.put("mass_g", null);
        assertThrows(EntryValidationException.class, () -> entries.patch(new PatchEntryCommand(
                owner, draft.id(), cleared.revision(), null, null, nullOrigin)));
        assertEquals(cleared, entries.requireEntry(owner, draft.id()));
    }

    @Test
    void staleCancelCannotUndoNewerPatch() {
        OwnerContext owner = new OwnerContext(UUID.randomUUID());
        Entry draft = entries.createDraft(draft(owner, 990, metrics("steps", 1000))).entry();
        Entry patched = entries.patch(new PatchEntryCommand(owner, draft.id(), 1, null,
                Map.of("value", 2000), Map.of("value", "reported")));

        assertThrows(EntryVersionConflictException.class, () -> entries.cancel(owner, draft.id(), 1));
        assertEquals(patched, entries.requireEntry(owner, draft.id()));
        Entry cancelled = entries.cancel(owner, draft.id(), 2);
        assertEquals(EntryStatus.CANCELLED, cancelled.status());
        assertThrows(EntryStatusConflictException.class, () -> entries.cancel(owner, draft.id(), 1));
    }

    @Test
    void concurrentCancelWithSameRevisionIsIdempotent() throws Exception {
        OwnerContext owner = new OwnerContext(UUID.randomUUID());
        Entry draft = entries.createDraft(draft(owner, 991, metrics("steps", 1000))).entry();
        List<Callable<Entry>> calls = new ArrayList<>();
        for (int index = 0; index < 8; index++) {
            calls.add(() -> entries.cancel(owner, draft.id(), draft.revision()));
        }

        try (var executor = Executors.newFixedThreadPool(8)) {
            Set<Entry> results = new HashSet<>();
            for (var result : executor.invokeAll(calls)) results.add(result.get());
            assertEquals(1, results.size());
            Entry cancelled = results.iterator().next();
            assertEquals(EntryStatus.CANCELLED, cancelled.status());
            assertEquals(draft.revision() + 1, cancelled.revision());
        }
    }

    @Test
    void sameSubmissionCannotConfirmTwoDifferentDraftsConcurrently() throws Exception {
        OwnerContext firstOwner = new OwnerContext(UUID.randomUUID());
        OwnerContext secondOwner = new OwnerContext(UUID.randomUUID());
        Map<String, Object> complete = new LinkedHashMap<>(metrics("steps", 1000));
        complete.put("unit", "count");
        complete.put("local_date", "2026-09-23");
        Entry first = entries.createDraft(draft(firstOwner, 1000, complete)).entry();
        Entry second = entries.createDraft(draft(secondOwner, 1001, complete)).entry();

        try (var executor = Executors.newFixedThreadPool(2)) {
            var results = executor.invokeAll(List.of(
                    () -> entries.confirm(new ConfirmEntryCommand(firstOwner, first.id(), "shared-submit", 1)),
                    () -> entries.confirm(new ConfirmEntryCommand(secondOwner, second.id(), "shared-submit", 1))));
            int confirmed = 0;
            int conflicts = 0;
            for (var result : results) {
                try {
                    result.get();
                    confirmed++;
                } catch (java.util.concurrent.ExecutionException exception) {
                    if (exception.getCause() instanceof org.healthtg.core.entry.EntryStatusConflictException) {
                        conflicts++;
                    } else {
                        throw exception;
                    }
                }
            }
            assertEquals(1, confirmed);
            assertEquals(1, conflicts);
        }
        assertEquals(1, mongoTemplate.getCollection("entries")
                .countDocuments(new org.bson.Document("submissionId", "shared-submit")));
    }

    @Test
    void stepsUseReportedDayForBothQueriesAndKeepUnknownDayOnlyInUnfilteredDiary() {
        OwnerContext owner = new OwnerContext(UUID.randomUUID());
        LocalDate day = LocalDate.of(2026, 10, 4);
        Instant messageTime = Instant.parse("2026-10-05T06:00:00Z");
        Entry reported = entryStore.save(stored(owner, EntryStatus.CONFIRMED, EntryType.METRICS,
                "steps-reported", messageTime, Map.of("code", "steps", "value", 9000, "local_date", day.toString())));
        Entry unknown = entryStore.save(stored(owner, EntryStatus.CONFIRMED, EntryType.METRICS,
                "steps-unknown", messageTime, metrics("steps", 99999)));
        for (EntryStatus status : List.of(EntryStatus.DRAFT, EntryStatus.CANCELLED, EntryStatus.DELETED)) {
            entryStore.save(stored(owner, status, EntryType.METRICS, "inactive-" + status,
                    messageTime, Map.of("code", "steps", "value", 99000, "local_date", day.toString())));
        }
        OwnerContext stranger = new OwnerContext(UUID.randomUUID());
        entryStore.save(stored(stranger, EntryStatus.CONFIRMED, EntryType.METRICS,
                "stranger-steps", messageTime, Map.of("code", "steps", "value", 99000, "local_date", day.toString())));
        for (String zone : List.of("Europe/Vilnius", "Pacific/Kiritimati", "America/Los_Angeles")) {
            ZoneId tz = ZoneId.of(zone);
            assertEquals(List.of(reported.id()), entries.listEntries(new ListEntriesQuery(owner,
                    EntryStatus.CONFIRMED, null, day, day, tz)).stream().map(Entry::id).toList());
            assertEquals(List.of(reported.id()), entries.listConfirmedEntries(new ListConfirmedEntriesQuery(
                    owner, day, day, tz, Set.of())).stream().map(Entry::id).toList());
            assertTrue(entries.listEntries(new ListEntriesQuery(owner, EntryStatus.CONFIRMED,
                    null, day.plusDays(1), day.plusDays(1), tz)).isEmpty());
            assertTrue(entries.listConfirmedEntries(new ListConfirmedEntriesQuery(owner,
                    day.plusDays(1), day.plusDays(1), tz, Set.of())).isEmpty());
        }
        assertEquals(Set.of(reported.id(), unknown.id()), new HashSet<>(entries.listEntries(
                new ListEntriesQuery(owner, EntryStatus.CONFIRMED, null, null, null, ZoneId.of("UTC")))
                .stream().map(Entry::id).toList()));
    }

    @Test
    void stepsDatePatchPreservesMessageTimeAndRejectsTamperingWithTimeCodeOwnerOrRevision() {
        OwnerContext owner = new OwnerContext(UUID.randomUUID());
        Entry original = entries.createDraft(draft(owner, 1100,
                Map.of("code", "steps", "value", 8000, "unit", "count", "local_date", "2026-10-04"))).entry();
        Entry changed = entries.patch(new PatchEntryCommand(owner, original.id(), 1, null,
                Map.of("local_date", "2026-10-03"), Map.of("local_date", "reported")));
        assertEquals(original.occurredAt(), changed.occurredAt());
        assertEquals("2026-10-03", changed.payload().get("local_date"));
        assertThrows(EntryVersionConflictException.class, () -> entries.patch(new PatchEntryCommand(
                owner, original.id(), 1, null, Map.of("local_date", "2026-10-02"), null)));
        assertThrows(EntryNotFoundException.class, () -> entries.patch(new PatchEntryCommand(
                new OwnerContext(UUID.randomUUID()), original.id(), 2, null, Map.of("local_date", "2026-10-02"), null)));
        assertThrows(EntryValidationException.class, () -> entries.patch(new PatchEntryCommand(
                owner, original.id(), 2, original.occurredAt().plusSeconds(1), Map.of(), null)));
        assertThrows(EntryValidationException.class, () -> entries.patch(new PatchEntryCommand(
                owner, original.id(), 2, null, Map.of("code", "heart_rate"), null)));
        assertEquals(changed, entries.requireEntry(owner, original.id()));
        OwnerContext another = new OwnerContext(UUID.randomUUID());
        Entry pulse = entries.createDraft(draft(another, 1101,
                Map.of("code", "heart_rate", "value", 72, "unit", "bpm", "local_date", "2026-10-04"))).entry();
        assertThrows(EntryValidationException.class, () -> entries.patch(new PatchEntryCommand(
                another, pulse.id(), 1, null, Map.of("code", "steps", "unit", "count"), null)));
        assertThrows(EntryValidationException.class, () -> entries.patch(new PatchEntryCommand(another, pulse.id(), 1,
                pulse.occurredAt().plusSeconds(60), Map.of(), null)));
        assertEquals(pulse.occurredAt(), entries.requireEntry(another, pulse.id()).occurredAt());
    }

    private static CreateDraftCommand draft(OwnerContext owner, long updateId, Map<String, Object> payload) {
        return new CreateDraftCommand(owner, EntryType.METRICS, SourceKind.TEXT, Map.of(),
                Instant.parse("2026-09-23T08:00:00Z"), payload, Map.of(),
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

    private static AnnotationConfigApplicationContext restartContext(String databaseName) {
        AnnotationConfigApplicationContext context = new AnnotationConfigApplicationContext();
        TestPropertyValues.of(
                "restart.mongo.uri=" + MONGO.getReplicaSetUrl(),
                "restart.mongo.database=" + databaseName
        ).applyTo(context);
        context.register(RestartStorageTestConfiguration.class);
        context.refresh();
        return context;
    }
}
