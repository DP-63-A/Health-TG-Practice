package org.healthtg.seed;

import org.healthtg.auth.AuthProperties;
import org.healthtg.core.entry.CheckinCategory;
import org.healthtg.core.entry.ConfirmEntryCommand;
import org.healthtg.core.entry.CreateCheckinCommand;
import org.healthtg.core.entry.CreateDraftCommand;
import org.healthtg.core.entry.DraftCreationResult;
import org.healthtg.core.entry.Entry;
import org.healthtg.core.entry.EntryCoreService;
import org.healthtg.core.entry.EntryStatus;
import org.healthtg.core.entry.EntryType;
import org.healthtg.core.entry.OwnerContext;
import org.healthtg.core.entry.PatchEntryCommand;
import org.healthtg.core.entry.SourceKind;
import org.healthtg.core.entry.TelegramUpdateKey;
import org.healthtg.user.UserAccount;
import org.healthtg.user.UserService;
import org.healthtg.user.UserStore;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.data.mongodb.core.MongoTemplate;
import org.springframework.data.mongodb.core.query.Criteria;
import org.springframework.data.mongodb.core.query.Query;
import org.springframework.stereotype.Service;

import java.time.Instant;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.EnumMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.TreeSet;

/**
 * Local demo seed and reset (BE3-05). Writes only through {@link EntryCoreService}, the agreed BE1-03/BE1-04 path,
 * using stable idempotency keys. It is a Java service driven by {@link SeedCli}; it has no HTTP surface.
 */
@Service
@ConditionalOnProperty(name = "health-tg.core.storage.enabled", matchIfMissing = true)
public class SeedService {
    static final String KEY_PREFIX = "seed:";
    private static final String ENTRIES_COLLECTION = "entries";
    /** A draft is revision 1 and confirmation makes it 2; a later patch makes it 3, so 2 means "not yet corrected". */
    private static final long CONFIRMED_UNCORRECTED_REVISION = 2;

    private final SeedProperties properties;
    private final AuthProperties auth;
    private final UserService users;
    private final UserStore userStore;
    private final EntryCoreService entries;
    private final MongoTemplate mongo;

    public SeedService(SeedProperties properties, AuthProperties auth, UserService users, UserStore userStore,
                       EntryCoreService entries, MongoTemplate mongo) {
        this.properties = properties;
        this.auth = auth;
        this.users = users;
        this.userStore = userStore;
        this.entries = entries;
        this.mongo = mongo;
    }

    public SeedReport seed() {
        return seed(properties.randomSeed(), properties.startDate());
    }

    public SeedReport seed(long randomSeed, LocalDate startDate) {
        Objects.requireNonNull(startDate, "startDate");
        requireDemoEnvironment();
        Map<SeedProfile, Long> ids = configuredTelegramIds();
        Map<SeedProfile, UserAccount> accounts = new EnumMap<>(SeedProfile.class);
        ids.forEach((profile, telegramId) -> accounts.put(profile, users.findOrCreate(telegramId)));

        List<SeedReport.ProfileReport> reports = new ArrayList<>();
        for (SeedProfile profile : SeedProfile.values()) {
            UserAccount account = accounts.get(profile);
            List<SeedRecord> records = SyntheticProfileGenerator.generate(profile, randomSeed, startDate);
            for (SeedRecord record : records) write(profile, account, randomSeed, startDate, record);
            reports.add(report(profile, account, startDate, records));
        }
        return new SeedReport(randomSeed, startDate, List.copyOf(reports));
    }

    /**
     * Removes only seed entries (update key prefix {@code seed:}) owned by the three configured demo accounts.
     * Other owners, other entries of those owners, users, sessions and dialog state are left untouched.
     *
     * @return number of removed entries
     */
    public long reset() {
        requireDemoEnvironment();
        List<String> owners = new ArrayList<>();
        for (Long telegramId : configuredTelegramIds().values()) {
            userStore.findByTelegramId(telegramId).ifPresent(user -> owners.add(user.id().toString()));
        }
        if (owners.isEmpty()) return 0;
        Query query = Query.query(Criteria.where("ownerId").in(owners)
                .and("telegramUpdateKey").regex("^" + KEY_PREFIX));
        return mongo.remove(query, ENTRIES_COLLECTION).getDeletedCount();
    }

    void requireDemoEnvironment() {
        if (!properties.demoEnvironment()) {
            throw new SeedRefusedException("Seed/reset refused: health-tg.seed.demo-environment is not enabled");
        }
        String allowed = properties.allowedDatabase();
        if (allowed.isBlank()) {
            throw new SeedRefusedException("Seed/reset refused: health-tg.seed.allowed-database is not configured");
        }
        String actual = mongo.getDb().getName();
        if (!allowed.equals(actual)) {
            throw new SeedRefusedException("Seed/reset refused: connected database is not the allowed demo database");
        }
    }

    private Map<SeedProfile, Long> configuredTelegramIds() {
        Map<SeedProfile, Long> result = new EnumMap<>(SeedProfile.class);
        Set<Long> allowed = auth.allowedIds();
        for (SeedProfile profile : SeedProfile.values()) {
            String raw = properties.accounts().rawFor(profile);
            if (raw.isBlank()) {
                throw new SeedRefusedException("Seed/reset refused: no test account configured for the "
                        + profile.code() + " profile");
            }
            long telegramId;
            try {
                telegramId = Long.parseLong(raw);
            } catch (NumberFormatException invalid) {
                throw new SeedRefusedException("Seed/reset refused: the " + profile.code()
                        + " test account id is not numeric");
            }
            if (!allowed.contains(telegramId)) {
                throw new SeedRefusedException("Seed/reset refused: the " + profile.code()
                        + " test account is not in the allowed Telegram ids");
            }
            if (result.containsValue(telegramId)) {
                throw new SeedRefusedException("Seed/reset refused: profiles must use distinct test accounts");
            }
            result.put(profile, telegramId);
        }
        return result;
    }

    private void write(SeedProfile profile, UserAccount user, long randomSeed, LocalDate startDate,
                       SeedRecord record) {
        OwnerContext owner = new OwnerContext(user.id());
        TelegramUpdateKey key = new TelegramUpdateKey(
                KEY_PREFIX + profile.code() + ":" + randomSeed + ":" + startDate, record.index());
        Instant occurredAt = record.date().atTime(record.time()).atZone(user.timezone()).toInstant();

        if (record.type() == EntryType.CHECKIN) {
            CreateCheckinCommand command = new CreateCheckinCommand(owner,
                    CheckinCategory.fromCode((String) record.payload().get("category")),
                    (Integer) record.payload().get("score"), occurredAt, key);
            Entry created = entries.createCheckin(command);
            if (record.redelivered()) requireSame(created, entries.createCheckin(command));
            return;
        }

        Map<String, Object> sourceRef = new LinkedHashMap<>();
        sourceRef.put("label", SyntheticProfileGenerator.LABEL);
        sourceRef.put("seed_profile", profile.code());
        CreateDraftCommand command = new CreateDraftCommand(owner, record.type(), SourceKind.SEED, sourceRef,
                occurredAt, record.payload(), record.fieldOrigins(), key);
        DraftCreationResult created = entries.createDraft(command);
        if (created.outcome() == DraftCreationResult.Outcome.ACTIVE_DRAFT_EXISTS) {
            throw new IllegalStateException("A demo account has an unrelated active draft; seed will not touch it");
        }
        Entry entry = created.entry();
        if (record.redelivered()) {
            DraftCreationResult repeat = entries.createDraft(command);
            if (repeat.outcome() != DraftCreationResult.Outcome.EXISTING_UPDATE) {
                throw new IllegalStateException("Repeated delivery was not recognised");
            }
            requireSame(entry, repeat.entry());
        }

        if (record.cancelledDraft()) {
            if (entry.status() == EntryStatus.DRAFT) {
                entries.cancel(owner, entry.id(), entry.revision());
            } else if (entry.status() != EntryStatus.CANCELLED) {
                throw new IllegalStateException("Seed draft key is occupied by a record in status "
                        + entry.status().code());
            }
            return;
        }

        if (entry.status() == EntryStatus.DRAFT) {
            entry = entries.confirm(new ConfirmEntryCommand(owner, entry.id(), key.storageKey(),
                    entry.revision()));
        }
        if (entry.status() != EntryStatus.CONFIRMED && entry.status() != EntryStatus.DELETED) {
            throw new IllegalStateException("Unexpected seed record status " + entry.status().code());
        }
        if (record.changed() && entry.status() == EntryStatus.CONFIRMED && entry.revision() == CONFIRMED_UNCORRECTED_REVISION) {
            entries.patch(new PatchEntryCommand(owner, entry.id(), entry.revision(), null,
                    record.correction(), record.correctionOrigins()));
        }
    }

    private static void requireSame(Entry first, Entry second) {
        if (!first.id().equals(second.id())) {
            throw new IllegalStateException("Repeated delivery created a different record");
        }
    }

    private static SeedReport.ProfileReport report(SeedProfile profile, UserAccount account, LocalDate start,
                                                   List<SeedRecord> records) {
        Map<String, Integer> byType = new LinkedHashMap<>();
        for (EntryType type : List.of(EntryType.MEAL, EntryType.METRICS, EntryType.CHECKIN)) byType.put(type.code(), 0);
        Set<LocalDate> withData = new TreeSet<>();
        int changed = 0;
        int redelivered = 0;
        int cancelled = 0;
        for (SeedRecord record : records) {
            if (record.cancelledDraft()) {
                cancelled++;
                continue;
            }
            byType.merge(record.type().code(), 1, Integer::sum);
            withData.add(record.date());
            if (record.changed()) changed++;
            if (record.redelivered()) redelivered++;
        }
        List<LocalDate> empty = new ArrayList<>();
        for (int day = 0; day < SyntheticProfileGenerator.DAYS; day++) {
            if (!withData.contains(start.plusDays(day))) empty.add(start.plusDays(day));
        }
        return new SeedReport.ProfileReport(profile, account.id(), SyntheticProfileGenerator.DAYS,
                records.size(), byType, changed, redelivered, cancelled, List.copyOf(empty));
    }
}
