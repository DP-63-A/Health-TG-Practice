package org.healthtg.core.file;

import org.healthtg.core.entry.EntryCoreService;
import org.springframework.data.mongodb.core.MongoTemplate;
import org.springframework.data.mongodb.repository.support.MongoRepositoryFactory;
import java.time.Clock;

/** Test-only access to the package-private repository; production visibility stays unchanged. */
public final class FoodPhotoFileTestSupport {
    private FoodPhotoFileTestSupport() { }
    public static FileStorageService create(MongoTemplate mongo, EntryCoreService entries, String root, org.healthtg.core.entry.EntryStore store) {
        return new DefaultFileStorageService(new MongoRepositoryFactory(mongo)
            .getRepository(MongoStoredFileRepository.class), entries, Clock.systemUTC(), root, new FileOperationGuard(root), new StoredFileLifecycle(mongo), store);
    }
    public static long count(MongoTemplate mongo) {
        return mongo.getCollection("stored_files").countDocuments(new org.bson.Document("lifecycle", new org.bson.Document("$ne", "DELETED")));
    }
}
