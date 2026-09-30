package org.healthtg.bot;

import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.support.BeanDefinitionRegistry;
import org.springframework.boot.SpringApplication;
import org.telegram.telegrambots.meta.api.methods.GetMe;
import org.telegram.telegrambots.meta.api.methods.updates.GetUpdates;
import org.telegram.telegrambots.meta.api.methods.updates.GetWebhookInfo;
import org.telegram.telegrambots.meta.api.objects.User;
import org.telegram.telegrambots.meta.api.objects.WebhookInfo;
import org.telegram.telegrambots.meta.generics.TelegramClient;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

class BotApplicationIsolationTest {
    @Test
    void productionConfigurationStartsOnlyBotAndStopsMockTransport() throws Exception {
        var client = mock(TelegramClient.class);
        var webhook = new WebhookInfo();
        webhook.setUrl("");
        when(client.execute(any(GetWebhookInfo.class))).thenReturn(webhook);
        var me = new User(9999L, "Synthetic bot", true);
        me.setUserName("isolation_test_bot");
        when(client.execute(any(GetMe.class))).thenReturn(me);
        var polling = new CountDownLatch(1);
        var stopped = new CountDownLatch(1);
        when(client.execute(any(GetUpdates.class))).thenAnswer(call -> {
            polling.countDown();
            try { new CountDownLatch(1).await(); }
            catch (InterruptedException expected) {
                Thread.currentThread().interrupt();
                stopped.countDown();
            }
            return List.of();
        });
        var settings = RuntimeSettings.from(RuntimeSettingsTest.environment());
        var closed = new AtomicInteger();
        var runtime = new BotRuntime(client, settings, closed::incrementAndGet);
        var application = new SpringApplication(BotApplication.class);
        application.setDefaultProperties(java.util.Map.of(
                "health-tg.core.storage.enabled", "false",
                "spring.autoconfigure.exclude",
                "org.springframework.boot.autoconfigure.mongo.MongoAutoConfiguration,"
                        + "org.springframework.boot.autoconfigure.data.mongo.MongoDataAutoConfiguration,"
                        + "org.springframework.boot.autoconfigure.data.mongo.MongoRepositoriesAutoConfiguration"));
        // Only external configuration/transport are replaced; production auto-configuration runs.
        application.addInitializers(context -> context.addBeanFactoryPostProcessor(factory -> {
            var registry = (BeanDefinitionRegistry) factory;
            registry.removeBeanDefinition("runtimeSettings");
            registry.removeBeanDefinition("botRuntime");
            registry.removeBeanDefinition("telegramTransport");
            factory.registerSingleton("runtimeSettings", settings);
            factory.registerSingleton("botRuntime", runtime);
            factory.registerSingleton("telegramTransport",
                    new TelegramTransport(client, () -> {}));
            factory.registerSingleton("userStore", mock(org.healthtg.user.UserStore.class));
        }));
        try {
            try (var context = application.run()) {
                assertEquals("health-tg-bot", context.getEnvironment().getProperty("spring.application.name"));
                assertEquals("none", context.getEnvironment().getProperty("spring.main.web-application-type"));
                assertFalse(context.containsBean("webServerFactory"));
                assertFalse(context.containsBean("mongoTemplate"));
                assertThrows(ClassNotFoundException.class, () -> Class.forName("org.healthtg.HealthTgApplication"));
                assertThrows(ClassNotFoundException.class, () -> Class.forName("org.springframework.web.servlet.DispatcherServlet"));
                assertFalse(context.containsBean("entryCoreService"));
                assertTrue(polling.await(5, TimeUnit.SECONDS));
                assertTrue(runtime.isRunning());
            }
            // These assertions must observe Spring's shutdown, before fallback cleanup.
            assertTrue(stopped.await(5, TimeUnit.SECONDS));
            assertFalse(runtime.isRunning());
            assertEquals(1, closed.get());
        } finally {
            runtime.close();
        }
    }
}
