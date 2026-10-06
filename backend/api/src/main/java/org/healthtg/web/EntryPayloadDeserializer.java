package org.healthtg.web;

import com.fasterxml.jackson.core.JsonParser;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.DeserializationContext;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.JsonDeserializer;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.io.IOException;
import java.util.Map;

/** Preserve decimal signs and precision in entry payloads, including nested nutrients. */
public final class EntryPayloadDeserializer extends JsonDeserializer<Map<String, Object>> {
    @Override
    public Map<String, Object> deserialize(JsonParser parser, DeserializationContext context) throws IOException {
        // An immutable reader keeps this policy local to PATCH payloads. Integer scores
        // retain their integer type, and the application's mapper/stream limits still apply.
        return ((ObjectMapper) parser.getCodec()).readerFor(new TypeReference<Map<String, Object>>() { })
                .with(DeserializationFeature.USE_BIG_DECIMAL_FOR_FLOATS)
                .readValue(parser);
    }
}
