package org.healthtg.core.dialog;

public final class DialogStateConflictException extends IllegalStateException {
    public DialogStateConflictException(String message, Throwable cause) {
        super(message, cause);
    }
}
