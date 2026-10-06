package org.healthtg.bot;

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Configuration;
import org.springframework.data.mongodb.repository.config.EnableMongoRepositories;

@Configuration(proxyBeanMethods = false)
@ConditionalOnProperty(name = "health-tg.core.storage.enabled", matchIfMissing = true)
@EnableMongoRepositories(basePackages = {"org.healthtg.core.entry", "org.healthtg.core.dialog", "org.healthtg.user"})
class BotMongoRepositoriesConfiguration {
}
