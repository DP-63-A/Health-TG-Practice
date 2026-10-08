package org.healthtg.bot.recognition;

/** Safe error categories; provider response bodies and secrets never become messages. */
public final class RecognitionException extends Exception {
    public enum Code { INVALID_IMAGE, IMAGE_TOO_LARGE, TOO_MANY_PIXELS, CONFIGURATION,
        TIMEOUT, NETWORK, HTTP_ERROR, REFUSED, EMPTY_RESPONSE, INVALID_RESPONSE, RESPONSE_TOO_LARGE }
    private final Code code;
    private final String requestId;
    private final RecognitionProvider.Usage usage;
    public RecognitionException(Code code) {
        this(code, null, null);
    }
    public RecognitionException(Code code, String requestId, RecognitionProvider.Usage usage) {
        super(code.name());
        this.code = code;
        this.requestId = safeRequestId(requestId);
        this.usage = usage;
    }
    public Code code() { return code; }
    public String requestId() { return requestId; }
    public RecognitionProvider.Usage usage() { return usage; }
    static String safeRequestId(String id) {
        return id != null && id.matches("[A-Za-z0-9_.:/-]{1,128}") ? id : null;
    }
}
