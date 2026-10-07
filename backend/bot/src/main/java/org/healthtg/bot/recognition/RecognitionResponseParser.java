package org.healthtg.bot.recognition;

import com.fasterxml.jackson.databind.JsonNode;
import com.networknt.schema.JsonSchema;
import com.networknt.schema.JsonSchemaFactory;
import com.networknt.schema.SpecVersion;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.time.LocalDate;
import java.time.LocalTime;
import java.time.OffsetDateTime;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import static org.healthtg.bot.recognition.RecognitionException.Code.*;

public final class RecognitionResponseParser {
    private final JsonSchema schema = JsonSchemaFactory.getInstance(SpecVersion.VersionFlag.V202012)
            .getSchema(RecognitionJson.resource("response-schema.json"));

    public RecognitionResult parse(String json) throws RecognitionException {
        if (json == null || json.isBlank()) throw new RecognitionException(EMPTY_RESPONSE);
        if (json.getBytes(StandardCharsets.UTF_8).length > RecognitionJson.MAX_RESPONSE_BYTES)
            throw new RecognitionException(RESPONSE_TOO_LARGE);
        try {
            JsonNode node = RecognitionJson.MAPPER.readTree(json);
            if (!schema.validate(node).isEmpty()) throw new RecognitionException(INVALID_RESPONSE);
            if (!node.get("is_food").booleanValue()) throw new RecognitionException(REFUSED);
            var origins = new LinkedHashMap<String, String>();
            var missing = new ArrayList<String>();
            var values = new LinkedHashMap<String, JsonNode>();
            values.put("description", node.get("description"));
            values.put("mass_g", node.get("mass_g"));
            node.get("nutrients").fields().forEachRemaining(e -> values.put("nutrients." + e.getKey(), e.getValue()));
            for (String field : List.of("occurred_at", "local_date", "local_time")) values.put(field, node.get(field));
            var originNode = node.get("field_origins");
            for (var entry : values.entrySet()) {
                String field = entry.getKey();
                JsonNode value = entry.getValue(), origin = originNode.get(field);
                if (value.isNull()) {
                    if (!origin.isNull()) throw new RecognitionException(INVALID_RESPONSE);
                    if (!field.equals("local_date") && !field.equals("local_time")) missing.add(field);
                } else {
                    if (origin.isNull()) throw new RecognitionException(INVALID_RESPONSE);
                    if (value.isTextual() && value.textValue().isBlank()) throw new RecognitionException(INVALID_RESPONSE);
                    origins.put(field, origin.textValue());
                }
            }
            String occurred = text(node, "occurred_at"), date = text(node, "local_date"), time = text(node, "local_time");
            if (occurred != null) {
                // OffsetDateTime additionally accepts offsets with seconds, outside RFC 3339.
                if (!occurred.matches("[0-9]{4}-[0-9]{2}-[0-9]{2}[Tt][0-9]{2}:[0-9]{2}:[0-9]{2}(\\.[0-9]{1,9})?([Zz]|[+-][0-9]{2}:[0-9]{2})"))
                    throw new RecognitionException(INVALID_RESPONSE);
                var offset = OffsetDateTime.parse(occurred);
                if ((date != null && !offset.toLocalDate().equals(LocalDate.parse(date)))
                        || (time != null && !offset.toLocalTime().equals(LocalTime.parse(time))))
                    throw new RecognitionException(INVALID_RESPONSE);
            }
            if (date != null) LocalDate.parse(date);
            if (time != null) LocalTime.parse(time);
            // Dates cannot be inferred from visual food appearance.
            for (String field : List.of("occurred_at", "local_date", "local_time"))
                if (origins.containsKey(field) && !origins.get(field).equals("extracted"))
                    throw new RecognitionException(INVALID_RESPONSE);
            String basis = node.get("nutrients_basis").textValue();
            if (basis.equals("unknown")) missing.add("nutrients_basis");
            var n = node.get("nutrients");
            return new RecognitionResult(text(node, "description"), number(node, "mass_g"),
                    new RecognitionResult.Nutrients(number(n,"energy_kcal"),number(n,"protein_g"),number(n,"fat_g"),number(n,"carbs_g")),
                    basis, occurred, date, time, origins, missing);
        } catch (IOException | DateTimeParseException e) { throw new RecognitionException(INVALID_RESPONSE); }
    }
    private static String text(JsonNode node, String key) { return node.get(key).isNull() ? null : node.get(key).textValue(); }
    private static java.math.BigDecimal number(JsonNode node, String key) { return node.get(key).isNull() ? null : node.get(key).decimalValue(); }
}
