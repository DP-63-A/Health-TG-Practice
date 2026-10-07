package org.healthtg.bot.recognition;

/** Safe error categories; provider response bodies and secrets never become messages. */
public final class RecognitionException extends Exception {
    public enum Code { INVALID_IMAGE, IMAGE_TOO_LARGE, TOO_MANY_PIXELS, CONFIGURATION,
        TIMEOUT, NETWORK, HTTP_ERROR, REFUSED, EMPTY_RESPONSE, INVALID_RESPONSE, RESPONSE_TOO_LARGE }
    private final Code code;
    public RecognitionException(Code code) {
        super(code.name());
        this.code = code;
    }
    public Code code() { return code; }
}
