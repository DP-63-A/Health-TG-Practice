package org.healthtg.bot.recognition;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import java.nio.file.Path;
import java.util.UUID;

public final class FoodRecognitionService {
    private static final Logger LOG = LoggerFactory.getLogger(FoodRecognitionService.class);
    private final ImageValidator images;
    private final RecognitionProvider provider;
    private final RecognitionResponseParser parser;
    public record Outcome(String operationId, RecognitionProvider.Mode mode, RecognitionResult result,
                          String requestId, RecognitionProvider.Usage usage, String cost) { }
    public FoodRecognitionService(ImageValidator images, RecognitionProvider provider, RecognitionResponseParser parser) {
        this.images = images; this.provider = provider; this.parser = parser;
    }
    public Outcome recognize(Path path) throws RecognitionException {
        return recognize(images.validate(path));
    }
    public Outcome recognize(byte[] bytes) throws RecognitionException {
        return recognize(images.validate(bytes));
    }
    private Outcome recognize(ImageValidator.ValidatedImage image) throws RecognitionException {
        String id = UUID.randomUUID().toString();
        long start = System.nanoTime();
        String status = "ERROR";
        RecognitionProvider.Usage usage = null;
        String requestId = null;
        try {
            var response = provider.recognize(image);
            usage = response.usage();
            requestId = response.requestId();
            var result = parser.parse(response.json());
            status = result.needsClarification() ? "NEEDS_CLARIFICATION" : "RECOGNIZED";
            return new Outcome(id, provider.mode(), result, response.requestId(), usage, "unknown");
        } catch (RecognitionException e) { status = e.code().name(); throw e; }
        finally {
            LOG.info("recognition operation={} mode={} status={} duration_ms={} usage={} request_id={} cost=unknown",
                    id, provider.mode(), status, (System.nanoTime()-start)/1_000_000, usage, safeRequestId(requestId));
        }
    }
    private static String safeRequestId(String id) {
        return id != null && id.matches("[A-Za-z0-9_-]{1,128}") ? id : null;
    }
}
