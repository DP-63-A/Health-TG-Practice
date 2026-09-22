package org.healthtg.core.entry;

import org.springframework.data.mongodb.repository.MongoRepository;

import java.util.Optional;

interface MongoEntryRepository extends MongoRepository<MongoEntryDocument, String> {
    Optional<MongoEntryDocument> findByTelegramUpdateKey(String telegramUpdateKey);

    Optional<MongoEntryDocument> findBySubmissionId(String submissionId);

    Optional<MongoEntryDocument> findFirstByOwnerIdAndStatus(String ownerId, String status);
}
