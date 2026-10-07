package org.healthtg.web;

import org.healthtg.core.entry.OwnerContext;
import org.healthtg.core.file.FileStorageService;
import org.healthtg.security.CurrentUser;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.core.io.InputStreamResource;
import org.springframework.http.CacheControl;
import org.springframework.http.ContentDisposition;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.UUID;

@RestController
@RequestMapping("/api/v1/files")
@ConditionalOnProperty(name = "health-tg.core.storage.enabled", matchIfMissing = true)
public class FilesController {
    private final FileStorageService files;

    public FilesController(FileStorageService files) {
        this.files = files;
    }

    @GetMapping("/{id}")
    public ResponseEntity<InputStreamResource> get(@AuthenticationPrincipal CurrentUser user,
                                                   @PathVariable UUID id) {
        var stored = files.open(new OwnerContext(user.id()), id);
        var metadata = stored.metadata();
        return ResponseEntity.ok()
                .cacheControl(CacheControl.noStore())
                .header(HttpHeaders.CONTENT_DISPOSITION, ContentDisposition.inline()
                        .filename(metadata.id() + "." + metadata.extension()).build().toString())
                .contentType(MediaType.parseMediaType(metadata.mediaType()))
                .contentLength(metadata.size())
                .body(new InputStreamResource(stored.content()));
    }
}
