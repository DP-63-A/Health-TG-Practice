package org.healthtg.session;

import org.springframework.data.mongodb.repository.MongoRepository;

interface MongoSessionRepository extends MongoRepository<MongoSessionDocument, String> {
}
