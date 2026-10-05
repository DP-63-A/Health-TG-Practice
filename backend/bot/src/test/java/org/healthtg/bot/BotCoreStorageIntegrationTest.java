package org.healthtg.bot;

import org.healthtg.core.dialog.DialogStateService;
import org.healthtg.core.entry.EntryCoreService;
import org.healthtg.core.entry.EntryStatus;
import org.healthtg.core.entry.OwnerContext;
import org.healthtg.user.UserService;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.util.TestPropertyValues;
import org.springframework.boot.SpringApplication;
import org.springframework.beans.factory.support.BeanDefinitionRegistry;
import org.springframework.context.annotation.AnnotationConfigApplicationContext;
import org.testcontainers.containers.MongoDBContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.utility.DockerImageName;

import java.time.Clock;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;

import org.telegram.telegrambots.meta.api.methods.GetMe;
import org.telegram.telegrambots.meta.api.methods.updates.GetUpdates;
import org.telegram.telegrambots.meta.api.methods.updates.GetWebhookInfo;
import org.telegram.telegrambots.meta.api.objects.User;
import org.telegram.telegrambots.meta.api.objects.WebhookInfo;
import org.telegram.telegrambots.meta.generics.TelegramClient;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;
import static org.mockito.ArgumentMatchers.any;

@Testcontainers(disabledWithoutDocker = true)
class BotCoreStorageIntegrationTest {
    @Container
    static final MongoDBContainer MONGO = new MongoDBContainer(
            DockerImageName.parse("mongodb/mongodb-community-server:8.0-ubi9-slim")
                    .asCompatibleSubstituteFor("mongo"));

    @Test
    void botFlowsPersistThroughCoreAndRestoreAfterRestart() {
        String database = "bot_core_" + UUID.randomUUID().toString().replace("-", "");
        UUID draftId;
        UUID draftOwner;
        UUID clarificationOwner;
        String restoredCategoryCallback;
        String restoredScoreCallback;

        try (var first = context(database)) {
            CoreBotFlow flow = flow(first);
            flow.handleMessage(message(1001, 10, "24.09.2026 за день прошёл 8000 шагов"));
            flow.handleMessage(message(1001, 10, "24.09.2026 за день прошёл 8000 шагов"));

            UserService users = first.getBean(UserService.class);
            EntryCoreService entries = first.getBean(EntryCoreService.class);
            draftOwner = users.findOrCreate(1001).id();
            var draft = entries.findActiveDraft(new OwnerContext(draftOwner)).orElseThrow();
            draftId = draft.id();
            assertEquals(EntryStatus.DRAFT, draft.status());

            var categories = (BotAction.SendInlineMessage) flow.beginCheckin(message(2002, 20, "/state")).getFirst();
            String category = categories.rows().getFirst().getFirst().callbackData();
            var scores = (BotAction.SendInlineMessage) flow.handleCallback(callback(2002, 21, category)).get(1);
            String score = scores.rows().getFirst().get(4).callbackData();
            flow.handleCallback(callback(2002, 22, score));
            flow.handleCallback(callback(2002, 22, score));

            UUID checkinOwner = users.findOrCreate(2002).id();
            assertEquals(1, entries.listConfirmedEntries(new org.healthtg.core.entry.ListConfirmedEntriesQuery(
                    new OwnerContext(checkinOwner), java.time.LocalDate.of(2026, 1, 1),
                    java.time.LocalDate.of(2030, 1, 1), java.time.ZoneId.of("UTC"), java.util.Set.of())).size());

            var restartCategories = (BotAction.SendInlineMessage) flow.beginCheckin(
                    message(3003, 30, "/state")).getFirst();
            String restartCategory = restartCategories.rows().get(2).getFirst().callbackData();
            restoredCategoryCallback = restartCategory;
            var restartScores = (BotAction.SendInlineMessage) flow.handleCallback(
                    callback(3003, 31, restartCategory)).get(1);
            restoredScoreCallback = restartScores.rows().getFirst().get(3).callbackData();

            flow.handleMessage(message(4004, 40, "24.09.2026 пульс 72"));
            clarificationOwner = users.findOrCreate(4004).id();
            assertEquals("text_clarification", dialogs(first).find(new OwnerContext(clarificationOwner))
                    .orElseThrow().step());
        }

        try (var restarted = context(database)) {
            CoreBotFlow flow = flow(restarted);
            EntryCoreService entries = restarted.getBean(EntryCoreService.class);
            DialogStateService dialogs = restarted.getBean(DialogStateService.class);
            UserService users = restarted.getBean(UserService.class);
            var restored = entries.findActiveDraft(new OwnerContext(draftOwner)).orElseThrow();
            assertEquals(draftId, restored.id());
            assertEquals("draft_review", dialogs.find(new OwnerContext(draftOwner)).orElseThrow().step());

            var replayedCategory = flow.handleCallback(callback(3003, 31, restoredCategoryCallback));
            assertTrue(replayedCategory.get(1) instanceof BotAction.SendInlineMessage);
            flow.handleCallback(callback(3003, 32, restoredScoreCallback));
            UUID restoredCheckinOwner = users.findOrCreate(3003).id();
            assertEquals(1, entries.listConfirmedEntries(new org.healthtg.core.entry.ListConfirmedEntriesQuery(
                    new OwnerContext(restoredCheckinOwner), java.time.LocalDate.of(2026, 1, 1),
                    java.time.LocalDate.of(2030, 1, 1), java.time.ZoneId.of("UTC"), java.util.Set.of())).size());

            var activeDraft = (BotAction.SendInlineMessage) flow.handleMessage(
                    message(1001, 12, "новая запись")).getFirst();
            String cancel = activeDraft.rows().getFirst().getFirst().callbackData();
            flow.handleCallback(callback(1001, 13, cancel));
            assertFalse(entries.findActiveDraft(new OwnerContext(draftOwner)).isPresent());
            assertEquals(EntryStatus.CANCELLED,
                    entries.requireEntry(new OwnerContext(draftOwner), draftId).status());

            flow.handleMessage(message(4004, 41, "ударов в минуту"));
            var clarifiedDraft = entries.findActiveDraft(new OwnerContext(clarificationOwner)).orElseThrow();
            assertEquals("bpm", clarifiedDraft.payload().get("unit"));
            restarted.getBean(org.springframework.data.mongodb.core.MongoTemplate.class).getDb().drop();
        }
    }

    @Test
    void productionBotApplicationDiscoversCoreMongoRepositories() throws Exception {
        TelegramClient client = mock(TelegramClient.class);
        WebhookInfo webhook = new WebhookInfo();
        webhook.setUrl("");
        when(client.execute(any(GetWebhookInfo.class))).thenReturn(webhook);
        User bot = new User(9999L, "Synthetic bot", true);
        bot.setUserName("storage_test_bot");
        when(client.execute(any(GetMe.class))).thenReturn(bot);
        when(client.execute(any(GetUpdates.class))).thenReturn(new java.util.ArrayList<>());
        RuntimeSettings settings = RuntimeSettings.from(RuntimeSettingsTest.environment());
        AtomicInteger closed = new AtomicInteger();
        TelegramTransport transport = new TelegramTransport(client, closed::incrementAndGet);
        SpringApplication application = new SpringApplication(BotApplication.class);
        application.setDefaultProperties(Map.of("spring.main.web-application-type", "none"));
        application.addInitializers(context -> context.addBeanFactoryPostProcessor(factory -> {
            var registry = (BeanDefinitionRegistry) factory;
            registry.removeBeanDefinition("runtimeSettings");
            registry.removeBeanDefinition("telegramTransport");
            factory.registerSingleton("runtimeSettings", settings);
            factory.registerSingleton("telegramTransport", transport);
        }));

        String database = "bot_startup_" + UUID.randomUUID().toString().replace("-", "");
        try (var context = application.run(
                "--spring.data.mongodb.uri=" + MONGO.getReplicaSetUrl(),
                "--spring.data.mongodb.database=" + database,
                "--spring.data.mongodb.auto-index-creation=true")) {
            assertTrue(context.containsBean("mongoEntryRepository"));
            assertTrue(context.containsBean("mongoDialogStateRepository"));
            assertTrue(context.containsBean("mongoUserRepository"));
            assertTrue(context.containsBean("botFlow"));
            BotRuntime runtime = context.getBean(BotRuntime.class);
            assertTrue(runtime.isRunning());
            assertTrue(context.getBean(BotFlow.class) instanceof CoreBotFlow);
        }
        assertEquals(1, closed.get());
    }

    private static CoreBotFlow flow(AnnotationConfigApplicationContext context) {
        return new CoreBotFlow(context.getBean(UserService.class), context.getBean(EntryCoreService.class),
                context.getBean(DialogStateService.class), context.getBean(Clock.class));
    }

    private static DialogStateService dialogs(AnnotationConfigApplicationContext context) {
        return context.getBean(DialogStateService.class);
    }

    private static AnnotationConfigApplicationContext context(String database) {
        var context = new AnnotationConfigApplicationContext();
        TestPropertyValues.of("test.mongo.uri=" + MONGO.getReplicaSetUrl(),
                "test.mongo.database=" + database).applyTo(context);
        context.register(BotCoreStorageTestConfiguration.class);
        context.refresh();
        return context;
    }

    private static BotUpdate message(long user, long updateId, String text) {
        return new BotUpdate(updateId, BotUpdate.Kind.MESSAGE, BotUpdate.ChatType.PRIVATE,
                user, user, false, text, List.of(), null, null,
                java.time.Instant.parse("2026-09-25T08:00:00Z"));
    }

    private static BotUpdate callback(long user, long updateId, String data) {
        return new BotUpdate(updateId, BotUpdate.Kind.CALLBACK, BotUpdate.ChatType.PRIVATE,
                user, user, false, null, List.of(), "callback-" + updateId, data);
    }
}
