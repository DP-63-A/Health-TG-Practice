package org.healthtg.user;

import java.util.Optional;

public interface UserStore {
    Optional<UserAccount> findById(java.util.UUID id);

    Optional<UserAccount> findByTelegramId(long telegramId);

    UserAccount save(UserAccount user);
}
