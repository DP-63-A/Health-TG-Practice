package org.healthtg.bot.recognition;

import com.fasterxml.jackson.databind.node.ObjectNode;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.HashMap;
import static org.junit.jupiter.api.Assertions.*;
import static org.healthtg.bot.recognition.RecognitionException.Code.*;

class RecognitionResponseParserTest {
    private final RecognitionResponseParser parser = new RecognitionResponseParser();
    static ObjectNode fixture() throws Exception {
        return (ObjectNode) RecognitionJson.MAPPER.readTree(RecognitionJson.resource("fixture.json"));
    }
    static void value(ObjectNode node, String field, String value, String origin) {
        node.put(field, value);
        ((ObjectNode) node.get("field_origins")).put(field, origin);
    }
    private void invalid(String json) { assertEquals(INVALID_RESPONSE, assertThrows(RecognitionException.class, () -> parser.parse(json)).code()); }

    @Test void unknownFieldsRemainUnknownAndNeedClarification() throws Exception {
        var result = parser.parse(fixture().toString());
        assertNull(result.massG()); assertNull(result.nutrients().energyKcal()); assertNull(result.occurredAt());
        assertTrue(result.needsClarification());
        assertTrue(result.missingFields().containsAll(java.util.List.of("mass_g", "nutrients.energy_kcal", "occurred_at", "nutrients_basis")));
        assertEquals("Учебный fixture: печёное яблоко 🍎", result.description());
        assertEquals(java.util.Map.of("description", "estimated"), result.fieldOrigins());
    }
    @ParameterizedTest @ValueSource(strings={"per_100g", "per_serving", "unknown"})
    void explicitZeroAndBasisArePreservedWithoutRecalculation(String basis) throws Exception {
        var node = fixture(); node.put("mass_g", 250); node.put("nutrients_basis", basis);
        ((ObjectNode) node.get("field_origins")).put("mass_g", "estimated");
        ((ObjectNode) node.get("nutrients")).put("energy_kcal", BigDecimal.ZERO);
        ((ObjectNode) node.get("field_origins")).put("nutrients.energy_kcal", "extracted");
        var result = parser.parse(node.toString());
        assertEquals(0, result.massG().compareTo(new BigDecimal("250")));
        assertEquals(0, result.nutrients().energyKcal().compareTo(BigDecimal.ZERO));
        assertEquals(basis, result.nutrientsBasis());
        assertFalse(result.missingFields().contains("nutrients.energy_kcal"));
    }
    @Test void completeResultAndPartialDateHaveDifferentClarificationState() throws Exception {
        var node=fixture(); node.put("mass_g", 100); node.put("nutrients_basis", "per_100g");
        var origins=(ObjectNode)node.get("field_origins"); origins.put("mass_g","estimated");
        for (String field: java.util.List.of("energy_kcal","protein_g","fat_g","carbs_g")) {
            ((ObjectNode)node.get("nutrients")).put(field, 1); origins.put("nutrients."+field,"estimated");
        }
        value(node,"local_date","2026-09-28","extracted"); value(node,"local_time","12:30:00","extracted");
        assertEquals(java.util.List.of("occurred_at"),parser.parse(node.toString()).missingFields());
        value(node,"occurred_at","2026-09-28T12:30:00+03:00","extracted");
        var result=parser.parse(node.toString()); assertFalse(result.needsClarification());
        assertEquals("2026-09-28T12:30:00+03:00",result.occurredAt());
    }
    @ParameterizedTest @ValueSource(strings={"", " ", "\n\t"})
    void emptyResponse(String json) { assertEquals(EMPTY_RESPONSE,assertThrows(RecognitionException.class,()->parser.parse(json)).code()); }
    @ParameterizedTest @ValueSource(strings={"{", "[]", "null", "42", "{}", "{\"is_food\":true,\"is_food\":false}", "{} {}"})
    void malformedStructure(String json) { invalid(json); }
    @Test void trailingAndDuplicateFieldsEvenInOtherwiseValidDocument() throws Exception {
        String json=fixture().toString(); invalid(json+" {}"); invalid(json.replace("\"is_food\":true", "\"is_food\":true,\"is_food\":true"));
    }
    @Test void unrecognizedFoodIsNotSuccess() throws Exception {
        var node=fixture(); node.put("is_food",false);
        assertEquals(REFUSED,assertThrows(RecognitionException.class,()->parser.parse(node.toString())).code());
    }
    @Test void inconsistentOriginsMissingKeysAndUnexpectedFields() throws Exception {
        var node=fixture(); ((ObjectNode)node.get("field_origins")).put("description","reported"); invalid(node.toString());
        node=fixture(); ((ObjectNode)node.get("field_origins")).putNull("description"); invalid(node.toString());
        node=fixture(); ((ObjectNode)node.get("field_origins")).put("mass_g","estimated"); invalid(node.toString());
        node=fixture(); node.remove("mass_g"); invalid(node.toString());
        node=fixture(); node.put("owner_id","should-never-be-a-server-entry"); invalid(node.toString());
        node=fixture(); node.put("description"," \t "); invalid(node.toString());
        node=fixture(); node.put("description","a".repeat(2001)); invalid(node.toString());
    }
    @ParameterizedTest @ValueSource(strings={"-1", "\"NaN\"", "\"1\"", "NaN"})
    void invalidMass(String mass) throws Exception {
        String json=fixture().toString().replaceFirst("\"mass_g\":null", "\"mass_g\":"+mass);
        // Set origin separately so numeric tests cannot pass merely due to absent provenance.
        if (!mass.equals("NaN")) {
            var node=(ObjectNode)RecognitionJson.MAPPER.readTree(json);
            ((ObjectNode)node.get("field_origins")).put("mass_g","estimated"); json=node.toString();
        }
        invalid(json);
    }
    @ParameterizedTest @ValueSource(strings={"2026-02-30T10:00:00Z", "2026-09-28T12:30:00", "2026-09-28T12:30:00+03:00:01", "yesterday"})
    void invalidTimestamp(String date) throws Exception { var n=fixture();value(n,"occurred_at",date,"extracted");invalid(n.toString()); }
    @Test void inconsistentDatesAndInferredDatesRejected() throws Exception {
        var n=fixture();value(n,"occurred_at","2026-09-28T12:30:00Z","extracted");value(n,"local_date","2026-09-27","extracted");invalid(n.toString());
        n=fixture();value(n,"local_date","2026-09-28","estimated");invalid(n.toString());
        n=fixture();value(n,"local_time","25:00:00","extracted");invalid(n.toString());
    }
    @Test void resultCollectionsDoNotLeakMutableState() throws Exception {
        var source=parser.parse(fixture().toString()); var origins=new HashMap<>(source.fieldOrigins());var missing=new ArrayList<>(source.missingFields());
        var result=new RecognitionResult(source.description(),null,source.nutrients(),"unknown",null,null,null,origins,missing);
        origins.clear();missing.clear();assertFalse(result.fieldOrigins().isEmpty());assertFalse(result.missingFields().isEmpty());
        assertThrows(UnsupportedOperationException.class,()->result.fieldOrigins().clear());assertThrows(UnsupportedOperationException.class,()->result.missingFields().clear());
    }
    @Test void responseSizeBoundUsesUtf8Bytes() {
        assertEquals(RESPONSE_TOO_LARGE, assertThrows(RecognitionException.class,()->parser.parse("ё".repeat(33_000))).code());
    }
}
