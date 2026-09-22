package org.healthtg.core.entry;

import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Service;

import java.time.Clock;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

@Service
class DefaultEntryCoreService implements EntryCoreService {
    private final EntryStore store;
    private final Clock clock;

    DefaultEntryCoreService(EntryStore store, Clock clock) {
        this.store = store;
        this.clock = clock;
    }

    @Override
    public Entry createCheckin(CreateCheckinCommand command) {
        String updateKey = command.updateKey().storageKey();
        Optional<Entry> existing = store.findByTelegramUpdateKey(updateKey);
        if (existing.isPresent()) return owned(existing.get(), command.owner());

        Instant now = persistedNow();
        Entry entry = new Entry(UUID.randomUUID(), command.owner().userId(), EntryType.CHECKIN,
                EntryStatus.CONFIRMED, SourceKind.QUICK_CHECKIN,
                Map.of("telegram_update_id", command.updateKey().updateId()), command.occurredAt(), now, now, 1,
                Map.of("category", command.category().code(), "score", command.score()),
                Map.of("category", "reported", "score", "reported"), null, updateKey);
        try {
            return store.save(entry);
        } catch (DuplicateKeyException duplicate) {
            return owned(store.findByTelegramUpdateKey(updateKey).orElseThrow(() -> duplicate), command.owner());
        }
    }

    @Override
    public DraftCreationResult createDraft(CreateDraftCommand command) {
        validatePayload(command.type(), command.payload());
        String updateKey = command.updateKey().storageKey();
        Optional<Entry> repeated = store.findByTelegramUpdateKey(updateKey);
        if (repeated.isEmpty() && command.submissionId() != null) {
            repeated = store.findBySubmissionId(command.submissionId());
        }
        if (repeated.isPresent()) {
            return new DraftCreationResult(owned(repeated.get(), command.owner()),
                    DraftCreationResult.Outcome.EXISTING_UPDATE);
        }
        Optional<Entry> active = store.findActiveDraft(command.owner().userId());
        if (active.isPresent()) {
            return new DraftCreationResult(active.get(), DraftCreationResult.Outcome.ACTIVE_DRAFT_EXISTS);
        }

        Instant now = persistedNow();
        Map<String, Object> sourceRef = new LinkedHashMap<>(command.sourceRef());
        sourceRef.putIfAbsent("telegram_update_id", command.updateKey().updateId());
        Entry draft = new Entry(UUID.randomUUID(), command.owner().userId(), command.type(), EntryStatus.DRAFT,
                command.sourceKind(), sourceRef, command.occurredAt(), now, now, 1, command.payload(),
                command.fieldOrigins(), command.submissionId(), updateKey);
        try {
            return new DraftCreationResult(store.save(draft), DraftCreationResult.Outcome.CREATED);
        } catch (DuplicateKeyException duplicate) {
            Optional<Entry> sameUpdate = store.findByTelegramUpdateKey(updateKey);
            if (sameUpdate.isEmpty() && command.submissionId() != null) {
                sameUpdate = store.findBySubmissionId(command.submissionId());
            }
            if (sameUpdate.isPresent()) {
                return new DraftCreationResult(owned(sameUpdate.get(), command.owner()),
                        DraftCreationResult.Outcome.EXISTING_UPDATE);
            }
            return new DraftCreationResult(store.findActiveDraft(command.owner().userId()).orElseThrow(() -> duplicate),
                    DraftCreationResult.Outcome.ACTIVE_DRAFT_EXISTS);
        }
    }

    @Override
    public Optional<Entry> findActiveDraft(OwnerContext owner) {
        return store.findActiveDraft(owner.userId());
    }

    private static Entry owned(Entry entry, OwnerContext owner) {
        if (!entry.ownerId().equals(owner.userId())) {
            throw new IllegalStateException("Telegram update key belongs to another owner");
        }
        return entry;
    }

    private Instant persistedNow() {
        return clock.instant().truncatedTo(ChronoUnit.MILLIS);
    }

    private static void validatePayload(EntryType type, Map<String, Object> payload) {
        validateFinite(payload);
        if (type != EntryType.METRICS) return;
        validateNonNegative(payload, "steps");
        validateNonNegative(payload, "sleep_hours");
        validateNonNegative(payload, "sleep_minutes");
    }

    private static void validateFinite(Object value) {
        if (value instanceof Double doubleValue && !Double.isFinite(doubleValue)
                || value instanceof Float floatValue && !Float.isFinite(floatValue)) {
            throw new IllegalArgumentException("Numeric values must be finite");
        }
        if (value instanceof Map<?, ?> map) map.values().forEach(DefaultEntryCoreService::validateFinite);
        if (value instanceof Collection<?> collection) collection.forEach(DefaultEntryCoreService::validateFinite);
    }

    private static void validateNonNegative(Map<String, Object> payload, String field) {
        Object value = payload.get(field);
        if (value instanceof Number number && number.doubleValue() < 0) {
            throw new IllegalArgumentException(field + " must be non-negative");
        }
    }
}
