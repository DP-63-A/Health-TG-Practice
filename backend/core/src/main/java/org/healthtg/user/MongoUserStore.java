package org.healthtg.user;

import org.springframework.stereotype.Repository;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;

import java.time.ZoneId;
import java.util.Optional;
import java.util.UUID;

@Repository
@ConditionalOnProperty(name = "health-tg.core.storage.enabled", matchIfMissing = true)
class MongoUserStore implements UserStore {
    private final MongoUserRepository repository;

    MongoUserStore(MongoUserRepository repository) {
        this.repository = repository;
    }

    @Override
    public Optional<UserAccount> findById(UUID id) {
        return repository.findById(id.toString()).map(MongoUserStore::toDomain);
    }

    @Override
    public Optional<UserAccount> findByTelegramId(long telegramId) {
        return repository.findByTelegramId(telegramId).map(MongoUserStore::toDomain);
    }

    @Override
    public UserAccount save(UserAccount user) {
        MongoUserDocument saved = repository.save(new MongoUserDocument(
                user.id().toString(), user.telegramId(), user.timezone().getId(), user.standAccess()));
        return toDomain(saved);
    }

    private static UserAccount toDomain(MongoUserDocument document) {
        return new UserAccount(UUID.fromString(document.id()), document.telegramId(),
                ZoneId.of(document.timezone()), document.standAccess());
    }
}
