package org.healthtg.user;

import org.springframework.data.mongodb.repository.MongoRepository;

import java.util.Optional;

interface MongoUserRepository extends MongoRepository<MongoUserDocument, String> {
    Optional<MongoUserDocument> findByTelegramId(long telegramId);
}
