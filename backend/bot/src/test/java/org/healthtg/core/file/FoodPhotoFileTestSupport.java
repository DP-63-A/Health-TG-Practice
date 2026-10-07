package org.healthtg.core.file;

import org.healthtg.core.entry.EntryCoreService;
import org.springframework.data.mongodb.core.MongoTemplate;
import org.springframework.data.mongodb.repository.support.MongoRepositoryFactory;
import java.time.Clock;

/** Test-only access to the package-private repository; production visibility stays unchanged. */
public final class FoodPhotoFileTestSupport {
    private FoodPhotoFileTestSupport() { }
    public static FileStorageService create(MongoTemplate mongo, EntryCoreService entries, String root) {
        return new DefaultFileStorageService(new MongoRepositoryFactory(mongo)
            .getRepository(MongoStoredFileRepository.class), entries, Clock.systemUTC(), root);
    }
    public static long count(MongoTemplate mongo) {
        return new MongoRepositoryFactory(mongo).getRepository(MongoStoredFileRepository.class).count();
    }
}
