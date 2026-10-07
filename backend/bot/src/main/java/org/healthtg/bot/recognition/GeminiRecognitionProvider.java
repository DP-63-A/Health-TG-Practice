package org.healthtg.bot.recognition;

import com.fasterxml.jackson.databind.JsonNode;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.ByteBuffer;
import java.time.Duration;
import java.util.Base64;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.Flow;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import static org.healthtg.bot.recognition.RecognitionException.Code.*;

/** One bounded request; no retries, file uploads or fallback. */
public final class GeminiRecognitionProvider implements RecognitionProvider {
    public static final String DEFAULT_MODEL = "gemini-3.5-flash-lite";
    private final HttpClient client;
    private final URI endpoint;
    private final String apiKey;
    private final Duration timeout;

    public GeminiRecognitionProvider(String apiKey, String model) throws RecognitionException {
        this(HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(10)).followRedirects(HttpClient.Redirect.NEVER).build(),
                endpoint(model), apiKey, Duration.ofSeconds(30));
    }
    // Package-private injection for offline HTTP boundary tests, never user-configurable endpoint.
    GeminiRecognitionProvider(HttpClient client, URI endpoint, String apiKey, Duration timeout) throws RecognitionException {
        // Reject every unsafe header character before HttpRequest can echo a secret in an exception.
        if (apiKey == null || apiKey.isBlank() || apiKey.chars().anyMatch(c -> c < 0x21 || c > 0x7e)
                || timeout.isZero() || timeout.isNegative() || timeout.compareTo(Duration.ofSeconds(30)) > 0)
            throw new RecognitionException(CONFIGURATION);
        this.client = client; this.endpoint = endpoint; this.apiKey = apiKey; this.timeout = timeout;
    }
    private static URI endpoint(String model) throws RecognitionException {
        if (model == null || !model.matches("gemini-[a-zA-Z0-9.-]{1,80}")) throw new RecognitionException(CONFIGURATION);
        return URI.create("https://generativelanguage.googleapis.com/v1beta/models/" + model + ":generateContent");
    }
    @Override public Mode mode() { return Mode.LIVE; }

    @Override public Response recognize(ImageValidator.ValidatedImage image) throws RecognitionException {
        final byte[] body;
        try {
            body = RecognitionJson.MAPPER.writeValueAsBytes(Map.of(
                    "systemInstruction", Map.of("parts", List.of(Map.of("text", RecognitionJson.resource("prompt.txt")))),
                    "contents", List.of(Map.of("role", "user", "parts", List.of(Map.of("inlineData", Map.of(
                            "mimeType", image.mimeType(), "data", Base64.getEncoder().encodeToString(image.bytes())))))),
                    "generationConfig", Map.of("responseMimeType", "application/json", "responseJsonSchema",
                            RecognitionJson.MAPPER.readTree(RecognitionJson.resource("response-schema.json")), "maxOutputTokens", 4096)));
        } catch (IOException e) { throw new IllegalStateException("Invalid bundled recognition configuration", e); }
        var request = HttpRequest.newBuilder(endpoint).timeout(timeout).header("Content-Type", "application/json")
                .header("x-goog-api-key", apiKey).POST(HttpRequest.BodyPublishers.ofByteArray(body)).build();
        CompletableFuture<HttpResponse<byte[]>> pending = client.sendAsync(request, info -> new LimitedBody());
        try {
            var response = pending.get(timeout.toMillis(), TimeUnit.MILLISECONDS);
            if (response.statusCode() != 200) throw new RecognitionException(HTTP_ERROR);
            return parseEnvelope(response.body());
        } catch (TimeoutException e) {
            pending.cancel(true); throw new RecognitionException(TIMEOUT);
        } catch (InterruptedException e) {
            pending.cancel(true); Thread.currentThread().interrupt(); throw new RecognitionException(NETWORK);
        } catch (ExecutionException e) {
            if (e.getCause() instanceof RecognitionException known) throw known;
            if (e.getCause() instanceof java.net.http.HttpTimeoutException) throw new RecognitionException(TIMEOUT);
            throw new RecognitionException(NETWORK);
        }
    }

    private static Response parseEnvelope(byte[] body) throws RecognitionException {
        if (body.length == 0) throw new RecognitionException(EMPTY_RESPONSE);
        try {
            JsonNode root = RecognitionJson.MAPPER.readTree(body);
            if (!root.isObject()) throw new RecognitionException(INVALID_RESPONSE);
            if (root.path("promptFeedback").hasNonNull("blockReason")) throw new RecognitionException(REFUSED);
            JsonNode candidates = root.path("candidates");
            if (!candidates.isArray() || candidates.isEmpty()) throw new RecognitionException(EMPTY_RESPONSE);
            JsonNode candidate = candidates.get(0);
            String finish = candidate.path("finishReason").asText();
            if (List.of("SAFETY", "RECITATION", "BLOCKLIST", "PROHIBITED_CONTENT", "SPII", "IMAGE_SAFETY").contains(finish))
                throw new RecognitionException(REFUSED);
            if (!finish.equals("STOP")) throw new RecognitionException(INVALID_RESPONSE);
            StringBuilder text = new StringBuilder();
            for (var part : candidate.path("content").path("parts")) {
                if (!part.path("thought").asBoolean(false) && part.path("text").isTextual()) text.append(part.get("text").textValue());
            }
            if (text.isEmpty() || text.toString().isBlank()) throw new RecognitionException(EMPTY_RESPONSE);
            String id = root.path("responseId").isTextual() ? root.get("responseId").textValue() : null;
            if (id != null && !id.matches("[a-zA-Z0-9_.:/-]{1,128}")) id = null;
            JsonNode usage = root.path("usageMetadata");
            Usage tokens = usage.isObject() ? new Usage(token(usage,"promptTokenCount"),token(usage,"candidatesTokenCount"),token(usage,"totalTokenCount")) : null;
            return new Response(text.toString(), id, tokens);
        } catch (IOException e) { throw new RecognitionException(INVALID_RESPONSE); }
    }
    private static Long token(JsonNode usage, String name) {
        var value = usage.path(name);
        return value.isIntegralNumber() && value.canConvertToLong() && value.longValue() >= 0 ? value.longValue() : null;
    }

    private static final class LimitedBody implements HttpResponse.BodySubscriber<byte[]> {
        private final CompletableFuture<byte[]> body = new CompletableFuture<>();
        private final ByteArrayOutputStream output = new ByteArrayOutputStream();
        private Flow.Subscription subscription;
        public CompletionStage<byte[]> getBody() { return body; }
        public void onSubscribe(Flow.Subscription value) { subscription = value; value.request(1); }
        public void onNext(List<ByteBuffer> buffers) {
            for (var buffer : buffers) {
                if ((long) output.size() + buffer.remaining() > RecognitionJson.MAX_RESPONSE_BYTES) {
                    subscription.cancel(); body.completeExceptionally(new RecognitionException(RESPONSE_TOO_LARGE)); return;
                }
                byte[] bytes = new byte[buffer.remaining()]; buffer.get(bytes); output.writeBytes(bytes);
            }
            subscription.request(1);
        }
        public void onError(Throwable error) { body.completeExceptionally(error); }
        public void onComplete() { body.complete(output.toByteArray()); }
    }
}
