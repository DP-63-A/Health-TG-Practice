package org.healthtg.session;

import org.springframework.stereotype.Repository;

import java.util.Optional;
import java.util.UUID;

@Repository
class MongoSessionStore implements SessionStore {
    private final MongoSessionRepository repository;

    MongoSessionStore(MongoSessionRepository repository) {
        this.repository = repository;
    }

    @Override
    public SessionRecord save(SessionRecord session) {
        return toDomain(repository.save(new MongoSessionDocument(session.tokenHash(),
                session.userId().toString(), session.createdAt(), session.expiresAt())));
    }

    @Override
    public Optional<SessionRecord> findByTokenHash(String tokenHash) {
        return repository.findById(tokenHash).map(MongoSessionStore::toDomain);
    }

    private static SessionRecord toDomain(MongoSessionDocument document) {
        return new SessionRecord(document.tokenHash(), UUID.fromString(document.userId()),
                document.createdAt(), document.expiresAt());
    }
}
