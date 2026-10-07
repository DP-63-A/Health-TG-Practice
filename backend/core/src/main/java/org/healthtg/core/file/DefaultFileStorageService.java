package org.healthtg.core.file;

import org.healthtg.core.entry.EntryCoreService;
import org.healthtg.core.entry.OwnerContext;
import org.healthtg.core.entry.EntryStore;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Service;
import org.springframework.dao.DuplicateKeyException;

import javax.imageio.ImageIO;
import javax.imageio.ImageReader;
import javax.imageio.stream.ImageInputStream;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Clock;
import java.util.HexFormat;
import java.util.Iterator;
import java.util.Locale;
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
    private final FileOperationGuard guard;
    private final StoredFileLifecycle lifecycle;
    private final EntryStore entryStore;

    public DefaultFileStorageService(MongoStoredFileRepository files, EntryCoreService entries, Clock clock,
                                     @Value("${health-tg.files.root:${user.home}/.health-tg/files}") String root,
                                     FileOperationGuard guard,StoredFileLifecycle lifecycle,EntryStore entryStore) {
        this.files = files;
        this.entries = entries;
        this.clock = clock;
        this.root = Path.of(root).toAbsolutePath().normalize();
        this.guard=guard;
        this.lifecycle=lifecycle;
        this.entryStore=entryStore;
    }

    @Override
    public StoredFile store(OwnerContext owner, InputStream content, long contentLength) {
        return store(owner,UUID.randomUUID(),content,contentLength);
    }

    @Override
    public StoredFile store(OwnerContext owner, UUID id, InputStream content, long contentLength) {
        if (owner == null || id == null || content == null)
            throw new IllegalArgumentException("owner, fileId and content are required");
        if (contentLength > MAX_BYTES) throw new FileValidationException("Image exceeds 5 MiB");
        byte[] bytes = readBounded(content);
        ImageMetadata image = decode(bytes);
        return guard.withFile(id,() -> storeReserved(owner,id,bytes,image));
    }

    private StoredFile storeReserved(OwnerContext owner,UUID id,byte[] bytes,ImageMetadata image) {
        String relativePath = id.toString().substring(0, 2) + "/" + id + ".bin";
        var proposed = new MongoStoredFileDocument(id.toString(), owner.userId().toString(), null,
                relativePath, image.mediaType(), image.extension(), bytes.length, image.width(), image.height(),
                sha256(bytes), clock.instant(), null);
        MongoStoredFileDocument reserved;
        try {
            // Insert, never upsert: another owner/content cannot be replaced under this ID.
            reserved = files.insert(proposed);
        } catch (DuplicateKeyException exists) {
            reserved = files.findById(id.toString()).orElseThrow(() ->
                    new StoredFileUnavailableException("File reservation is unavailable", null));
        }
        // A lost acknowledgement is allowed to propagate: no file is deleted. A later retry
        // finds the same reservation through duplicate-key handling and completes the write.
        if (!reserved.ownerId().equals(owner.userId().toString())) throw new StoredFileNotFoundException();
        StoredFileLifecycle.requireUsable(reserved);
        validateReservation(reserved, proposed);
        Path target = resolve(reserved.relativePath());
        writeReserved(target, bytes);
        return domain(reserved);
    }

    private static void validateReservation(MongoStoredFileDocument stored, MongoStoredFileDocument proposed) {
        if (!stored.ownerId().equals(proposed.ownerId())) throw new StoredFileNotFoundException();
        if (!stored.id().equals(proposed.id()) || !stored.sha256().equals(proposed.sha256())
                || !stored.size().equals(proposed.size()) || !stored.relativePath().equals(proposed.relativePath())
                || !stored.mediaType().equals(proposed.mediaType()) || !stored.extension().equals(proposed.extension())
                || !stored.width().equals(proposed.width()) || !stored.height().equals(proposed.height())) {
            throw new FileValidationException("File ID is reserved for different image contents");
        }
    }
    @Override
    public StoredFile bindToEntry(OwnerContext owner, UUID fileId, UUID entryId) {
        return guard.withFile(fileId,() -> bindOwned(owner,fileId,entryId));
    }
    private StoredFile bindOwned(OwnerContext owner,UUID fileId,UUID entryId) {
        MongoStoredFileDocument file = requireOwned(owner, fileId);
        StoredFileLifecycle.requireUsable(file);
        var entry = entries.requireEntry(owner, entryId);
        Object referencedId = entry.sourceRef().get("file_id");
        if (!fileId.toString().equals(referencedId)) {
            throw new FileValidationException("Entry source_ref does not reference this file");
        }
        if (file.entryId() != null && !file.entryId().equals(entryId.toString())) {
            throw new FileValidationException("File is already bound to another entry");
        }
        if (entryId.toString().equals(file.entryId())) return domain(file);
        lifecycle.pinForEntry(owner,fileId);
        return domain(lifecycle.bind(owner,fileId,entryId));
    }

    @Override
    public CleanupResult discardUnreferenced(OwnerContext owner,UUID fileId) {
        if(owner==null || fileId==null) throw new IllegalArgumentException("owner and fileId are required");
        return guard.withFile(fileId,() -> discardOwned(owner,fileId));
    }
    private CleanupResult discardOwned(OwnerContext owner,UUID fileId) {
        var existing=files.findById(fileId.toString());
        if(existing.isEmpty()) {
            if(entryStore.hasFileReference(fileId)) return CleanupResult.PROTECTED;
            try {
                files.insert(new MongoStoredFileDocument(fileId.toString(),owner.userId().toString(),null,
                        null,null,null,null,null,null,null,null,null,StoredFileLifecycle.DELETED));
                return CleanupResult.DELETED;
            } catch(DuplicateKeyException concurrent) {
                existing=files.findById(fileId.toString());
                if(existing.isEmpty()) throw new StoredFileUnavailableException("File cancellation is not acknowledged",concurrent);
            }
        }
        var file=existing.orElseThrow();
        if(!file.ownerId().equals(owner.userId().toString())) throw new StoredFileNotFoundException();
        if(file.entryId()!=null || entryStore.hasFileReference(fileId)
                || StoredFileLifecycle.PINNED.equals(StoredFileLifecycle.state(file))) return CleanupResult.PROTECTED;
        String state=StoredFileLifecycle.state(file);
        if(StoredFileLifecycle.DELETED.equals(state)) return CleanupResult.DELETED;
        if(!StoredFileLifecycle.DELETING.equals(state)
                && (!StoredFileLifecycle.ACTIVE.equals(state) || !lifecycle.beginDeletion(owner,fileId)))
            throw new StoredFileUnavailableException("File cancellation is not acknowledged",null);
        try { Files.deleteIfExists(resolve(file.relativePath())); }
        catch(IOException failure) { throw new StoredFileUnavailableException("File cleanup is incomplete",failure); }
        lifecycle.finishDeletion(owner,fileId);
        return CleanupResult.DELETED;
    }

    @Override
    public StoredFileContent open(OwnerContext owner, UUID fileId) {
        MongoStoredFileDocument file = requireOwned(owner, fileId);
        StoredFileLifecycle.requireUsable(file);
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

    private void writeReserved(Path target, byte[] bytes) {
        try {
            if (Files.exists(target)) {
                requireMatchingContents(target, bytes);
                return;
            }
            Files.createDirectories(target.getParent());
            Path temporary = Files.createTempFile(target.getParent(), ".upload-", ".tmp");
            try {
                Files.write(temporary, bytes);
                try {
                    // Do not replace an existing destination; concurrent identical retries converge.
                    Files.move(temporary, target);
                } catch (java.nio.file.FileAlreadyExistsException concurrentWrite) {
                    requireMatchingContents(target, bytes);
                }
            } finally {
                Files.deleteIfExists(temporary);
            }
        } catch (IOException failure) {
            throw new StoredFileUnavailableException("File storage is unavailable", failure);
        }
    }

    private static void requireMatchingContents(Path target, byte[] bytes) throws IOException {
        try (var existing = Files.newInputStream(target)) {
            byte[] saved = existing.readNBytes((int) MAX_BYTES + 1);
            if (saved.length != bytes.length || !sha256(saved).equals(sha256(bytes)))
                throw new StoredFileUnavailableException("Stored file conflicts with reservation", null);
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
