package org.healthtg.session;

import org.healthtg.auth.AuthFailureException;
import org.healthtg.auth.AuthProperties;
import org.junit.jupiter.api.Test;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.HashMap;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;

class SessionServiceTest {
    @Test
    void storesOnlyHashAndAcceptsSessionBeforeExpiry() {
        MutableClock clock = new MutableClock(Instant.parse("2026-09-22T12:00:00Z"));
        InMemorySessionStore store = new InMemorySessionStore();
        SessionService service = new SessionService(store, properties(), clock);
        UUID userId = UUID.randomUUID();

        SessionService.IssuedSession issued = service.issue(userId);
        assertFalse(store.values.containsKey(issued.token()));
        clock.instant = issued.expiresAt().minusNanos(1);
        assertEquals(userId, service.authenticate(issued.token()));
    }

    @Test
    void rejectsSessionAtExpiryAndChangedToken() {
        MutableClock clock = new MutableClock(Instant.parse("2026-09-22T12:00:00Z"));
        SessionService service = new SessionService(new InMemorySessionStore(), properties(), clock);
        SessionService.IssuedSession issued = service.issue(UUID.randomUUID());

        assertThrows(AuthFailureException.class, () -> service.authenticate(issued.token() + "x"));
        clock.instant = issued.expiresAt();
        assertThrows(AuthFailureException.class, () -> service.authenticate(issued.token()));
    }

    private static AuthProperties properties() {
        return new AuthProperties("token", "10001", Duration.ofMinutes(15), Duration.ofMinutes(60),
                ZoneId.of("Europe/Warsaw"));
    }

    private static final class InMemorySessionStore implements SessionStore {
        private final Map<String, SessionRecord> values = new HashMap<>();

        @Override
        public SessionRecord save(SessionRecord session) {
            values.put(session.tokenHash(), session);
            return session;
        }

        @Override
        public Optional<SessionRecord> findByTokenHash(String tokenHash) {
            return Optional.ofNullable(values.get(tokenHash));
        }
    }

    private static final class MutableClock extends Clock {
        private Instant instant;

        private MutableClock(Instant instant) {
            this.instant = instant;
        }

        @Override
        public ZoneId getZone() {
            return ZoneOffset.UTC;
        }

        @Override
        public Clock withZone(ZoneId zone) {
            return this;
        }

        @Override
        public Instant instant() {
            return instant;
        }
    }
}
