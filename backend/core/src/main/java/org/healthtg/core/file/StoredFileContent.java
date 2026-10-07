package org.healthtg.core.file;

import java.io.InputStream;
import java.util.Objects;

public record StoredFileContent(StoredFile metadata, InputStream content) implements AutoCloseable {
    public StoredFileContent {
        Objects.requireNonNull(metadata);
        Objects.requireNonNull(content);
    }

    @Override
    public void close() throws Exception {
        content.close();
    }
}
