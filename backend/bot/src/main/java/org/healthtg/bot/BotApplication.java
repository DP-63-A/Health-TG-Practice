package org.healthtg.bot;

import java.util.concurrent.TimeUnit;
import okhttp3.OkHttpClient;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.WebApplicationType;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.context.annotation.Bean;
import org.healthtg.core.dialog.DialogStateService;
import org.healthtg.core.entry.EntryCoreService;
import org.healthtg.user.UserAccount;
import org.healthtg.user.UserService;
import org.healthtg.user.UserStore;
import java.time.Clock;
import java.util.Optional;
import java.util.UUID;
import org.telegram.telegrambots.client.okhttp.OkHttpTelegramClient;
import org.telegram.telegrambots.meta.generics.TelegramClient;

@SpringBootApplication(scanBasePackages = {"org.healthtg.bot", "org.healthtg.core", "org.healthtg.user"})
public class BotApplication {
    public static void main(String[] args) {
        SpringApplication app = new SpringApplication(BotApplication.class);
        app.setWebApplicationType(WebApplicationType.NONE);
        app.run(args);
    }

    @Bean RuntimeSettings runtimeSettings() { return RuntimeSettings.from(System.getenv()); }

    @Bean Clock clock() { return Clock.systemUTC(); }

    @Bean
    @ConditionalOnProperty(name = "health-tg.core.storage.enabled", matchIfMissing = true)
    BotFlow botFlow(UserService users, EntryCoreService entries, DialogStateService dialogs, Clock clock, RuntimeSettings settings, TelegramTransport transport, org.healthtg.core.file.FileStorageService files) {
        var flow = new CoreBotFlow(users, entries, dialogs, clock, settings.miniAppUrl());
        String mode = System.getenv().getOrDefault("FOOD_RECOGNITION_MODE", "disabled");
        if (mode.equals("disabled")) return flow;
        try {
            org.healthtg.bot.recognition.RecognitionProvider provider = switch (mode) {
                case "fixture" -> org.healthtg.bot.recognition.FixtureRecognitionProvider.bundled();
                case "live" -> new org.healthtg.bot.recognition.GeminiRecognitionProvider(System.getenv("GEMINI_API_KEY"),
                        System.getenv().getOrDefault("GEMINI_MODEL", org.healthtg.bot.recognition.GeminiRecognitionProvider.DEFAULT_MODEL));
                default -> throw new IllegalArgumentException("FOOD_RECOGNITION_MODE: disabled, fixture or live required");
            };
            var recognition = new org.healthtg.bot.recognition.FoodRecognitionService(new org.healthtg.bot.recognition.ImageValidator(),
                    provider, new org.healthtg.bot.recognition.RecognitionResponseParser());
            return flow.withPhotos(new org.healthtg.bot.visual.FoodPhotoFlow(entries, dialogs, files, recognition,
                    new org.healthtg.bot.visual.TelegramImageLoader(transport.client(), settings.token()),
                    new org.healthtg.bot.draft.DraftReviewFlow(entries, dialogs, settings.miniAppUrl()), provider.mode().name()));
        } catch (org.healthtg.bot.recognition.RecognitionException invalid) {
            throw new IllegalArgumentException("Invalid food recognition configuration");
        }
    }

    @Bean
    @ConditionalOnMissingBean(BotFlow.class)
    BotFlow unavailableBotFlow() {
        return BotFlow.unavailable();
    }

    @Bean
    @ConditionalOnProperty(name = "health-tg.core.storage.enabled", havingValue = "false")
    @ConditionalOnMissingBean(UserStore.class)
    UserStore unavailableUserStore() {
        return new UserStore() {
            @Override public Optional<UserAccount> findById(UUID id) { return Optional.empty(); }
            @Override public Optional<UserAccount> findByTelegramId(long telegramId) { return Optional.empty(); }
            @Override public UserAccount save(UserAccount user) {
                throw new IllegalStateException("Core storage is disabled");
            }
        };
    }

    @Bean TelegramTransport telegramTransport(RuntimeSettings settings) {
        OkHttpClient http = new OkHttpClient.Builder().connectTimeout(10, TimeUnit.SECONDS)
                .readTimeout(45, TimeUnit.SECONDS).callTimeout(50, TimeUnit.SECONDS).build();
        TelegramClient client = new OkHttpTelegramClient(http, settings.token());
        return new TelegramTransport(client, () -> {
            http.dispatcher().cancelAll();
            http.dispatcher().executorService().shutdownNow();
            http.connectionPool().evictAll();
        });
    }

    @Bean BotRuntime botRuntime(RuntimeSettings settings, BotFlow flow, TelegramTransport transport) {
        return new BotRuntime(transport.client(), settings, transport.close(), flow);
    }
}
