package org.healthtg.auth;

import org.healthtg.session.SessionService;
import org.healthtg.user.UserAccount;
import org.healthtg.user.UserService;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

class AuthServiceTest {
    @Test
    void rejectsAccountOutsideAllowlistBeforeCreatingUserOrSession() {
        TelegramInitDataVerifier verifier = mock(TelegramInitDataVerifier.class);
        UserService users = mock(UserService.class);
        SessionService sessions = mock(SessionService.class);
        when(verifier.verify("signed-data")).thenReturn(new VerifiedTelegramUser(20002));
        AuthService service = new AuthService(verifier, properties(), users, sessions);

        assertThrows(AccessDeniedException.class, () -> service.authenticate("signed-data"));
        verifyNoInteractions(users, sessions);
    }

    @Test
    void createsSessionForAllowedVerifiedAccount() {
        TelegramInitDataVerifier verifier = mock(TelegramInitDataVerifier.class);
        UserService users = mock(UserService.class);
        SessionService sessions = mock(SessionService.class);
        UUID userId = UUID.randomUUID();
        UserAccount user = new UserAccount(userId, 10001, ZoneId.of("Europe/Warsaw"), true);
        when(verifier.verify("signed-data")).thenReturn(new VerifiedTelegramUser(10001));
        when(users.findOrCreate(10001)).thenReturn(user);
        when(sessions.issue(userId)).thenReturn(
                new SessionService.IssuedSession("opaque", 3600, Instant.parse("2026-09-22T13:00:00Z")));

        AuthService.AuthenticationResult result =
                new AuthService(verifier, properties(), users, sessions).authenticate("signed-data");

        assertEquals(user, result.user());
        assertEquals("opaque", result.session().token());
    }

    private static AuthProperties properties() {
        return new AuthProperties("token", "10001", Duration.ofMinutes(15), Duration.ofMinutes(60),
                ZoneId.of("Europe/Warsaw"));
    }
}
