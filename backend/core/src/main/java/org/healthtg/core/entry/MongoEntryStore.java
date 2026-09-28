package org.healthtg.core.entry;

import org.springframework.stereotype.Repository;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

@Repository
@ConditionalOnProperty(name = "health-tg.core.storage.enabled", matchIfMissing = true)
class MongoEntryStore implements EntryStore {
    private final MongoEntryRepository repository;

    MongoEntryStore(MongoEntryRepository repository) {
        this.repository = repository;
    }

    @Override
    public Entry save(Entry entry) {
        return toDomain(repository.save(toDocument(entry)));
    }

    @Override
    public Optional<Entry> findById(UUID id) {
        return repository.findById(id.toString()).map(MongoEntryStore::toDomain);
    }

    @Override
    public Optional<Entry> findByTelegramUpdateKey(String updateKey) {
        return repository.findByTelegramUpdateKey(updateKey).map(MongoEntryStore::toDomain);
    }

    @Override
    public Optional<Entry> findBySubmissionId(String submissionId) {
        return repository.findBySubmissionId(submissionId).map(MongoEntryStore::toDomain);
    }

    @Override
    public Optional<Entry> findActiveDraft(UUID ownerId) {
        return repository.findFirstByOwnerIdAndStatus(ownerId.toString(), EntryStatus.DRAFT.code())
                .map(MongoEntryStore::toDomain);
    }

    @Override
    public List<Entry> findByOwnerAndStatus(UUID ownerId, EntryStatus status) {
        return repository.findByOwnerIdAndStatus(ownerId.toString(), status.code()).stream()
                .map(MongoEntryStore::toDomain)
                .toList();
    }

    private static MongoEntryDocument toDocument(Entry entry) {
        return new MongoEntryDocument(entry.id().toString(), entry.ownerId().toString(), entry.type().code(),
                entry.status().code(), entry.sourceKind().code(), entry.sourceRef(), entry.occurredAt(),
                entry.createdAt(), entry.updatedAt(), entry.revision(), entry.payload(), entry.fieldOrigins(),
                entry.submissionId(), entry.telegramUpdateKey());
    }

    private static Entry toDomain(MongoEntryDocument document) {
        return new Entry(UUID.fromString(document.id()), UUID.fromString(document.ownerId()),
                EntryType.fromCode(document.type()), EntryStatus.fromCode(document.status()),
                SourceKind.fromCode(document.sourceKind()), document.sourceRef(), document.occurredAt(),
                document.createdAt(), document.updatedAt(), document.revision(), document.payload(),
                document.fieldOrigins(), document.submissionId(), document.telegramUpdateKey());
    }
}
