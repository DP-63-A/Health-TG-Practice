package org.healthtg.user;

import java.util.Optional;
import java.util.UUID;

public interface UserStore {
    Optional<UserAccount> findById(UUID id);
    Optional<UserAccount> findByTelegramId(long telegramId);
    UserAccount save(UserAccount user);
}
