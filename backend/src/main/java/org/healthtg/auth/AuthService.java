package org.healthtg.auth;

import org.healthtg.session.SessionService;
import org.healthtg.user.UserAccount;
import org.healthtg.user.UserService;
import org.springframework.stereotype.Service;

@Service
public class AuthService {
    private final TelegramInitDataVerifier verifier;
    private final AuthProperties properties;
    private final UserService userService;
    private final SessionService sessionService;

    public AuthService(TelegramInitDataVerifier verifier, AuthProperties properties,
                       UserService userService, SessionService sessionService) {
        this.verifier = verifier;
        this.properties = properties;
        this.userService = userService;
        this.sessionService = sessionService;
    }

    public AuthenticationResult authenticate(String initData) {
        VerifiedTelegramUser telegramUser = verifier.verify(initData);
        if (!properties.allowedIds().contains(telegramUser.telegramId())) {
            throw new AccessDeniedException("Telegram account is not allowed");
        }
        UserAccount user = userService.findOrCreate(telegramUser.telegramId());
        SessionService.IssuedSession session = sessionService.issue(user.id());
        return new AuthenticationResult(user, session);
    }

    public record AuthenticationResult(UserAccount user, SessionService.IssuedSession session) {
    }
}
