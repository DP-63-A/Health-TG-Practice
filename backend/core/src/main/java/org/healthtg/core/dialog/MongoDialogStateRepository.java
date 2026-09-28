package org.healthtg.core.dialog;

import org.springframework.data.mongodb.repository.MongoRepository;

interface MongoDialogStateRepository extends MongoRepository<MongoDialogStateDocument, String> {
}
