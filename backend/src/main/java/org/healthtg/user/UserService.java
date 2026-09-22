package org.healthtg.user;

import org.healthtg.auth.AuthProperties;
import org.healthtg.auth.AuthFailureException;
import org.springframework.stereotype.Service;

import java.util.UUID;

@Service
public class UserService {
    private final UserStore userStore;
    private final AuthProperties properties;

    public UserService(UserStore userStore, AuthProperties properties) {
        this.userStore = userStore;
        this.properties = properties;
    }

    public UserAccount findOrCreate(long telegramId) {
        return userStore.findByTelegramId(telegramId).orElseGet(() -> userStore.save(
                new UserAccount(UUID.randomUUID(), telegramId, properties.defaultTimezone(), true)));
    }

    public UserAccount requireById(UUID id) {
        return userStore.findById(id).orElseThrow(() -> new AuthFailureException("Session missing or expired"));
    }
}
