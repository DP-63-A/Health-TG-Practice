package org.healthtg.bot.simulation;

import org.healthtg.bot.*;
import java.net.URI;
import java.util.List;
import java.util.Set;

/** Offline demonstration with synthetic IDs only; never reads a token or sends network traffic. */
public final class BotSimulation {
    private BotSimulation() {}

    public static void main(String[] args) {
        // example.com is a placeholder; this simulation does not claim that a Mini App exists there.
        URI url = args.length == 1 && args[0].equals("--without-mini-app")
                ? null : URI.create("https://example.com/mini-app");
        BotHandler handler = new BotHandler(new BotSettings(Set.of(1001L), "demo_bot", url),
                QuickCheckin.unavailable());
        System.out.println("ЛОКАЛЬНАЯ СИМУЛЯЦИЯ — сообщения в Telegram не отправляются.");
        System.out.println("Токен не нужен, все пользователи вымышленные, данные не сохраняются.");
        demonstrate(handler, "1. Разрешённый пользователь: /start", message(1001L, BotUpdate.ChatType.PRIVATE, "/start", true));
        demonstrate(handler, "2. Разрешённый пользователь: /state", message(1001L, BotUpdate.ChatType.PRIVATE, "/state", true));
        demonstrate(handler, "3. Нажатие нижней кнопки", message(1001L, BotUpdate.ChatType.PRIVATE, BotHandler.CHECKIN_BUTTON, false));
        demonstrate(handler, "4. Пользователь вне разрешённого списка", message(2002L, BotUpdate.ChatType.PRIVATE, "/start", true));
        demonstrate(handler, "5. Сообщение в групповом чате", message(1001L, BotUpdate.ChatType.GROUP, "/start", true));
    }

    private static BotUpdate message(long userId, BotUpdate.ChatType chat, String text, boolean command) {
        return new BotUpdate(BotUpdate.Kind.MESSAGE, chat, userId, userId, false, text,
                command ? List.of(new BotUpdate.Entity("bot_command", 0, text.length())) : List.of());
    }

    private static void demonstrate(BotHandler handler, String label, BotUpdate update) {
        System.out.println();
        System.out.println(label);
        System.out.println("Пользователь: " + update.text());
        List<BotAction> actions = handler.handle(update);
        if (actions.isEmpty()) {
            System.out.println("Результат: бот не отвечает — доступ запрещён.");
        }
        for (BotAction action : actions) {
            switch (action) {
                case BotAction.SendMessage message -> {
                    System.out.println("Бот: " + message.text());
                    if (message.keyboard() != null) {
                        System.out.println("Постоянная нижняя клавиатура: ["
                                + String.join("] [", message.keyboard().buttons()) + "]");
                    }
                }
                case BotAction.SetMenuButton menu -> {
                    if (menu.url() == null) {
                        System.out.println("Меню кабинета: кнопка недоступна, адрес ещё не настроен.");
                    } else {
                        System.out.println("Отдельная кнопка меню: [" + menu.label() + "]");
                        System.out.println("Демонстрационный адрес: " + menu.url()
                                + " (пример, не настоящий кабинет)");
                    }
                }
            }
        }
    }
}
