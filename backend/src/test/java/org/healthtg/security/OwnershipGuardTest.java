package org.healthtg.security;

import org.junit.jupiter.api.Test;

import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertThrows;

class OwnershipGuardTest {
    private final OwnershipGuard guard = new OwnershipGuard();

    @Test
    void acceptsOwnerAndHidesForeignResource() {
        UUID ownerId = UUID.randomUUID();
        CurrentUser currentUser = new CurrentUser(ownerId);

        assertDoesNotThrow(() -> guard.requireOwner(currentUser, ownerId));
        assertThrows(ResourceNotFoundException.class,
                () -> guard.requireOwner(currentUser, UUID.randomUUID()));
    }
}
