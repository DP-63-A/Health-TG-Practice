package org.healthtg.web;

import org.healthtg.core.entry.OwnerContext;
import org.healthtg.core.file.FileStorageService;
import org.healthtg.core.file.StoredFile;
import org.healthtg.core.file.StoredFileContent;
import org.healthtg.security.CurrentUser;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.io.ByteArrayInputStream;
import java.time.Instant;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class FilesControllerTest {
    @Test
    void streamsOwnedOriginalWithSafeHeadersAndPassesOwnerToCore() throws Exception {
        FileStorageService files = mock(FileStorageService.class);
        UUID ownerId = UUID.randomUUID();
        UUID fileId = UUID.randomUUID();
        byte[] bytes = {1, 2, 3};
        StoredFile metadata = new StoredFile(fileId, ownerId, UUID.randomUUID(), "image/png", "png",
                bytes.length, 1, 1, "hash", Instant.parse("2026-10-05T10:00:00Z"));
        when(files.open(new OwnerContext(ownerId), fileId)).thenReturn(
                new StoredFileContent(metadata, new ByteArrayInputStream(bytes)));

        var response = new FilesController(files).get(new CurrentUser(ownerId), fileId);

        assertEquals("image/png", response.getHeaders().getContentType().toString());
        assertEquals(bytes.length, response.getHeaders().getContentLength());
        assertEquals("no-store", response.getHeaders().getCacheControl());
        String disposition = response.getHeaders().getFirst("Content-Disposition");
        assertEquals("inline; filename=\"" + fileId + ".png\"", disposition);
        assertFalse(disposition.contains("\\"));
        assertEquals(1, response.getBody().getInputStream().read());
        ArgumentCaptor<OwnerContext> owner = ArgumentCaptor.forClass(OwnerContext.class);
        verify(files).open(owner.capture(), org.mockito.ArgumentMatchers.eq(fileId));
        assertEquals(ownerId, owner.getValue().userId());
    }
}
