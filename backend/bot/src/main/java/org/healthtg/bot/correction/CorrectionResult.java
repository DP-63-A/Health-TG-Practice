package org.healthtg.bot.correction;

import java.util.Objects;

/** An entire corrected value, or a user-facing error; never a partial extraction. */
public sealed interface CorrectionResult<T> {
    record Success<T>(T value) implements CorrectionResult<T> {
        public Success {
            Objects.requireNonNull(value, "value");
        }
    }

    record Failure<T>(ErrorCode code, String message) implements CorrectionResult<T> {
        public Failure {
            Objects.requireNonNull(code, "code");
            Objects.requireNonNull(message, "message");
        }
    }

    enum ErrorCode {
        EMPTY_INPUT,
        INVALID_NUMBER_FORMAT,
        INVALID_DATE_FORMAT,
        INVALID_DATE
    }
}
