package org.healthtg.core.dialog;

import org.healthtg.core.entry.OwnerContext;
import org.healthtg.core.entry.Entry;
import org.healthtg.core.entry.EntryStore;
import org.healthtg.core.entry.EntryOwnershipException;
import org.healthtg.core.entry.EntryValidationException;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.dao.OptimisticLockingFailureException;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Service;

import java.time.Clock;
import java.time.temporal.ChronoUnit;
import java.util.HashMap;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;

@Service
@ConditionalOnProperty(name = "health-tg.core.storage.enabled", matchIfMissing = true)
class DefaultDialogStateService implements DialogStateService {
    private final MongoDialogStateRepository repository;
    private final EntryStore entryStore;
    private final Clock clock;

    DefaultDialogStateService(MongoDialogStateRepository repository, EntryStore entryStore, Clock clock) {
        this.repository = repository;
        this.entryStore = entryStore;
        this.clock = clock;
    }

    @Override
    public DialogState save(SaveDialogStateCommand command) {
        validateActiveEntry(command);
        String ownerId = command.owner().userId().toString();
        String updateKey = command.updateKey().storageKey();
        RuntimeException lastConflict = null;

        for (int attempt = 0; attempt < 5; attempt++) {
            Optional<MongoDialogStateDocument> current = repository.findById(ownerId);
            if (current.filter(value -> alreadyProcessed(value, command)).isPresent()) {
                return toDomain(current.orElseThrow());
            }

            long revision = current.map(value -> value.revision() + 1).orElse(1L);
            Long mongoVersion = current.map(MongoDialogStateDocument::mongoVersion).orElse(null);
            Map<String, Long> processedUpdateIds = current
                    .map(DefaultDialogStateService::processedUpdateIds)
                    .orElseGet(HashMap::new);
            processedUpdateIds.merge(command.updateKey().botKey(), command.updateKey().updateId(), Math::max);

            try {
                MongoDialogStateDocument saved = repository.save(new MongoDialogStateDocument(ownerId,
                        command.activeEntryId() == null ? null : command.activeEntryId().toString(), command.step(),
                        command.context(), revision, clock.instant().truncatedTo(ChronoUnit.MILLIS), updateKey,
                        Map.copyOf(processedUpdateIds), mongoVersion));
                return toDomain(saved);
            } catch (DuplicateKeyException | OptimisticLockingFailureException conflict) {
                lastConflict = conflict;
            }
        }

        throw new DialogStateConflictException("Could not save dialog state after concurrent updates", lastConflict);
    }

    @Override
    public Optional<DialogState> find(OwnerContext owner) {
        return repository.findById(owner.userId().toString()).map(DefaultDialogStateService::toDomain);
    }

    private void validateActiveEntry(SaveDialogStateCommand command) {
        if (command.activeEntryId() == null) return;
        Entry entry = entryStore.findById(command.activeEntryId())
                .orElseThrow(() -> new EntryValidationException("Active entry does not exist"));
        if (!entry.ownerId().equals(command.owner().userId())) {
            throw new EntryOwnershipException("Active entry belongs to another owner");
        }
    }

    private static boolean alreadyProcessed(MongoDialogStateDocument state, SaveDialogStateCommand command) {
        Long highest = processedUpdateIds(state).get(command.updateKey().botKey());
        if (highest != null) return command.updateKey().updateId() <= highest;
        return Objects.equals(state.telegramUpdateKey(), command.updateKey().storageKey());
    }

    private static Map<String, Long> processedUpdateIds(MongoDialogStateDocument state) {
        Map<String, Long> processed = state.processedUpdateIds() == null
                ? new HashMap<>()
                : new HashMap<>(state.processedUpdateIds());
        String legacyKey = state.telegramUpdateKey();
        int separator = legacyKey == null ? -1 : legacyKey.lastIndexOf(':');
        if (separator > 0 && separator < legacyKey.length() - 1) {
            try {
                String botKey = legacyKey.substring(0, separator);
                long updateId = Long.parseLong(legacyKey.substring(separator + 1));
                processed.merge(botKey, updateId, Math::max);
            } catch (NumberFormatException ignored) {
                // Exact-key comparison in alreadyProcessed still handles malformed legacy keys.
            }
        }
        return processed;
    }

    private static DialogState toDomain(MongoDialogStateDocument document) {
        return new DialogState(UUID.fromString(document.ownerId()),
                document.activeEntryId() == null ? null : UUID.fromString(document.activeEntryId()),
                document.step(), document.context(), document.revision(), document.updatedAt(),
                document.telegramUpdateKey());
    }
}
