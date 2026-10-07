package org.healthtg.bot;

import com.mongodb.client.MongoClient;
import com.mongodb.client.MongoClients;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.ComponentScan;
import org.springframework.data.mongodb.core.MongoTemplate;
import org.springframework.data.mongodb.repository.config.EnableMongoRepositories;

import java.time.Clock;

@org.springframework.context.annotation.Import({org.healthtg.core.file.FileOperationGuard.class, org.healthtg.core.file.StoredFileLifecycle.class})
@TestConfiguration(proxyBeanMethods = false)
@ComponentScan(basePackages = {"org.healthtg.core.entry", "org.healthtg.core.dialog", "org.healthtg.user"})
@EnableMongoRepositories(basePackages = {"org.healthtg.core.entry", "org.healthtg.core.dialog", "org.healthtg.user"})
class BotCoreStorageTestConfiguration {
    @Bean
    MongoClient mongoClient(@Value("${test.mongo.uri}") String uri) {
        return MongoClients.create(uri);
    }

    @Bean
    MongoTemplate mongoTemplate(MongoClient client, @Value("${test.mongo.database}") String database) {
        return new MongoTemplate(client, database);
    }

    @Bean
    Clock clock() {
        return Clock.systemUTC();
    }
}
