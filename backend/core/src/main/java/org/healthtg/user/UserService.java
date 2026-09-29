package org.healthtg.user;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Service;

import java.time.ZoneId;
import java.util.UUID;

@Service
public class UserService {
    private final UserStore userStore;
    private final ZoneId defaultTimezone;

    @Autowired
    public UserService(UserStore userStore,
                       @Value("${health-tg.auth.default-timezone:Europe/Warsaw}") String defaultTimezone) {
        this(userStore, ZoneId.of(defaultTimezone));
    }

    public UserService(UserStore userStore, ZoneId defaultTimezone) {
        this.userStore = userStore;
        this.defaultTimezone = defaultTimezone;
    }

    public UserAccount findOrCreate(long telegramId) {
        return userStore.findByTelegramId(telegramId).orElseGet(() -> createOrReadConcurrent(telegramId));
    }

    private UserAccount createOrReadConcurrent(long telegramId) {
        try {
            return userStore.save(new UserAccount(UUID.randomUUID(), telegramId, defaultTimezone, true));
        } catch (DuplicateKeyException duplicate) {
            return userStore.findByTelegramId(telegramId).orElseThrow(() -> duplicate);
        }
    }

    public UserAccount requireById(UUID id) {
        return userStore.findById(id).orElseThrow(UserNotFoundException::new);
    }
}
