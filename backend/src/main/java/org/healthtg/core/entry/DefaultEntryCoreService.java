package org.healthtg.core.entry;

import org.springframework.dao.DuplicateKeyException;
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
        Object payloadDate = entry.payload().get("local_date");
        if (payloadDate instanceof LocalDate date) return date;
        if (payloadDate instanceof String date) return LocalDate.parse(date);
        return entry.occurredAt().atZone(query.timezone()).toLocalDate();
    }

}
