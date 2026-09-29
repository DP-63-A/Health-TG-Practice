package org.healthtg.web;

import org.healthtg.core.entry.ConfirmEntryCommand;
import org.healthtg.core.entry.Entry;
import org.healthtg.core.entry.EntryCoreService;
import org.healthtg.core.entry.EntryStatus;
import org.healthtg.core.entry.EntryType;
import org.healthtg.core.entry.OwnerContext;
import org.healthtg.core.entry.SourceKind;
import org.healthtg.security.CurrentUser;
import org.healthtg.user.UserAccount;
import org.healthtg.user.UserService;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.time.Instant;
import java.time.ZoneId;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class EntriesControllerTest {
    private final EntryCoreService entries = mock(EntryCoreService.class);
    private final UserService users = mock(UserService.class);
    private final EntriesController controller = new EntriesController(entries, users);
    private final UUID ownerId = UUID.randomUUID();
    private final CurrentUser user = new CurrentUser(ownerId);

    @Test
    void returnsRevisionEtagAndPassesIdempotencyDataToCore() {
        Entry entry = entry(UUID.randomUUID(), 3);
        when(entries.confirm(any())).thenReturn(entry);

        var response = controller.confirm(user, entry.id(), new EntriesController.ConfirmRequest("submission-1", 2L));

        assertEquals("\"3\"", response.getHeaders().getETag());
        assertEquals(entry.id(), response.getBody().id());
        ArgumentCaptor<ConfirmEntryCommand> command = ArgumentCaptor.forClass(ConfirmEntryCommand.class);
        verify(entries).confirm(command.capture());
        assertEquals(new OwnerContext(ownerId), command.getValue().owner());
        assertEquals("submission-1", command.getValue().submissionId());
        assertEquals(2, command.getValue().expectedRevision());
    }

    @Test
    void paginatesWithOpaqueCursorAndRejectsMalformedIfMatch() {
        when(users.requireById(ownerId)).thenReturn(new UserAccount(ownerId, 1001, ZoneId.of("Europe/Warsaw"), true));
        List<Entry> found = List.of(entry(UUID.randomUUID(), 1), entry(UUID.randomUUID(), 1),
                entry(UUID.randomUUID(), 1));
        when(entries.listEntries(any())).thenReturn(found);

        var first = controller.list(user, null, null, null, "confirmed", 2, null);
        assertEquals(2, first.items().size());
        assertNotNull(first.nextCursor());
        var second = controller.list(user, null, null, null, "confirmed", 2, first.nextCursor());
        assertEquals(1, second.items().size());
        assertEquals(found.get(2).id(), second.items().getFirst().id());

        assertThrows(IllegalArgumentException.class, () -> controller.delete(user, found.getFirst().id(), "1"));
        assertThrows(IllegalArgumentException.class, () -> controller.delete(user, found.getFirst().id(), "W/\"1\""));
    }

    private Entry entry(UUID id, long revision) {
        Instant instant = Instant.parse("2026-09-23T08:00:00Z");
        return new Entry(id, ownerId, EntryType.NOTE, EntryStatus.CONFIRMED, SourceKind.TEXT, Map.of(),
                instant, instant, instant, revision, Map.of("text", "note"), Map.of("text", "reported"),
                null, "test:" + id);
    }
}
