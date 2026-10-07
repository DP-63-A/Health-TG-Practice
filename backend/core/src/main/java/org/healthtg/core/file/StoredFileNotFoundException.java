package org.healthtg.core.file;

public class StoredFileNotFoundException extends RuntimeException {
    public StoredFileNotFoundException() { super("Stored file not found"); }
}
