package org.healthtg.session;

import java.util.Optional;

public interface SessionStore {
    SessionRecord save(SessionRecord session);

    Optional<SessionRecord> findByTokenHash(String tokenHash);
}
