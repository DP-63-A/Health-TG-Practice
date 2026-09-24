package org.healthtg.session;

import org.healthtg.auth.AuthFailureException;
import org.healthtg.auth.AuthProperties;
import org.springframework.stereotype.Service;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.time.Clock;
import java.time.Instant;
import java.util.Base64;
import java.util.HexFormat;
import java.util.UUID;

@Service
public class SessionService {
    private static final SecureRandom RANDOM = new SecureRandom();

    private final SessionStore sessionStore;
    private final AuthProperties properties;
    private final Clock clock;

    public SessionService(SessionStore sessionStore, AuthProperties properties, Clock clock) {
        this.sessionStore = sessionStore;
        this.properties = properties;
        this.clock = clock;
    }

    public IssuedSession issue(UUID userId) {
        byte[] bytes = new byte[32];
        RANDOM.nextBytes(bytes);
        String token = Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
        Instant createdAt = Instant.now(clock);
        Instant expiresAt = createdAt.plus(properties.sessionTtl());
        sessionStore.save(new SessionRecord(hash(token), userId, createdAt, expiresAt));
        return new IssuedSession(token, properties.sessionTtl().toSeconds(), expiresAt);
    }

    public UUID authenticate(String token) {
        if (token == null || token.isBlank()) {
            throw unauthorized();
        }
        SessionRecord session = sessionStore.findByTokenHash(hash(token)).orElseThrow(SessionService::unauthorized);
        if (!Instant.now(clock).isBefore(session.expiresAt())) {
            throw unauthorized();
        }
        return session.userId();
    }

    private static String hash(String token) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
                    .digest(token.getBytes(StandardCharsets.UTF_8)));
        } catch (Exception exception) {
            throw new IllegalStateException("SHA-256 is unavailable", exception);
        }
    }

    private static AuthFailureException unauthorized() {
        return new AuthFailureException("Session missing or expired");
    }

    public record IssuedSession(String token, long expiresIn, Instant expiresAt) {
    }
}
