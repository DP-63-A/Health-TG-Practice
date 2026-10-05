package org.healthtg.seed;

/** Raised before any data change when seed or reset is not allowed in the current environment. */
public class SeedRefusedException extends RuntimeException {
    public SeedRefusedException(String message) {
        super(message);
    }
}
