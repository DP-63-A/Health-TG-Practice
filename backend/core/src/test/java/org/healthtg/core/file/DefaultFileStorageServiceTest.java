package org.healthtg.core.file;

import org.healthtg.core.entry.Entry;
import org.healthtg.core.entry.EntryCoreService;
import org.healthtg.core.entry.EntryStatus;
import org.healthtg.core.entry.EntryType;
import org.healthtg.core.entry.OwnerContext;
import org.healthtg.core.entry.SourceKind;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import javax.imageio.ImageIO;
import java.awt.image.BufferedImage;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.HashMap;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class DefaultFileStorageServiceTest {
    private static final Instant NOW = Instant.parse("2026-10-05T10:00:00Z");
    @TempDir Path root;

    @Test
    void storesDetectedImageBindsEntryAndReturnsSameOriginalOnlyToOwner() throws Exception {
        UUID ownerId = UUID.randomUUID();
        OwnerContext owner = new OwnerContext(ownerId);
        EntryCoreService entries = mock(EntryCoreService.class);
        Map<String, MongoStoredFileDocument> database = new HashMap<>();
        var service = service(entries, database);
        byte[] original = image("png", 3, 2);

        StoredFile stored = service.store(owner, new ByteArrayInputStream(original), original.length);
        assertEquals("image/png", stored.mediaType());
        assertEquals(3, stored.width());
        assertEquals(2, stored.height());
        assertEquals(1, Files.walk(root).filter(Files::isRegularFile).filter(p -> p.toString().endsWith(".bin")).count());
        assertThrows(StoredFileNotFoundException.class, () -> service.open(owner, stored.id()));

        UUID entryId = UUID.randomUUID();
        when(entries.requireEntry(owner, entryId)).thenReturn(entry(entryId, ownerId, stored.id()));
        StoredFile bound = service.bindToEntry(owner, stored.id(), entryId);
        assertEquals(entryId, bound.entryId());
        var restarted = service(entries, database);
        try (var opened = restarted.open(owner, stored.id())) {
            assertArrayEquals(original, opened.content().readAllBytes());
        }
        assertThrows(StoredFileNotFoundException.class,
                () -> service.open(new OwnerContext(UUID.randomUUID()), stored.id()));
    }

    @Test
    void rejectsOversizeCorruptUnsupportedAndOverTwelveMegapixelImages() throws Exception {
        var service = service(mock(EntryCoreService.class), new HashMap<>());
        OwnerContext owner = new OwnerContext(UUID.randomUUID());

        assertThrows(FileValidationException.class, () -> service.store(owner,
                new ByteArrayInputStream(new byte[(int) DefaultFileStorageService.MAX_BYTES + 1]),
                DefaultFileStorageService.MAX_BYTES + 1));
        assertThrows(FileValidationException.class, () -> service.store(owner,
                new ByteArrayInputStream(new byte[]{1, 2, 3, 4}), 4));
        byte[] gif = image("gif", 2, 2);
        assertThrows(FileValidationException.class,
                () -> service.store(owner, new ByteArrayInputStream(gif), gif.length));
        byte[] tooManyPixels = image("png", 4001, 3000);
        assertThrows(FileValidationException.class, () -> service.store(owner,
                new ByteArrayInputStream(tooManyPixels), tooManyPixels.length));
        assertFalse(Files.exists(root.resolve("outside")));
    }

    @Test
    void detectsContentInsteadOfTrustingAFileNameAndReportsMissingOrCorruptedStorage() throws Exception {
        UUID ownerId = UUID.randomUUID();
        OwnerContext owner = new OwnerContext(ownerId);
        EntryCoreService entries = mock(EntryCoreService.class);
        Map<String, MongoStoredFileDocument> database = new HashMap<>();
        var service = service(entries, database);
        byte[] jpeg = image("jpeg", 2, 2);
        StoredFile stored = service.store(owner, new ByteArrayInputStream(jpeg), -1);
        assertEquals("image/jpeg", stored.mediaType());
        UUID entryId = UUID.randomUUID();
        when(entries.requireEntry(owner, entryId)).thenReturn(entry(entryId, ownerId, stored.id()));
        service.bindToEntry(owner, stored.id(), entryId);

        Path physical = Files.walk(root).filter(Files::isRegularFile).filter(p -> p.toString().endsWith(".bin")).findFirst().orElseThrow();
        assertTrue(physical.getFileName().toString().endsWith(".bin"));
        Files.write(physical, new byte[]{9, 8, 7});
        assertThrows(StoredFileUnavailableException.class, () -> service.open(owner, stored.id()));
        assertThrows(StoredFileNotFoundException.class, () -> service.open(owner, UUID.randomUUID()));
    }

    @Test
    void bindingRequiresMatchingOwnerEntryAndSourceReference() throws Exception {
        UUID ownerId = UUID.randomUUID();
        OwnerContext owner = new OwnerContext(ownerId);
        EntryCoreService entries = mock(EntryCoreService.class);
        Map<String, MongoStoredFileDocument> database = new HashMap<>();
        var service = service(entries, database);
        byte[] image = image("png", 1, 1);
        StoredFile stored = service.store(owner, new ByteArrayInputStream(image), image.length);
        UUID entryId = UUID.randomUUID();
        when(entries.requireEntry(owner, entryId)).thenReturn(entry(entryId, ownerId, UUID.randomUUID()));

        assertThrows(FileValidationException.class, () -> service.bindToEntry(owner, stored.id(), entryId));
    }

    private DefaultFileStorageService service(EntryCoreService entries,
                                              Map<String, MongoStoredFileDocument> database) {
        MongoStoredFileRepository repository = mock(MongoStoredFileRepository.class);
        when(repository.insert(any(MongoStoredFileDocument.class))).thenAnswer(call -> {
            MongoStoredFileDocument value = call.getArgument(0);
            database.put(value.id(), value);
            return value;
        });
        when(repository.findByIdAndOwnerId(any(), any())).thenAnswer(call -> Optional.ofNullable(database.get(call.getArgument(0)))
                .filter(file -> file.ownerId().equals(call.getArgument(1))));
        return new DefaultFileStorageService(repository, entries, Clock.fixed(NOW, ZoneOffset.UTC), root.toString(), new FileOperationGuard(root.toString()), FileLifecycleUnitSupport.lifecycle(database), mock(org.healthtg.core.entry.EntryStore.class));
    }

    private static Entry entry(UUID id, UUID ownerId, UUID fileId) {
        return new Entry(id, ownerId, EntryType.MEAL, EntryStatus.DRAFT, SourceKind.FOOD_PHOTO,
                Map.of("file_id", fileId.toString()), NOW, NOW, NOW, 1,
                Map.of("description", "meal"), Map.of("description", "reported"), null, "test:" + id);
    }

    private static byte[] image(String format, int width, int height) throws Exception {
        int type = format.equals("jpeg") ? BufferedImage.TYPE_INT_RGB : BufferedImage.TYPE_BYTE_BINARY;
        var image = new BufferedImage(width, height, type);
        var output = new ByteArrayOutputStream();
        assertTrue(ImageIO.write(image, format, output));
        return output.toByteArray();
    }
}
