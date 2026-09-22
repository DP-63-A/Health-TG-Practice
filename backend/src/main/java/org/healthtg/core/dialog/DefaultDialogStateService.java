package org.healthtg.core.dialog;

import org.healthtg.core.entry.OwnerContext;
import org.springframework.stereotype.Service;

import java.time.Clock;
import java.time.temporal.ChronoUnit;
import java.util.Optional;
import java.util.UUID;

@Service
class DefaultDialogStateService implements DialogStateService {
    private final MongoDialogStateRepository repository;
    private final Clock clock;

    DefaultDialogStateService(MongoDialogStateRepository repository, Clock clock) {
        this.repository = repository;
        this.clock = clock;
    }

    @Override
    public DialogState save(SaveDialogStateCommand command) {
        String ownerId = command.owner().userId().toString();
        String updateKey = command.updateKey().storageKey();
        Optional<MongoDialogStateDocument> current = repository.findById(ownerId);
        if (current.filter(value -> value.telegramUpdateKey().equals(updateKey)).isPresent()) {
            return toDomain(current.orElseThrow());
        }
        long revision = current.map(value -> value.revision() + 1).orElse(1L);
        Long mongoVersion = current.map(MongoDialogStateDocument::mongoVersion).orElse(null);
        MongoDialogStateDocument saved = repository.save(new MongoDialogStateDocument(ownerId,
                command.activeEntryId() == null ? null : command.activeEntryId().toString(), command.step(),
                command.context(), revision, clock.instant().truncatedTo(ChronoUnit.MILLIS), updateKey, mongoVersion));
        return toDomain(saved);
    }

    @Override
    public Optional<DialogState> find(OwnerContext owner) {
        return repository.findById(owner.userId().toString()).map(DefaultDialogStateService::toDomain);
    }

    private static DialogState toDomain(MongoDialogStateDocument document) {
        return new DialogState(UUID.fromString(document.ownerId()),
                document.activeEntryId() == null ? null : UUID.fromString(document.activeEntryId()),
                document.step(), document.context(), document.revision(), document.updatedAt(),
                document.telegramUpdateKey());
    }
}
