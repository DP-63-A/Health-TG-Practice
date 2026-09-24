package org.healthtg.bot;

import org.healthtg.bot.simulation.BotSimulation;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import java.io.ByteArrayOutputStream;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import static org.junit.jupiter.api.Assertions.*;

/** Presentation checks; the Windows/Gradle encoding boundary is checked separately. */
class BotSimulationTest {
    @ParameterizedTest @ValueSource(booleans = {false, true})
    void rendersReadableDemoAndAccessDecisionsWithoutInternalRecords(boolean withoutMiniApp) {
        var bytes = new ByteArrayOutputStream();
        var originalOut = System.out;
        try (var output = new PrintStream(bytes, true, StandardCharsets.UTF_8)) {
            System.setOut(output);
            BotSimulation.main(withoutMiniApp ? new String[]{"--without-mini-app"} : new String[0]);
        } finally {
            System.setOut(originalOut);
        }
        String text = bytes.toString(StandardCharsets.UTF_8);
        assertTrue(text.contains("ЛОКАЛЬНАЯ СИМУЛЯЦИЯ"));
        assertTrue(text.contains("сообщения в Telegram не отправляются"));
        assertTrue(text.contains("Токен не нужен"));
        assertTrue(text.contains("все пользователи вымышленные"));
        assertTrue(text.contains("Бот: Это учебный"));
        assertTrue(text.contains("Постоянная нижняя клавиатура: [Отметить состояние]"));
        assertEquals(2, text.split("Данные не сохранены", -1).length - 1);
        assertEquals(2, text.split("бот не отвечает — доступ запрещён", -1).length - 1);
        for (String internal : new String[]{"SendMessage[", "SetMenuButton[", "ReplyKeyboard[", "chatId=", "1001", "2002"})
            assertFalse(text.contains(internal), internal);
        if (withoutMiniApp) {
            assertTrue(text.contains("адрес ещё не настроен"));
            assertTrue(text.contains("Кабинет пока не подключён"));
            assertFalse(text.contains("https://example.com"));
        } else {
            assertTrue(text.contains("Отдельная кнопка меню: [Открыть кабинет]"));
            assertTrue(text.contains("https://example.com/mini-app (пример, не настоящий кабинет)"));
        }
    }
}
