package org.healthtg.bot.recognition;

public interface RecognitionProvider {
    enum Mode { FIXTURE, LIVE }
    record Usage(Long inputTokens, Long outputTokens, Long totalTokens) { }
    record Response(String json, String requestId, Usage usage) { }
    Mode mode();
    Response recognize(ImageValidator.ValidatedImage image) throws RecognitionException;
}
