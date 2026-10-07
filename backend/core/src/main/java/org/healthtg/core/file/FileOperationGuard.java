package org.healthtg.core.file;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.nio.channels.FileChannel;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.UUID;
import java.util.concurrent.locks.ReentrantLock;
import java.util.function.Supplier;

/** All processes must use the same physical file root; lock files must never be removed. */
@Component
@ConditionalOnProperty(name = "health-tg.core.storage.enabled", matchIfMissing = true)
public final class FileOperationGuard {
    private static final ReentrantLock[] STRIPES = new ReentrantLock[256];
    static { for (int i=0;i<STRIPES.length;i++) STRIPES[i]=new ReentrantLock(); }
    private final Path root;

    public FileOperationGuard(@Value("${health-tg.files.root:${user.home}/.health-tg/files}") String root) {
        this.root=Path.of(root).toAbsolutePath().normalize().resolve(".locks");
    }

    public <T> T withFile(UUID id, Supplier<T> operation) {
        var stripe=STRIPES[Math.floorMod(id.hashCode(),STRIPES.length)];
        if (stripe.isHeldByCurrentThread()) throw new IllegalStateException("Nested file operations are forbidden");
        stripe.lock();
        try {
            Files.createDirectories(root);
            try (var channel=FileChannel.open(root.resolve(id+".lock"),StandardOpenOption.CREATE,StandardOpenOption.WRITE);
                 var ignored=channel.lock()) {
                return operation.get();
            }
        } catch (IOException failure) {
            throw new StoredFileUnavailableException("File operation lock is unavailable",failure);
        } finally { stripe.unlock(); }
    }
}
