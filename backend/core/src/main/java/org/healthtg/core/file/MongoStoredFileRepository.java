package org.healthtg.core.file;

import org.springframework.data.mongodb.repository.MongoRepository;

import java.util.Optional;

interface MongoStoredFileRepository extends MongoRepository<MongoStoredFileDocument, String> {
    Optional<MongoStoredFileDocument> findByIdAndOwnerId(String id, String ownerId);
}
