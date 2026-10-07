package org.healthtg.core.entry;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.mockito.ArgumentCaptor;
import org.springframework.data.mongodb.core.FindAndModifyOptions;
import org.springframework.data.mongodb.core.MongoTemplate;
import org.springframework.data.mongodb.core.query.Query;
import org.springframework.data.mongodb.core.query.Update;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class EntryNumericSafetyTest {
    @ParameterizedTest
    @ValueSource(strings = {"1E+100000000", "1E-100000000", "0E-100000000", "1E+2001", "1E-2001", "1E309", "-1E-400"})
    void rejectsUnsafeNumbersInEveryNumericField(String text) {
        BigDecimal value = new BigDecimal(text);
        for (String code : List.of("steps", "sleep_duration_min", "heart_rate")) {
            assertThrows(EntryValidationException.class, () -> EntryPayloadValidator.validateDraft(
                    EntryType.METRICS, Map.of("code", code, "value", value)), code);
        }
        assertThrows(EntryValidationException.class, () -> EntryPayloadValidator.validateDraft(
                EntryType.MEAL, Map.of("description", "meal", "mass_g", value)));
        for (String nutrient : List.of("energy_kcal", "protein_g", "fat_g", "carbs_g")) {
            assertThrows(EntryValidationException.class, () -> EntryPayloadValidator.validateDraft(
                    EntryType.MEAL, Map.of("description", "meal", "nutrients", Map.of(nutrient, value))), nutrient);
        }
    }

    @Test void boundsPrecisionWithoutRoundingAndPreservesNullAndIntegerScores() {
        for (String text : List.of("0", "125.7500", "1E-400", "0E+2000", "1E-2000",
                "1.7976931348623157E308", "1." + "0".repeat(1999))) {
            assertDoesNotThrow(() -> EntryPayloadValidator.validateDraft(EntryType.MEAL,
                    Map.of("description", "meal", "mass_g", new BigDecimal(text))), text.substring(0, Math.min(12, text.length())));
        }
        assertThrows(EntryValidationException.class, () -> EntryPayloadValidator.validateDraft(EntryType.MEAL,
                Map.of("description", "meal", "mass_g", new BigDecimal("1." + "0".repeat(2000)))));
        Map<String, Object> nullable = new LinkedHashMap<>();
        nullable.put("description", "Печёное яблоко 🍎"); nullable.put("mass_g", null);
        assertDoesNotThrow(() -> EntryPayloadValidator.validateDraft(EntryType.MEAL, nullable));
        assertDoesNotThrow(() -> EntryPayloadValidator.validateDraft(EntryType.CHECKIN, Map.of("category", "mood", "score", 3)));
        assertThrows(EntryValidationException.class, () -> EntryPayloadValidator.validateDraft(EntryType.CHECKIN,
                Map.of("category", "mood", "score", new BigDecimal("3.0"))));
    }

    @Test void acceptsFractionalStepAndSleepValuesWithoutRounding() {
        for (String code : List.of("steps", "sleep_duration_min")) {
            assertDoesNotThrow(() -> EntryPayloadValidator.validateDraft(EntryType.METRICS,
                    Map.of("code", code, "value", new BigDecimal("123.456789"))), code);
        }
    }

    @ParameterizedTest
    @ValueSource(strings = {"1E+100000000", "1E-100000000", "125.7500"})
    void storeUsesCompactExactEncodingEvenForLegacyNumbersAndHistory(String text) {
        MongoEntryRepository repository = mock(MongoEntryRepository.class);
        MongoTemplate mongo = mock(MongoTemplate.class);
        MongoEntryStore store = new MongoEntryStore(repository, mongo);
        when(repository.save(any())).thenAnswer(invocation -> invocation.getArgument(0));
        BigDecimal value = new BigDecimal(text);
        Entry original = entry(Map.of("description", "12.5", "mass_g", value,
                "nutrients", Map.of("energy_kcal", value)));
        assertEquals(original, store.save(original));
        ArgumentCaptor<MongoEntryDocument> document = ArgumentCaptor.forClass(MongoEntryDocument.class);
        verify(repository).save(document.capture());
        assertEquals(value.toString(), document.getValue().payload().get("mass_g"));
        assertEquals(value.toString(), ((Map<?, ?>) document.getValue().payload().get("nutrients")).get("energy_kcal"));
        assertEquals("12.5", document.getValue().payload().get("description"));
        store.replaceIfCurrent(original, original);
        ArgumentCaptor<Update> update = ArgumentCaptor.forClass(Update.class);
        verify(mongo).findAndModify(any(Query.class), update.capture(), any(FindAndModifyOptions.class), eq(MongoEntryDocument.class));
        Map<?, ?> push = (Map<?, ?>) update.getValue().getUpdateObject().get("$push");
        Update.Modifiers history = (Update.Modifiers) push.get("history");
        Object each = history.getModifiers().stream().filter(modifier -> modifier.getKey().equals("$each"))
                .findFirst().orElseThrow().getValue();
        Map<?, ?> snapshot = (Map<?, ?>) (each instanceof List<?> list ? list.getFirst() : ((Object[]) each)[0]);
        assertEquals(value.toString(), ((Map<?, ?>) snapshot.get("payload")).get("mass_g"));
    }

    @Test void fieldOriginsEncodingPreservesCollidingKeysAndOrdinaryDottedKeys() {
        MongoEntryRepository repository = mock(MongoEntryRepository.class);
        MongoTemplate mongo = mock(MongoTemplate.class);
        MongoEntryStore store = new MongoEntryStore(repository, mongo);
        Map<String, String> origins = new LinkedHashMap<>();
        origins.put(".．", "reported");
        origins.put("．.", "computed");
        origins.put("payload.mass_g", "estimated");
        Entry original = entry(Map.of("description", "meal"), origins);
        when(repository.save(any())).thenAnswer(invocation -> invocation.getArgument(0));

        assertEquals(original, store.save(original));

        ArgumentCaptor<MongoEntryDocument> document = ArgumentCaptor.forClass(MongoEntryDocument.class);
        verify(repository).save(document.capture());
        assertEquals(2, document.getValue().fieldOriginsEncodingVersion());
        assertTrue(document.getValue().fieldOrigins().keySet().stream().noneMatch(key -> key.contains(".")));
    }

    @Test void readsLegacyFieldOriginsEncoding() {
        MongoEntryRepository repository = mock(MongoEntryRepository.class);
        MongoEntryStore store = new MongoEntryStore(repository, mock(MongoTemplate.class));
        Entry original = entry(Map.of("description", "meal"));
        MongoEntryDocument legacy = new MongoEntryDocument(original.id().toString(), original.ownerId().toString(),
                original.type().code(), original.status().code(), original.sourceKind().code(), original.sourceRef(),
                original.occurredAt(), original.createdAt(), original.updatedAt(), original.revision(),
                original.payload(), Map.of("payload\uFF0Emass_g", "reported", "literal\uFF0E\uFF0Edot", "computed"),
                null, null, original.telegramUpdateKey(), List.of());
        when(repository.findById(original.id().toString())).thenReturn(Optional.of(legacy));

        assertEquals(Map.of("payload.mass_g", "reported", "literal．dot", "computed"),
                store.findById(original.id()).orElseThrow().fieldOrigins());
    }

    private static Entry entry(Map<String, Object> payload) {
        return entry(payload, Map.of());
    }

    private static Entry entry(Map<String, Object> payload, Map<String, String> fieldOrigins) {
        Instant now = Instant.parse("2026-10-07T00:00:00Z");
        return new Entry(UUID.randomUUID(), UUID.randomUUID(), EntryType.MEAL, EntryStatus.DRAFT,
                SourceKind.TEXT, Map.of(), now, now, now, 1, payload, fieldOrigins, null, "numeric:test");
    }
}
