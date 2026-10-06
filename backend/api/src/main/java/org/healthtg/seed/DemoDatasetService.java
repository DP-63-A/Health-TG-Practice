package org.healthtg.seed;

import org.healthtg.core.entry.ConfirmEntryCommand;
import org.healthtg.core.entry.CreateDraftCommand;
import org.healthtg.core.entry.DraftCreationResult;
import org.healthtg.core.entry.Entry;
import org.healthtg.core.entry.EntryCoreService;
import org.healthtg.core.entry.EntryStatus;
import org.healthtg.core.entry.OwnerContext;
import org.healthtg.core.entry.PatchEntryCommand;
import org.healthtg.core.entry.SourceKind;
import org.healthtg.core.entry.TelegramUpdateKey;
import org.healthtg.user.UserAccount;
import org.healthtg.user.UserService;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.data.mongodb.core.MongoTemplate;
import org.springframework.data.mongodb.core.query.Criteria;
import org.springframework.data.mongodb.core.query.Query;
import org.springframework.stereotype.Service;

import java.time.LocalDate;
import java.util.EnumMap;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;

@Service
@ConditionalOnProperty(name = "health-tg.core.storage.enabled", matchIfMissing = true)
public class DemoDatasetService {
    public static final String DATASET_MARKER = "be3-05-v1";

    private final EntryCoreService entries;
    private final UserService users;
    private final MongoTemplate mongoTemplate;
    private final SyntheticDatasetGenerator generator;

    @Autowired
    public DemoDatasetService(EntryCoreService entries, UserService users, MongoTemplate mongoTemplate) {
        this(entries, users, mongoTemplate, new SyntheticDatasetGenerator());
    }

    DemoDatasetService(EntryCoreService entries, UserService users, MongoTemplate mongoTemplate,
                       SyntheticDatasetGenerator generator) {
        this.entries = entries;
        this.users = users;
        this.mongoTemplate = mongoTemplate;
        this.generator = generator;
    }

    public int seed(String demoFlag, String mongoUri, DemoProfileOwners owners, long seed, LocalDate startDate) {
        DemoEnvironmentGuard.requireDemoEnvironment(demoFlag, mongoUri);
        Map<SyntheticProfile, UserAccount> accounts = new EnumMap<>(SyntheticProfile.class);
        for (SyntheticProfile profile : SyntheticProfile.values()) {
            accounts.put(profile, users.requireById(owners.owner(profile)));
        }
        if (accounts.values().stream().map(UserAccount::id).distinct().count() != SyntheticProfile.values().length) {
            throw new IllegalArgumentException("BE3-05 demo profiles must use three distinct user accounts");
        }

        Map<SyntheticProfile, SyntheticDatasetGenerator.SyntheticProfileData> datasets =
                new EnumMap<>(SyntheticProfile.class);
        for (SyntheticProfile profile : SyntheticProfile.values()) {
            datasets.put(profile, generator.generateProfile(profile, seed, startDate,
                    accounts.get(profile).timezone()));
        }

        int count = 0;
        for (SyntheticProfile profile : SyntheticProfile.values()) {
            UserAccount account = accounts.get(profile);
            var dataset = datasets.get(profile);
            OwnerContext owner = new OwnerContext(account.id());
            for (SyntheticEntry synthetic : dataset.entries()) {
                persist(owner, profile, startDate, seed, account.timezone().getId(), synthetic);
                count++;
            }
        }
        return count;
    }

    public long reset(String demoFlag, String mongoUri) {
        DemoEnvironmentGuard.requireDemoEnvironment(demoFlag, mongoUri);
        Query query = Query.query(new Criteria().andOperator(
                Criteria.where("sourceKind").is(SourceKind.SEED.code()),
                Criteria.where("sourceRef.demo_dataset").is(DATASET_MARKER),
                Criteria.where("sourceRef.profile").in(List.of("regular", "irregular", "incomplete"))));
        return mongoTemplate.remove(query, "entries").getDeletedCount();
    }

    private void persist(OwnerContext owner, SyntheticProfile profile, LocalDate startDate, long seed,
                         String timezone, SyntheticEntry synthetic) {
        String key = "be3-05:" + profile.code() + ":" + startDate + ":" + seed + ":" + timezone + ":"
                + synthetic.logicalKey();
        TelegramUpdateKey updateKey = new TelegramUpdateKey(key, 0);
        String submissionId = key;
        Map<String, Object> sourceRef = Map.of(
                "demo_dataset", DATASET_MARKER,
                "profile", profile.code(),
                "logical_key", synthetic.logicalKey(),
                "seed", seed,
                "start_date", startDate.toString(),
                "timezone", timezone);
        Map<String, Object> draftPayload = synthetic.initialPayload() == null
                ? synthetic.payload() : synthetic.initialPayload();
        CreateDraftCommand command = new CreateDraftCommand(owner, synthetic.type(), SourceKind.SEED, sourceRef,
                synthetic.occurredAt().toInstant(), draftPayload, synthetic.fieldOrigins(), updateKey);

        for (int delivery = 0; delivery < synthetic.deliveries(); delivery++) {
            var result = entries.createDraft(command);
            if (result.outcome() == DraftCreationResult.Outcome.ACTIVE_DRAFT_EXISTS) {
                throw new IllegalStateException("A different draft is active for a configured demo account");
            }
            Entry entry = result.entry();
            if (result.outcome() == DraftCreationResult.Outcome.EXISTING_UPDATE) {
                validateExisting(entry, owner, synthetic, sourceRef, key, updateKey);
                continue;
            }
            if (entry.status() != EntryStatus.DRAFT) continue;

            if (!entry.payload().equals(synthetic.payload())) {
                entry = entries.patch(new PatchEntryCommand(owner, entry.id(), entry.revision(), null,
                        synthetic.payload(), synthetic.fieldOrigins()));
            }
            if (synthetic.cancelled()) {
                entries.cancel(owner, entry.id(), entry.revision());
            } else {
                entries.confirm(new ConfirmEntryCommand(owner, entry.id(), submissionId, entry.revision()));
            }
        }
    }

    private static void validateExisting(Entry entry, OwnerContext owner, SyntheticEntry synthetic,
                                         Map<String, Object> sourceRef, String key, TelegramUpdateKey updateKey) {
        EntryStatus expectedStatus = synthetic.cancelled() ? EntryStatus.CANCELLED : EntryStatus.CONFIRMED;
        String expectedSubmissionId = synthetic.cancelled() ? null : key;
        Map<String, Object> expectedSourceRef = new HashMap<>(sourceRef);
        expectedSourceRef.put("telegram_update_id", updateKey.updateId());
        if (!entry.ownerId().equals(owner.userId())
                || entry.type() != synthetic.type()
                || entry.sourceKind() != SourceKind.SEED
                || !entry.sourceRef().equals(expectedSourceRef)
                || !entry.occurredAt().equals(synthetic.occurredAt().toInstant())
                || !entry.payload().equals(synthetic.payload())
                || !entry.fieldOrigins().equals(synthetic.fieldOrigins())
                || entry.status() != expectedStatus
                || !Objects.equals(entry.submissionId(), expectedSubmissionId)
                || !entry.telegramUpdateKey().equals(updateKey.storageKey())) {
            throw new IllegalStateException("Existing BE3-05 record differs from the expected dataset; run reset first");
        }
    }
}
