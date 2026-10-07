package org.healthtg.bot.recognition;

import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import java.net.*;
import java.net.http.HttpClient;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.*;
import java.util.concurrent.atomic.*;
import static org.junit.jupiter.api.Assertions.*;

/** Local HTTP only: no external model calls, fixture answers are transport test data. */
class HealthWatchProviderTest {
    @ParameterizedTest @ValueSource(strings={"health_screenshot","watch_photo"})
    void selectedClassUsesMetricSchemaAndContainsNoFixtureOrTools(String kind) throws Exception {
        var requestBody=new AtomicReference<String>();var calls=new AtomicInteger();var json=RecognitionJson.resource("fixture-metrics.json").replace("health_screenshot",kind);
        String envelope=RecognitionJson.MAPPER.writeValueAsString(Map.of("candidates",List.of(Map.of("finishReason","STOP","content",Map.of("parts",List.of(Map.of("text",json)))))));
        var server=HttpServer.create(new InetSocketAddress("127.0.0.1",0),0);
        server.createContext("/",e->{calls.incrementAndGet();requestBody.set(new String(e.getRequestBody().readAllBytes(),StandardCharsets.UTF_8));byte[] bytes=envelope.getBytes(StandardCharsets.UTF_8);e.sendResponseHeaders(200,bytes.length);e.getResponseBody().write(bytes);e.close();});server.start();
        try {
            var provider=new GeminiRecognitionProvider(HttpClient.newBuilder().followRedirects(HttpClient.Redirect.NEVER).build(),URI.create("http://127.0.0.1:"+server.getAddress().getPort()+"/"),"synthetic-secret",Duration.ofSeconds(2));
            var result=provider.recognize(new ImageValidator.ValidatedImage(new byte[]{1,2,3},"image/png"),kind);
            assertEquals(1,calls.get());assertEquals(json,result.json());var request=RecognitionJson.MAPPER.readTree(requestBody.get());
            var schema=com.networknt.schema.JsonSchemaFactory.getInstance(com.networknt.schema.SpecVersion.VersionFlag.V202012)
                    .getSchema(request.at("/generationConfig/responseJsonSchema"));
            var candidate=RecognitionJson.MAPPER.readTree(json);assertTrue(schema.validate(candidate).isEmpty());
            var metrics=(com.fasterxml.jackson.databind.node.ArrayNode)candidate.get("metrics");metrics.add(metrics.get(0).deepCopy());
            assertFalse(schema.validate(candidate).isEmpty(),"Sent schema must reject more than three metrics");
            assertTrue(requestBody.get().contains(kind));assertTrue(request.at("/systemInstruction/parts/0/text").asText().contains("untrusted data"));
            assertFalse(request.has("tools"));assertFalse(requestBody.get().contains("4200"));assertFalse(requestBody.get().contains("synthetic-secret"));
        } finally {server.stop(0);}
    }
}
