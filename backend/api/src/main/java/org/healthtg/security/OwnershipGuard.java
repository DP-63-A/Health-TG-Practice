package org.healthtg.security;

import org.springframework.stereotype.Component;

import java.util.UUID;

@Component
public class OwnershipGuard {
    public void requireOwner(CurrentUser currentUser, UUID resourceOwnerId) {
        if (!currentUser.id().equals(resourceOwnerId)) {
            throw new ResourceNotFoundException();
        }
    }
}
