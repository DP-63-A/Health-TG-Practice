package org.healthtg.core.entry;

import org.springframework.dao.DuplicateKeyException;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Service;

import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.temporal.ChronoUnit;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

@Service
@ConditionalOnProperty(name = "health-tg.core.storage.enabled", matchIfMissing = true)
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
        EntryPayloadValidator.validateDraft(command.type(), command.payload());
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

    @Override
    public List<Entry> listConfirmedEntries(ListConfirmedEntriesQuery query) {
        return store.findByOwnerAndStatus(query.owner().userId(), EntryStatus.CONFIRMED).stream()
                .filter(entry -> query.types().isEmpty() || query.types().contains(entry.type()))
                .filter(entry -> {
                    LocalDate date = localDate(entry, query);
                    return !date.isBefore(query.from()) && !date.isAfter(query.to());
                })
                .sorted(Comparator.comparing(Entry::occurredAt).thenComparing(Entry::id))
                .toList();
    }

    @Override
    public List<Entry> listEntries(ListEntriesQuery query) {
        if (query.status() != EntryStatus.CONFIRMED && query.status() != EntryStatus.DRAFT) {
            throw new EntryValidationException("Only confirmed and draft entries may be listed");
        }
        return store.findByOwnerAndStatus(query.owner().userId(), query.status()).stream()
                .filter(entry -> query.type() == null || entry.type() == query.type())
                .filter(entry -> {
                    LocalDate date = entry.occurredAt().atZone(query.timezone()).toLocalDate();
                    return (query.from() == null || !date.isBefore(query.from()))
                            && (query.to() == null || !date.isAfter(query.to()));
                })
                .sorted(Comparator.comparing(Entry::occurredAt).thenComparing(Entry::id).reversed())
                .toList();
    }

    @Override
    public Entry requireEntry(OwnerContext owner, UUID entryId) {
        return store.findById(entryId).filter(entry -> entry.ownerId().equals(owner.userId()))
                .orElseThrow(EntryNotFoundException::new);
    }

    @Override
    public Entry patch(PatchEntryCommand command) {
        Entry current = requireEntry(command.owner(), command.entryId());
        if (current.status() != EntryStatus.DRAFT && current.status() != EntryStatus.CONFIRMED) {
            throw new EntryStatusConflictException();
        }
        requireRevision(current, command.expectedRevision());
        Map<String, Object> payload = merge(current.payload(), command.payload());
        Map<String, String> origins = merge(current.fieldOrigins(), command.fieldOrigins());
        if (current.status() == EntryStatus.CONFIRMED) {
            EntryPayloadValidator.validateConfirmed(current.type(), payload);
        } else {
            EntryPayloadValidator.validateDraft(current.type(), payload);
        }
        Entry replacement = changed(current, current.status(), command.occurredAt() == null
                ? current.occurredAt() : command.occurredAt(), payload, origins, current.submissionId());
        return replaceOrConflict(current, replacement);
    }

    @Override
    public Entry confirm(ConfirmEntryCommand command) {
        Entry current = requireEntry(command.owner(), command.entryId());
        Optional<Entry> repeated = store.findBySubmissionId(command.submissionId());
        if (repeated.isPresent()) {
            Entry entry = repeated.get();
            if (!entry.id().equals(command.entryId())) {
                throw new EntryStatusConflictException();
            }
            if (entry.status() == EntryStatus.CONFIRMED) return entry;
        }
        if (current.status() != EntryStatus.DRAFT) throw new EntryStatusConflictException();
        requireRevision(current, command.expectedRevision());
        EntryPayloadValidator.validateConfirmed(current.type(), current.payload());
        Entry replacement = changed(current, EntryStatus.CONFIRMED, current.occurredAt(), current.payload(),
                current.fieldOrigins(), command.submissionId());
        try {
            return replaceOrConflict(current, replacement);
        } catch (DuplicateKeyException | EntryVersionConflictException conflict) {
            Entry entry = store.findBySubmissionId(command.submissionId()).orElseThrow(() -> conflict);
            if (entry.ownerId().equals(command.owner().userId()) && entry.id().equals(command.entryId())
                    && entry.status() == EntryStatus.CONFIRMED) return entry;
            throw new EntryStatusConflictException();
        }
    }

    @Override
    public Entry cancel(OwnerContext owner, UUID entryId) {
        Entry current = requireEntry(owner, entryId);
        if (current.status() == EntryStatus.CANCELLED) return current;
        if (current.status() != EntryStatus.DRAFT) throw new EntryStatusConflictException();
        try {
            return replaceOrConflict(current, changed(current, EntryStatus.CANCELLED, current.occurredAt(),
                    current.payload(), current.fieldOrigins(), current.submissionId()));
        } catch (EntryVersionConflictException conflict) {
            Entry actual = requireEntry(owner, entryId);
            if (actual.status() == EntryStatus.CANCELLED) return actual;
            throw conflict;
        }
    }

    @Override
    public Entry delete(OwnerContext owner, UUID entryId, long expectedRevision) {
        Entry current = requireEntry(owner, entryId);
        if (current.status() != EntryStatus.CONFIRMED) throw new EntryStatusConflictException();
        requireRevision(current, expectedRevision);
        return replaceOrConflict(current, changed(current, EntryStatus.DELETED, current.occurredAt(),
                current.payload(), current.fieldOrigins(), current.submissionId()));
    }

    private Entry replaceOrConflict(Entry current, Entry replacement) {
        return store.replaceIfCurrent(current, replacement).orElseGet(() -> {
            Entry actual = store.findById(current.id()).orElseThrow(EntryNotFoundException::new);
            if (!actual.ownerId().equals(current.ownerId())) throw new EntryNotFoundException();
            throw new EntryVersionConflictException(actual);
        });
    }

    private Entry changed(Entry current, EntryStatus status, Instant occurredAt, Map<String, Object> payload,
                          Map<String, String> origins, String submissionId) {
        return new Entry(current.id(), current.ownerId(), current.type(), status, current.sourceKind(),
                current.sourceRef(), occurredAt, current.createdAt(), persistedNow(), current.revision() + 1,
                payload, origins, submissionId, current.telegramUpdateKey());
    }

    private static void requireRevision(Entry current, long expectedRevision) {
        if (current.revision() != expectedRevision) throw new EntryVersionConflictException(current);
    }

    private static <T> Map<String, T> merge(Map<String, T> current, Map<String, T> patch) {
        if (patch == null) return current;
        Map<String, T> result = new LinkedHashMap<>(current);
        result.putAll(patch);
        return result;
    }

    private static Entry owned(Entry entry, OwnerContext owner) {
        if (!entry.ownerId().equals(owner.userId())) {
            throw new EntryOwnershipException("Telegram update key belongs to another owner");
        }
        return entry;
    }

    private Instant persistedNow() {
        return clock.instant().truncatedTo(ChronoUnit.MILLIS);
    }

    private static LocalDate localDate(Entry entry, ListConfirmedEntriesQuery query) {
        return entry.occurredAt().atZone(query.timezone()).toLocalDate();
    }

}
