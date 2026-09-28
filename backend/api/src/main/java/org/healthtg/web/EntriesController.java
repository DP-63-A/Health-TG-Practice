package org.healthtg.web;

import jakarta.validation.Valid;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;
import jakarta.validation.constraints.Size;
import org.healthtg.core.entry.ConfirmEntryCommand;
import org.healthtg.core.entry.Entry;
import org.healthtg.core.entry.EntryCoreService;
import org.healthtg.core.entry.EntryStatus;
import org.healthtg.core.entry.EntryType;
import org.healthtg.core.entry.ListEntriesQuery;
import org.healthtg.core.entry.OwnerContext;
import org.healthtg.core.entry.PatchEntryCommand;
import org.healthtg.security.CurrentUser;
import org.healthtg.user.UserService;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.validation.annotation.Validated;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;

import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.time.LocalDate;
import java.util.Base64;
import java.util.List;
import java.util.Map;
import java.util.UUID;

@RestController
@RequestMapping("/api/v1/entries")
@Validated
@ConditionalOnProperty(name = "health-tg.core.storage.enabled", matchIfMissing = true)
public class EntriesController {
    private final EntryCoreService entries;
    private final UserService users;

    public EntriesController(EntryCoreService entries, UserService users) {
        this.entries = entries;
        this.users = users;
    }

    @GetMapping
    public EntryListResponse list(@AuthenticationPrincipal CurrentUser user,
                                  @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate from,
                                  @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate to,
                                  @RequestParam(required = false) String type,
                                  @RequestParam(defaultValue = "confirmed") String status,
                                  @RequestParam(defaultValue = "20") @Min(1) @Max(100) int limit,
                                  @RequestParam(required = false) String cursor) {
        var account = users.requireById(user.id());
        List<Entry> found = entries.listEntries(new ListEntriesQuery(owner(user), EntryStatus.fromCode(status),
                type == null ? null : EntryType.fromCode(type), from, to, account.timezone()));
        int start = cursor == null ? 0 : cursorIndex(found, cursor);
        int end = Math.min(start + limit, found.size());
        List<EntryResponse> items = found.subList(start, end).stream().map(EntryResponse::from).toList();
        String next = end < found.size() ? cursor(found.get(end - 1).id()) : null;
        return new EntryListResponse(items, next);
    }

    @GetMapping("/{id}")
    public ResponseEntity<EntryResponse> get(@AuthenticationPrincipal CurrentUser user, @PathVariable UUID id) {
        return response(entries.requireEntry(owner(user), id));
    }

    @PatchMapping("/{id}")
    public ResponseEntity<EntryResponse> patch(@AuthenticationPrincipal CurrentUser user, @PathVariable UUID id,
                                               @Valid @RequestBody EntryPatchRequest request) {
        return response(entries.patch(new PatchEntryCommand(owner(user), id, request.expectedRevision(),
                request.occurredAt(), request.payload(), request.fieldOrigins())));
    }

    @PostMapping("/{id}/confirm")
    public ResponseEntity<EntryResponse> confirm(@AuthenticationPrincipal CurrentUser user, @PathVariable UUID id,
                                                 @Valid @RequestBody ConfirmRequest request) {
        return response(entries.confirm(new ConfirmEntryCommand(owner(user), id, request.submissionId(),
                request.expectedRevision())));
    }

    @PostMapping("/{id}/cancel")
    public ResponseEntity<EntryResponse> cancel(@AuthenticationPrincipal CurrentUser user, @PathVariable UUID id,
                                                @RequestHeader("If-Match") String ifMatch) {
        return response(entries.cancel(owner(user), id, parseEtag(ifMatch)));
    }

    @DeleteMapping("/{id}")
    public ResponseEntity<EntryResponse> delete(@AuthenticationPrincipal CurrentUser user, @PathVariable UUID id,
                                                @RequestHeader("If-Match") String ifMatch) {
        return response(entries.delete(owner(user), id, parseEtag(ifMatch)));
    }

    private static ResponseEntity<EntryResponse> response(Entry entry) {
        return ResponseEntity.ok().eTag(Long.toString(entry.revision())).body(EntryResponse.from(entry));
    }

    private static OwnerContext owner(CurrentUser user) { return new OwnerContext(user.id()); }

    private static long parseEtag(String value) {
        if (value == null || !value.matches("\"[1-9][0-9]*\"")) {
            throw new IllegalArgumentException("If-Match must be a strong revision ETag");
        }
        return Long.parseLong(value.substring(1, value.length() - 1));
    }

    private static String cursor(UUID id) {
        return Base64.getUrlEncoder().withoutPadding().encodeToString(id.toString().getBytes(StandardCharsets.US_ASCII));
    }

    private static int cursorIndex(List<Entry> entries, String encoded) {
        try {
            UUID id = UUID.fromString(new String(Base64.getUrlDecoder().decode(encoded), StandardCharsets.US_ASCII));
            for (int i = 0; i < entries.size(); i++) if (entries.get(i).id().equals(id)) return i + 1;
        } catch (IllegalArgumentException ignored) {
        }
        throw new IllegalArgumentException("Invalid cursor");
    }

    public record EntryPatchRequest(@NotNull @Positive Long expectedRevision, Instant occurredAt,
                                    Map<String, Object> payload, Map<String, String> fieldOrigins) { }

    public record ConfirmRequest(@NotBlank @Size(max = 128) String submissionId,
                                 @NotNull @Positive Long expectedRevision) { }

    public record EntryListResponse(List<EntryResponse> items, String nextCursor) { }

    public record EntryResponse(UUID id, UUID userId, String type, String status, String sourceKind,
                                Map<String, Object> sourceRef, Instant occurredAt, Instant createdAt,
                                Instant updatedAt, long revision, Map<String, Object> payload,
                                Map<String, String> fieldOrigins, String submissionId) {
        static EntryResponse from(Entry entry) {
            return new EntryResponse(entry.id(), entry.ownerId(), entry.type().code(), entry.status().code(),
                    entry.sourceKind().code(), entry.sourceRef(), entry.occurredAt(), entry.createdAt(),
                    entry.updatedAt(), entry.revision(), entry.payload(), entry.fieldOrigins(), entry.submissionId());
        }
    }
}
