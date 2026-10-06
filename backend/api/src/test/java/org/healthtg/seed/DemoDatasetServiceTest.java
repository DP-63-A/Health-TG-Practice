package org.healthtg.seed;

import org.healthtg.core.entry.ConfirmEntryCommand;
import org.healthtg.core.entry.CreateCheckinCommand;
import org.healthtg.core.entry.CreateDraftCommand;
import org.healthtg.core.entry.DraftCreationResult;
import org.healthtg.core.entry.Entry;
import org.healthtg.core.entry.EntryCoreService;
import org.healthtg.core.entry.EntryStatus;
import org.healthtg.core.entry.EntryType;
import org.healthtg.core.entry.ListConfirmedEntriesQuery;
import org.healthtg.core.entry.ListEntriesQuery;
import org.healthtg.core.entry.OwnerContext;
import org.healthtg.core.entry.PatchEntryCommand;
import org.healthtg.core.entry.SourceKind;
import org.healthtg.core.entry.TelegramUpdateKey;
import org.healthtg.user.UserAccount;
import org.healthtg.user.UserService;
import org.junit.jupiter.api.Test;
import org.springframework.data.mongodb.core.MongoTemplate;
import org.springframework.data.mongodb.core.query.Query;

import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class DemoDatasetServiceTest {
    private static final String DEMO_URI = "mongodb://localhost:27017/health_tg_demo";
    private static final LocalDate START = LocalDate.of(2026, 9, 1);
    private static final long SEED = 24680L;

    @Test
    void rerunningSameParametersKeepsLogicalEntryCountAndKeysStable() {
        UUID regularId = UUID.fromString("11111111-1111-4111-8111-111111111101");
        UUID irregularId = UUID.fromString("11111111-1111-4111-8111-111111111102");
        UUID incompleteId = UUID.fromString("11111111-1111-4111-8111-111111111103");
        DemoProfileOwners owners = new DemoProfileOwners(regularId, irregularId, incompleteId);
        UserService users = mock(UserService.class);
        when(users.requireById(regularId)).thenReturn(new UserAccount(regularId, 10, ZoneId.of("Europe/Warsaw"), true));
        when(users.requireById(irregularId)).thenReturn(new UserAccount(irregularId, 20, ZoneId.of("UTC"), true));
        when(users.requireById(incompleteId))
                .thenReturn(new UserAccount(incompleteId, 30, ZoneId.of("America/New_York"), true));
        InMemoryEntryCore entries = new InMemoryEntryCore();
        DemoDatasetService service = new DemoDatasetService(entries, users, mock(MongoTemplate.class));

        int seeded = service.seed("true", DEMO_URI, owners, SEED, START);
        Map<String, UUID> firstKeys = entries.keySnapshot();
        int repeated = service.seed("true", DEMO_URI, owners, SEED, START);

        assertEquals(seeded, repeated);
        assertEquals(seeded, entries.entries.size());
        assertEquals(firstKeys, entries.keySnapshot());
        assertEquals(1, entries.entries.values().stream()
                .filter(entry -> entry.status() == EntryStatus.CANCELLED).count());
        assertEquals(1, entries.entries.values().stream()
                .filter(entry -> entry.status() == EntryStatus.CONFIRMED && entry.revision() == 3).count());
        assertEquals(52, entries.entries.values().stream()
                .filter(entry -> entry.ownerId().equals(incompleteId) && entry.status() == EntryStatus.CONFIRMED)
                .count());
        assertEquals(1, entries.entries.values().stream()
                .filter(entry -> entry.ownerId().equals(incompleteId) && entry.status() == EntryStatus.CANCELLED)
                .count());
    }

    @Test
    void rerunningSeedRejectsAnExistingRecordWithChangedPayloadOrStatus() {
        UUID regularId = UUID.fromString("11111111-1111-4111-8111-111111111101");
        UUID irregularId = UUID.fromString("11111111-1111-4111-8111-111111111102");
        UUID incompleteId = UUID.fromString("11111111-1111-4111-8111-111111111103");
        DemoProfileOwners owners = new DemoProfileOwners(regularId, irregularId, incompleteId);
        UserService users = mock(UserService.class);
        when(users.requireById(regularId)).thenReturn(new UserAccount(regularId, 10, ZoneId.of("Europe/Warsaw"), true));
        when(users.requireById(irregularId)).thenReturn(new UserAccount(irregularId, 20, ZoneId.of("UTC"), true));
        when(users.requireById(incompleteId))
                .thenReturn(new UserAccount(incompleteId, 30, ZoneId.of("America/New_York"), true));
        InMemoryEntryCore entries = new InMemoryEntryCore();
        DemoDatasetService service = new DemoDatasetService(entries, users, mock(MongoTemplate.class));
        service.seed("true", DEMO_URI, owners, SEED, START);
        entries.corruptFirstEntry(entry -> new Entry(entry.id(), entry.ownerId(), entry.type(), EntryStatus.DRAFT,
                entry.sourceKind(), entry.sourceRef(), entry.occurredAt(), entry.createdAt(), entry.updatedAt(),
                entry.revision(), Map.of("tampered", true), entry.fieldOrigins(), entry.submissionId(),
                entry.telegramUpdateKey()));

        IllegalStateException error = assertThrows(IllegalStateException.class,
                () -> service.seed("true", DEMO_URI, owners, SEED, START));

        assertEquals("Existing BE3-05 record differs from the expected dataset; run reset first", error.getMessage());
    }

    @Test
    void seedRejectsDuplicateResolvedAccountsBeforeWriting() {
        UUID regularId = UUID.fromString("11111111-1111-4111-8111-111111111101");
        UUID irregularId = UUID.fromString("11111111-1111-4111-8111-111111111102");
        UUID incompleteId = UUID.fromString("11111111-1111-4111-8111-111111111103");
        DemoProfileOwners owners = new DemoProfileOwners(regularId, irregularId, incompleteId);
        UserService users = mock(UserService.class);
        UserAccount duplicate = new UserAccount(regularId, 10, ZoneId.of("Europe/Warsaw"), true);
        when(users.requireById(regularId)).thenReturn(duplicate);
        when(users.requireById(irregularId)).thenReturn(
                new UserAccount(regularId, 10, ZoneId.of("UTC"), true));
        when(users.requireById(incompleteId)).thenReturn(
                new UserAccount(incompleteId, 30, ZoneId.of("America/New_York"), true));
        InMemoryEntryCore entries = new InMemoryEntryCore();
        DemoDatasetService service = new DemoDatasetService(entries, users, mock(MongoTemplate.class));

        assertThrows(IllegalArgumentException.class, () -> service.seed("true", DEMO_URI, owners, SEED, START));

        assertEquals(0, entries.entries.size());
    }

    @Test
    void seedRejectsMissingAccountBeforeWriting() {
        UUID regularId = UUID.fromString("11111111-1111-4111-8111-111111111101");
        UUID irregularId = UUID.fromString("11111111-1111-4111-8111-111111111102");
        UUID incompleteId = UUID.fromString("11111111-1111-4111-8111-111111111103");
        DemoProfileOwners owners = new DemoProfileOwners(regularId, irregularId, incompleteId);
        UserService users = mock(UserService.class);
        when(users.requireById(regularId)).thenReturn(new UserAccount(regularId, 10, ZoneId.of("Europe/Warsaw"), true));
        when(users.requireById(irregularId)).thenReturn(new UserAccount(irregularId, 20, ZoneId.of("UTC"), true));
        when(users.requireById(incompleteId)).thenThrow(new IllegalStateException("Account not found"));
        InMemoryEntryCore entries = new InMemoryEntryCore();
        DemoDatasetService service = new DemoDatasetService(entries, users, mock(MongoTemplate.class));

        assertThrows(IllegalStateException.class, () -> service.seed("true", DEMO_URI, owners, SEED, START));

        assertEquals(0, entries.entries.size());
    }

    @Test
    void resetRefusesOutsideDemoBeforeTouchingMongo() {
        MongoTemplate mongo = mock(MongoTemplate.class);
        DemoDatasetService service = new DemoDatasetService(mock(EntryCoreService.class), mock(UserService.class), mongo);

        assertThrows(IllegalStateException.class, () ->
                service.reset("false", DEMO_URI));
        assertThrows(IllegalStateException.class, () ->
                service.reset("true", "mongodb://localhost:27017/health_tg"));
        verify(mongo, never()).remove(any(Query.class), anyString());
    }

    private static final class InMemoryEntryCore implements EntryCoreService {
        private static final Instant NOW = Instant.parse("2026-09-01T00:00:00Z");
        private final Map<UUID, Entry> entries = new HashMap<>();
        private final Map<String, UUID> updateKeys = new HashMap<>();

        @Override
        public Entry createCheckin(CreateCheckinCommand command) {
            throw new UnsupportedOperationException();
        }

        @Override
        public DraftCreationResult createDraft(CreateDraftCommand command) {
            String key = command.updateKey().storageKey();
            UUID existingId = updateKeys.get(key);
            if (existingId != null) return new DraftCreationResult(entries.get(existingId),
                    DraftCreationResult.Outcome.EXISTING_UPDATE);
            UUID id = UUID.nameUUIDFromBytes(key.getBytes(StandardCharsets.UTF_8));
            Map<String, Object> sourceRef = new HashMap<>(command.sourceRef());
            sourceRef.putIfAbsent("telegram_update_id", command.updateKey().updateId());
            Entry entry = new Entry(id, command.owner().userId(), command.type(), EntryStatus.DRAFT,
                    command.sourceKind(), sourceRef, command.occurredAt(), NOW, NOW, 1,
                    command.payload(), command.fieldOrigins(), null, key);
            entries.put(id, entry);
            updateKeys.put(key, id);
            return new DraftCreationResult(entry, DraftCreationResult.Outcome.CREATED);
        }

        @Override
        public Optional<Entry> findActiveDraft(OwnerContext owner) {
            return Optional.empty();
        }

        @Override
        public List<Entry> listConfirmedEntries(ListConfirmedEntriesQuery query) {
            throw new UnsupportedOperationException();
        }

        @Override
        public List<Entry> listEntries(ListEntriesQuery query) {
            throw new UnsupportedOperationException();
        }

        @Override
        public Entry requireEntry(OwnerContext owner, UUID entryId) {
            return entries.get(entryId);
        }

        @Override
        public Entry patch(PatchEntryCommand command) {
            Entry current = entries.get(command.entryId());
            Entry replacement = new Entry(current.id(), current.ownerId(), current.type(), current.status(),
                    current.sourceKind(), current.sourceRef(), current.occurredAt(), current.createdAt(), NOW,
                    current.revision() + 1, command.payload(), command.fieldOrigins(), current.submissionId(),
                    current.telegramUpdateKey());
            entries.put(replacement.id(), replacement);
            return replacement;
        }

        @Override
        public Entry confirm(ConfirmEntryCommand command) {
            Entry current = entries.get(command.entryId());
            Entry replacement = changed(current, EntryStatus.CONFIRMED, command.submissionId());
            entries.put(replacement.id(), replacement);
            return replacement;
        }

        @Override
        public Entry cancel(OwnerContext owner, UUID entryId, long expectedRevision) {
            Entry current = entries.get(entryId);
            Entry replacement = changed(current, EntryStatus.CANCELLED, current.submissionId());
            entries.put(replacement.id(), replacement);
            return replacement;
        }

        @Override
        public Entry delete(OwnerContext owner, UUID entryId, long expectedRevision) {
            throw new UnsupportedOperationException();
        }

        private static Entry changed(Entry current, EntryStatus status, String submissionId) {
            return new Entry(current.id(), current.ownerId(), current.type(), status, current.sourceKind(),
                    current.sourceRef(), current.occurredAt(), current.createdAt(), NOW, current.revision() + 1,
                    current.payload(), current.fieldOrigins(), submissionId, current.telegramUpdateKey());
        }

        private Map<String, UUID> keySnapshot() {
            return Map.copyOf(updateKeys);
        }

        private void corruptFirstEntry(java.util.function.UnaryOperator<Entry> change) {
            Entry current = entries.values().iterator().next();
            entries.put(current.id(), change.apply(current));
        }
    }
}
