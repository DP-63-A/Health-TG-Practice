package org.healthtg.web;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import org.healthtg.auth.AuthService;
import org.healthtg.security.CurrentUser;
import org.healthtg.user.UserAccount;
import org.healthtg.user.UserService;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.UUID;

@RestController
@RequestMapping("/api/v1")
public class AuthController {
    private final AuthService authService;
    private final UserService userService;

    public AuthController(AuthService authService, UserService userService) {
        this.authService = authService;
        this.userService = userService;
    }

    @PostMapping("/auth/telegram")
    public TelegramAuthResponse authenticate(@Valid @RequestBody TelegramAuthRequest request) {
        AuthService.AuthenticationResult result = authService.authenticate(request.initData());
        return new TelegramAuthResponse(result.session().token(), "Bearer",
                result.session().expiresIn(), UserResponse.from(result.user()));
    }

    @GetMapping("/me")
    public UserResponse me(@AuthenticationPrincipal CurrentUser currentUser) {
        return UserResponse.from(userService.requireById(currentUser.id()));
    }

    public record TelegramAuthRequest(@NotBlank String initData) {
    }

    public record TelegramAuthResponse(String sessionToken, String tokenType, long expiresIn, UserResponse user) {
    }

    public record UserResponse(UUID id, long telegramId, String timezone, boolean standAccess) {
        static UserResponse from(UserAccount user) {
            return new UserResponse(user.id(), user.telegramId(), user.timezone().getId(), user.standAccess());
        }
    }
}
