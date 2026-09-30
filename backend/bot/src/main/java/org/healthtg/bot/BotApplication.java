package org.healthtg.bot;

import java.util.concurrent.TimeUnit;
import okhttp3.OkHttpClient;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.WebApplicationType;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.healthtg.core.dialog.DialogStateService;
import org.healthtg.core.entry.EntryCoreService;
import org.healthtg.user.UserService;
import java.time.Clock;
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
    BotFlow botFlow(UserService users, EntryCoreService entries, DialogStateService dialogs, Clock clock) {
        return new CoreBotFlow(users, entries, dialogs, clock);
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
