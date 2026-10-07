package org.healthtg.bot.recognition;

import com.fasterxml.jackson.core.JsonFactory;
import com.fasterxml.jackson.core.StreamReadConstraints;
import com.fasterxml.jackson.core.StreamReadFeature;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.IOException;
import java.nio.charset.StandardCharsets;

final class RecognitionJson {
    static final int MAX_RESPONSE_BYTES = 64 * 1024;
    static final ObjectMapper MAPPER = new ObjectMapper(JsonFactory.builder()
            .enable(StreamReadFeature.STRICT_DUPLICATE_DETECTION)
            .streamReadConstraints(StreamReadConstraints.builder().maxNestingDepth(20)
                    .maxStringLength(MAX_RESPONSE_BYTES).maxNumberLength(100).build()).build())
            .enable(DeserializationFeature.FAIL_ON_TRAILING_TOKENS)
            .enable(DeserializationFeature.USE_BIG_DECIMAL_FOR_FLOATS);
    private RecognitionJson() { }
    static String resource(String name) {
        try (var input = RecognitionJson.class.getResourceAsStream("/recognition/" + name)) {
            if (input == null) throw new IllegalStateException("Missing recognition resource: " + name);
            return new String(input.readAllBytes(), StandardCharsets.UTF_8);
        } catch (IOException e) { throw new IllegalStateException("Cannot read recognition resource", e); }
    }
}
