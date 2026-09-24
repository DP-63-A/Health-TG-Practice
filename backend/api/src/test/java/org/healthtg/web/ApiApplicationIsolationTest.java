package org.healthtg.web;

import org.healthtg.session.SessionStore;
import org.healthtg.user.UserStore;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.context.ApplicationContext;
import org.springframework.test.context.bean.override.mockito.MockitoBean;

import static org.junit.jupiter.api.Assertions.*;

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT, properties = {
        "spring.autoconfigure.exclude=org.springframework.boot.autoconfigure.mongo.MongoAutoConfiguration,"
                + "org.springframework.boot.autoconfigure.data.mongo.MongoDataAutoConfiguration,"
                + "org.springframework.boot.autoconfigure.data.mongo.MongoRepositoriesAutoConfiguration",
        "health-tg.auth.telegram-bot-token=",
        "health-tg.auth.allowed-telegram-ids="
})
class ApiApplicationIsolationTest {
    @MockitoBean UserStore userStore;
    @MockitoBean SessionStore sessionStore;
    @Autowired TestRestTemplate http;
    @Autowired ApplicationContext context;

    @Test
    void apiServesHttpWithoutBotConfigurationOrBotClasses() {
        assertThrows(ClassNotFoundException.class, () -> Class.forName("org.healthtg.bot.BotApplication"));
        assertThrows(ClassNotFoundException.class, () -> Class.forName("org.telegram.telegrambots.meta.generics.TelegramClient"));
        assertFalse(context.containsBean("botRuntime"));
        assertFalse(context.containsBean("runtimeSettings"));
        var response = http.getForEntity("/api/v1/me", String.class);
        assertEquals(401, response.getStatusCode().value());
        assertNotNull(response.getHeaders().getFirst("X-Request-Id"));
        assertTrue(response.getBody().contains("UNAUTHORIZED"));
    }
}
