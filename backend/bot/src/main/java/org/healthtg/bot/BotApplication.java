package org.healthtg.bot;

import java.util.concurrent.TimeUnit;
import okhttp3.OkHttpClient;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.WebApplicationType;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.context.annotation.Bean;
import org.telegram.telegrambots.client.okhttp.OkHttpTelegramClient;

@SpringBootApplication
public class BotApplication {
    public static void main(String[] args) {
        SpringApplication app = new SpringApplication(BotApplication.class);
        app.setWebApplicationType(WebApplicationType.NONE);
        app.run(args);
    }

    @Bean RuntimeSettings runtimeSettings() { return RuntimeSettings.from(System.getenv()); }

    @Bean BotRuntime botRuntime(RuntimeSettings settings) {
        OkHttpClient http = new OkHttpClient.Builder().connectTimeout(10, TimeUnit.SECONDS)
                .readTimeout(45, TimeUnit.SECONDS).callTimeout(50, TimeUnit.SECONDS).build();
        return new BotRuntime(new OkHttpTelegramClient(http, settings.token()), settings, () -> {
            http.dispatcher().cancelAll();
            http.dispatcher().executorService().shutdownNow();
            http.connectionPool().evictAll();
        });
    }
}
