package org.healthtg.core.entry;

import org.springframework.stereotype.Repository;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.data.mongodb.core.FindAndModifyOptions;
import org.springframework.data.mongodb.core.MongoTemplate;
import org.springframework.data.mongodb.core.query.Criteria;
import org.springframework.data.mongodb.core.query.Query;
import org.springframework.data.mongodb.core.query.Update;

import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.LinkedHashMap;
import java.util.Map;

@Repository
@ConditionalOnProperty(name = "health-tg.core.storage.enabled", matchIfMissing = true)
class MongoEntryStore implements EntryStore {
    private static final char MAP_KEY_ESCAPE = '\uFF0E';

    private final MongoEntryRepository repository;
    private final MongoTemplate mongoTemplate;

    MongoEntryStore(MongoEntryRepository repository, MongoTemplate mongoTemplate) {
        this.repository = repository;
        this.mongoTemplate = mongoTemplate;
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

    @Override
    public Optional<Entry> replaceIfCurrent(Entry current, Entry replacement) {
        Query query = Query.query(Criteria.where("_id").is(current.id().toString())
                .and("ownerId").is(current.ownerId().toString())
                .and("revision").is(current.revision())
                .and("status").is(current.status().code()));
        Update update = new Update()
                .set("status", replacement.status().code())
                .set("occurredAt", replacement.occurredAt())
                .set("updatedAt", replacement.updatedAt())
                .set("revision", replacement.revision())
                .set("payload", encodeNumbers(replacement.payload()))
                .set("fieldOrigins", encodeMapKeys(replacement.fieldOrigins()))
                .set("submissionId", replacement.submissionId())
                .push("history").slice(-10).each(snapshot(current));
        MongoEntryDocument changed = mongoTemplate.findAndModify(query, update,
                FindAndModifyOptions.options().returnNew(true), MongoEntryDocument.class);
        return Optional.ofNullable(changed).map(MongoEntryStore::toDomain);
    }

    private static Map<String, Object> snapshot(Entry entry) {
        Map<String, Object> snapshot = new LinkedHashMap<>();
        snapshot.put("revision", entry.revision());
        snapshot.put("status", entry.status().code());
        snapshot.put("occurredAt", entry.occurredAt());
        snapshot.put("updatedAt", entry.updatedAt());
        snapshot.put("payload", encodeNumbers(entry.payload()));
        snapshot.put("fieldOrigins", encodeMapKeys(entry.fieldOrigins()));
        snapshot.put("submissionId", entry.submissionId());
        return snapshot;
    }

    private static MongoEntryDocument toDocument(Entry entry) {
        return new MongoEntryDocument(entry.id().toString(), entry.ownerId().toString(), entry.type().code(),
                entry.status().code(), entry.sourceKind().code(), entry.sourceRef(), entry.occurredAt(),
                entry.createdAt(), entry.updatedAt(), entry.revision(), encodeNumbers(entry.payload()), encodeMapKeys(entry.fieldOrigins()),
                entry.submissionId(), entry.telegramUpdateKey(), List.of());
    }

    private static Entry toDomain(MongoEntryDocument document) {
        return new Entry(UUID.fromString(document.id()), UUID.fromString(document.ownerId()),
                EntryType.fromCode(document.type()), EntryStatus.fromCode(document.status()),
                SourceKind.fromCode(document.sourceKind()), document.sourceRef(), document.occurredAt(),
                document.createdAt(), document.updatedAt(), document.revision(), decodeNumbers(document.payload()),
                decodeMapKeys(document.fieldOrigins()), document.submissionId(), document.telegramUpdateKey());
    }

    // Untyped Mongo maps otherwise return BigDecimal as String (or BSON Decimal128).
    // Only contract numeric fields are decoded; free text such as "12.5" remains text.
    private static Map<String, Object> encodeNumbers(Map<String, Object> payload) {
        Map<String, Object> result = new LinkedHashMap<>(payload);
        result.replaceAll((key, value) -> value instanceof java.math.BigDecimal decimal ? decimal.toString() : value);
        if (payload.get("nutrients") instanceof Map<?, ?> map) {
            Map<String, Object> nutrients = new LinkedHashMap<>();
            map.forEach((key, value) -> nutrients.put(key.toString(),
                    value instanceof java.math.BigDecimal decimal ? decimal.toString() : value));
            result.put("nutrients", nutrients);
        }
        return result;
    }

    private static Map<String, Object> decodeNumbers(Map<String, Object> payload) {
        Map<String, Object> result = new LinkedHashMap<>(payload);
        for (String key : List.of("value", "mass_g")) {
            if (result.containsKey(key)) result.put(key, decimal(result.get(key)));
        }
        if (payload.get("nutrients") instanceof Map<?, ?> map) {
            Map<String, Object> nutrients = new LinkedHashMap<>();
            map.forEach((key, value) -> nutrients.put(key.toString(), decimal(value)));
            result.put("nutrients", nutrients);
        }
        return result;
    }

    private static Object decimal(Object value) {
        if (value instanceof org.bson.types.Decimal128 decimal) return decimal.bigDecimalValue();
        if (value instanceof String text) return new java.math.BigDecimal(text);
        return value;
    }

    private static Map<String, String> encodeMapKeys(Map<String, String> values) {
        Map<String, String> encoded = new LinkedHashMap<>();
        values.forEach((key, value) -> {
            StringBuilder safeKey = new StringBuilder(key.length());
            for (int index = 0; index < key.length(); index++) {
                char character = key.charAt(index);
                if (character == MAP_KEY_ESCAPE) safeKey.append(MAP_KEY_ESCAPE).append(MAP_KEY_ESCAPE);
                else if (character == '.') safeKey.append(MAP_KEY_ESCAPE);
                else safeKey.append(character);
            }
            encoded.put(safeKey.toString(), value);
        });
        return encoded;
    }

    private static Map<String, String> decodeMapKeys(Map<String, String> values) {
        Map<String, String> decoded = new LinkedHashMap<>();
        values.forEach((key, value) -> {
            StringBuilder originalKey = new StringBuilder(key.length());
            for (int index = 0; index < key.length(); index++) {
                char character = key.charAt(index);
                if (character == MAP_KEY_ESCAPE) {
                    if (index + 1 < key.length() && key.charAt(index + 1) == MAP_KEY_ESCAPE) {
                        originalKey.append(MAP_KEY_ESCAPE);
                        index++;
                    } else {
                        originalKey.append('.');
                    }
                } else {
                    originalKey.append(character);
                }
            }
            decoded.put(originalKey.toString(), value);
        });
        return decoded;
    }
}
