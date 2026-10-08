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
    @FunctionalInterface public interface ImageSource { byte[] load() throws RecognitionException; }
    @FunctionalInterface private interface ValidatedSource { ImageValidator.ValidatedImage load() throws RecognitionException; }
    public record Outcome(String operationId, RecognitionProvider.Mode mode, RecognitionResult result,
                          String requestId, RecognitionProvider.Usage usage, String cost) { }
    public FoodRecognitionService(ImageValidator images, RecognitionProvider provider, RecognitionResponseParser parser) {
        this.images = images; this.provider = provider; this.parser = parser;
    }
    public Outcome recognize(Path path) throws RecognitionException {
        return recognize(() -> images.validate(path), null);
    }
    public Outcome recognize(byte[] bytes) throws RecognitionException {
        return recognize(bytes, null);
    }
    public Outcome recognize(byte[] bytes,String imageClass) throws RecognitionException {
        return recognizeLoaded(() -> bytes, imageClass);
    }
    /** Loading and validation belong to the same diagnostic operation as the provider call. */
    public Outcome recognizeLoaded(ImageSource source, String imageClass) throws RecognitionException {
        return recognize(() -> images.validate(source.load()), imageClass);
    }
    /** Telegram metadata rejected before a dialog or provider call; no content is accepted here. */
    public void rejectInput(RecognitionException.Code code) {
        if (!java.util.Set.of(RecognitionException.Code.INVALID_IMAGE, RecognitionException.Code.IMAGE_TOO_LARGE,
                RecognitionException.Code.TOO_MANY_PIXELS).contains(code)) throw new IllegalArgumentException("Invalid input rejection");
        log(UUID.randomUUID().toString(), null, "input", code.name(), System.nanoTime(), null, null);
    }
    private Outcome recognize(ValidatedSource source,String imageClass) throws RecognitionException {
        if(imageClass!=null && !java.util.Set.of("food_photo","health_screenshot","watch_photo").contains(imageClass))
            throw new IllegalArgumentException("Invalid image class");
        String id = UUID.randomUUID().toString();
        long start = System.nanoTime();
        String status = "ERROR";
        String stage = "input";
        RecognitionProvider.Usage usage = null;
        String requestId = null;
        try {
            var image = source.load();
            stage = "provider";
            var response = imageClass==null ? provider.recognize(image) : provider.recognize(image,imageClass);
            usage = response.usage();
            requestId = response.requestId();
            stage = "response";
            var result = parser.parse(response.json());
            if(imageClass!=null && !imageClass.equals(result.imageClass()))
                throw new RecognitionException(RecognitionException.Code.INVALID_RESPONSE);
            status = result.needsClarification() ? "NEEDS_CLARIFICATION" : "RECOGNIZED";
            return new Outcome(id, provider.mode(), result, response.requestId(), usage, "unknown");
        } catch (RecognitionException e) {
            status = e.code().name();
            if (e.usage() != null) usage = e.usage();
            if (e.requestId() != null) requestId = e.requestId();
            throw e;
        }
        finally {
            log(id, imageClass, stage, status, start, usage, requestId);
        }
    }
    private void log(String id, String imageClass, String stage, String status, long start,
                     RecognitionProvider.Usage usage, String requestId) {
        LOG.info("recognition operation={} type=recognition image_class={} stage={} mode={} status={} duration_ms={} usage={} request_id={} cost=unknown",
                id, imageClass == null ? "unspecified" : imageClass, stage, provider.mode(), status,
                (System.nanoTime()-start)/1_000_000, usage, RecognitionException.safeRequestId(requestId));
    }
}
