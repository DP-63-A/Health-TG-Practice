package org.healthtg.core.file;

import org.healthtg.core.entry.EntryCoreService;
import org.healthtg.core.entry.OwnerContext;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Service;

import javax.imageio.ImageIO;
import javax.imageio.ImageReader;
import javax.imageio.stream.ImageInputStream;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Clock;
import java.util.HexFormat;
import java.util.Iterator;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;

@Service
@ConditionalOnProperty(name = "health-tg.core.storage.enabled", matchIfMissing = true)
public class DefaultFileStorageService implements FileStorageService {
    public static final long MAX_BYTES = 5L * 1024 * 1024;
    public static final long MAX_PIXELS = 12_000_000L;

    private final MongoStoredFileRepository files;
    private final EntryCoreService entries;
    private final Clock clock;
    private final Path root;

    public DefaultFileStorageService(MongoStoredFileRepository files, EntryCoreService entries, Clock clock,
                                     @Value("${health-tg.files.root:${user.home}/.health-tg/files}") String root) {
        this.files = files;
        this.entries = entries;
        this.clock = clock;
        this.root = Path.of(root).toAbsolutePath().normalize();
    }

    @Override
    public StoredFile store(OwnerContext owner, InputStream content, long contentLength) {
        if (owner == null || content == null) throw new IllegalArgumentException("owner and content are required");
        if (contentLength > MAX_BYTES) throw new FileValidationException("Image exceeds 5 MiB");
        byte[] bytes = readBounded(content);
        ImageMetadata image = decode(bytes);
        UUID id = UUID.randomUUID();
        String relativePath = id.toString().substring(0, 2) + "/" + id + ".bin";
        Path target = resolve(relativePath);
        writeAtomically(target, bytes);
        var document = new MongoStoredFileDocument(id.toString(), owner.userId().toString(), null,
                relativePath, image.mediaType(), image.extension(), bytes.length, image.width(), image.height(),
                sha256(bytes), clock.instant(), null);
        try {
            return domain(files.save(document));
        } catch (RuntimeException failure) {
            try { Files.deleteIfExists(target); } catch (IOException ignored) { }
            throw failure;
        }
    }

    @Override
    public StoredFile bindToEntry(OwnerContext owner, UUID fileId, UUID entryId) {
        MongoStoredFileDocument file = requireOwned(owner, fileId);
        var entry = entries.requireEntry(owner, entryId);
        Object referencedId = entry.sourceRef().get("file_id");
        if (!fileId.toString().equals(referencedId)) {
            throw new FileValidationException("Entry source_ref does not reference this file");
        }
        if (file.entryId() != null && !file.entryId().equals(entryId.toString())) {
            throw new FileValidationException("File is already bound to another entry");
        }
        if (entryId.toString().equals(file.entryId())) return domain(file);
        return domain(files.save(new MongoStoredFileDocument(file.id(), file.ownerId(), entryId.toString(),
                file.relativePath(), file.mediaType(), file.extension(), file.size(), file.width(), file.height(),
                file.sha256(), file.createdAt(), file.version())));
    }

    @Override
    public StoredFileContent open(OwnerContext owner, UUID fileId) {
        MongoStoredFileDocument file = requireOwned(owner, fileId);
        if (file.entryId() == null) throw new StoredFileNotFoundException();
        Path path = resolve(file.relativePath());
        try {
            byte[] bytes = Files.readAllBytes(path);
            if (bytes.length != file.size() || !sha256(bytes).equals(file.sha256())) {
                throw new StoredFileUnavailableException("Stored file is corrupted", null);
            }
            return new StoredFileContent(domain(file), new ByteArrayInputStream(bytes));
        } catch (StoredFileUnavailableException failure) {
            throw failure;
        } catch (IOException failure) {
            throw new StoredFileUnavailableException("Stored file is unavailable", failure);
        }
    }

    private MongoStoredFileDocument requireOwned(OwnerContext owner, UUID fileId) {
        if (owner == null || fileId == null) throw new IllegalArgumentException("owner and fileId are required");
        return files.findByIdAndOwnerId(fileId.toString(), owner.userId().toString())
                .orElseThrow(StoredFileNotFoundException::new);
    }

    private byte[] readBounded(InputStream content) {
        try {
            ByteArrayOutputStream output = new ByteArrayOutputStream();
            content.transferTo(new LimitedOutputStream(output, MAX_BYTES + 1));
            byte[] bytes = output.toByteArray();
            if (bytes.length == 0) throw new FileValidationException("Image is empty");
            if (bytes.length > MAX_BYTES) throw new FileValidationException("Image exceeds 5 MiB");
            return bytes;
        } catch (SizeLimitExceededException failure) {
            throw new FileValidationException("Image exceeds 5 MiB");
        } catch (IOException failure) {
            throw new FileValidationException("Image could not be read");
        }
    }

    private static ImageMetadata decode(byte[] bytes) {
        try (ImageInputStream input = ImageIO.createImageInputStream(new ByteArrayInputStream(bytes))) {
            if (input == null) throw new FileValidationException("Image is invalid");
            Iterator<ImageReader> readers = ImageIO.getImageReaders(input);
            if (!readers.hasNext()) throw new FileValidationException("Only JPEG and PNG images are supported");
            ImageReader reader = readers.next();
            try {
                String format = reader.getFormatName().toLowerCase(Locale.ROOT);
                String mediaType;
                String extension;
                if (format.equals("jpeg") || format.equals("jpg")) {
                    mediaType = "image/jpeg";
                    extension = "jpg";
                } else if (format.equals("png")) {
                    mediaType = "image/png";
                    extension = "png";
                } else {
                    throw new FileValidationException("Only JPEG and PNG images are supported");
                }
                reader.setInput(input, true, true);
                checkPixels(reader.getWidth(0), reader.getHeight(0));
                var decoded = reader.read(0);
                if (decoded == null) throw new FileValidationException("Image is invalid");
                checkPixels(decoded.getWidth(), decoded.getHeight());
                return new ImageMetadata(mediaType, extension, decoded.getWidth(), decoded.getHeight());
            } finally {
                reader.dispose();
            }
        } catch (FileValidationException failure) {
            throw failure;
        } catch (IOException | RuntimeException failure) {
            throw new FileValidationException("Image is corrupted or unsupported");
        }
    }

    private static void checkPixels(int width, int height) {
        if (width < 1 || height < 1 || (long) width * height > MAX_PIXELS) {
            throw new FileValidationException("Image exceeds 12 megapixels");
        }
    }

    private void writeAtomically(Path target, byte[] bytes) {
        try {
            Files.createDirectories(target.getParent());
            Path temporary = Files.createTempFile(target.getParent(), ".upload-", ".tmp");
            try {
                Files.write(temporary, bytes);
                try {
                    Files.move(temporary, target, StandardCopyOption.ATOMIC_MOVE);
                } catch (AtomicMoveNotSupportedException ignored) {
                    Files.move(temporary, target);
                }
            } finally {
                Files.deleteIfExists(temporary);
            }
        } catch (IOException failure) {
            throw new StoredFileUnavailableException("File storage is unavailable", failure);
        }
    }

    private Path resolve(String relativePath) {
        Path resolved = root.resolve(relativePath).normalize();
        if (!resolved.startsWith(root)) throw new StoredFileUnavailableException("Invalid storage path", null);
        return resolved;
    }

    private static String sha256(byte[] bytes) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes));
        } catch (NoSuchAlgorithmException impossible) {
            throw new IllegalStateException(impossible);
        }
    }

    private static StoredFile domain(MongoStoredFileDocument file) {
        return new StoredFile(UUID.fromString(file.id()), UUID.fromString(file.ownerId()),
                file.entryId() == null ? null : UUID.fromString(file.entryId()), file.mediaType(), file.extension(),
                file.size(), file.width(), file.height(), file.sha256(), file.createdAt());
    }

    private record ImageMetadata(String mediaType, String extension, int width, int height) { }

    private static final class SizeLimitExceededException extends IOException { }

    private static final class LimitedOutputStream extends java.io.OutputStream {
        private final ByteArrayOutputStream delegate;
        private final long limit;
        private long written;

        private LimitedOutputStream(ByteArrayOutputStream delegate, long limit) {
            this.delegate = delegate;
            this.limit = limit;
        }

        @Override public void write(int value) throws IOException {
            if (++written > limit) throw new SizeLimitExceededException();
            delegate.write(value);
        }

        @Override public void write(byte[] bytes, int offset, int length) throws IOException {
            if (written + length > limit) throw new SizeLimitExceededException();
            written += length;
            delegate.write(bytes, offset, length);
        }
    }
}
