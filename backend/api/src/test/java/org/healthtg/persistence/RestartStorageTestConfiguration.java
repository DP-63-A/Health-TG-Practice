package org.healthtg.persistence;

import com.mongodb.client.MongoClient;
import com.mongodb.client.MongoClients;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.ComponentScan;
import org.springframework.data.mongodb.core.MongoTemplate;
import org.springframework.data.mongodb.repository.config.EnableMongoRepositories;

import java.time.Clock;

@TestConfiguration(proxyBeanMethods = false)
@ComponentScan(basePackages = {"org.healthtg.core.entry", "org.healthtg.core.dialog"})
@EnableMongoRepositories(basePackages = {"org.healthtg.core.entry", "org.healthtg.core.dialog"})
class RestartStorageTestConfiguration {
    @Bean
    MongoClient mongoClient(@Value("${restart.mongo.uri}") String uri) {
        return MongoClients.create(uri);
    }

    @Bean
    MongoTemplate mongoTemplate(MongoClient mongoClient,
                                @Value("${restart.mongo.database}") String databaseName) {
        return new MongoTemplate(mongoClient, databaseName);
    }

    @Bean
    Clock clock() {
        return Clock.systemUTC();
    }
}
