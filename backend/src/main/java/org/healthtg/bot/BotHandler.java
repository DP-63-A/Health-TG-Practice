package org.healthtg.bot;

import java.util.List;
import java.util.Objects;

/** Actual command/access logic, independent of Spring and any Telegram library. */
public final class BotHandler {
    public static final String CHECKIN_BUTTON = "Отметить состояние";
    private static final BotAction.ReplyKeyboard KEYBOARD =
            new BotAction.ReplyKeyboard(List.of(CHECKIN_BUTTON), true, true);
    private final BotSettings settings;
    private final QuickCheckin checkin;

    public BotHandler(BotSettings settings, QuickCheckin checkin) {
        this.settings = Objects.requireNonNull(settings);
        this.checkin = Objects.requireNonNull(checkin);
    }

    public List<BotAction> handle(BotUpdate update) {
        if (update == null || (update.kind() != BotUpdate.Kind.MESSAGE && update.kind() != BotUpdate.Kind.CALLBACK)
                || update.chatType() != BotUpdate.ChatType.PRIVATE || update.senderIsBot()
                || update.senderId() == null || update.chatId() != update.senderId()
                || !settings.allowedUserIds().contains(update.senderId())) {
            return List.of(); // Deny before dispatch, including future checkin calls.
        }
        if (update.kind() == BotUpdate.Kind.CALLBACK) return checkin.callback(update);
        String text = update.text();
        if (text == null) return List.of();
        String command = command(update);
        if ("/start".equals(command)) {
            String welcome = "Это учебный демонстрационный дневник самочувствия. "
                    + "Для демонстрации используются синтетические данные. "
                    + "Это не медицинский сервис. Не отправляйте реальные сведения о здоровье. "
                    + "Нажмите «Отметить состояние» или отправьте /state.";
            if (settings.miniAppUrl() == null) welcome += " Кабинет пока не подключён.";
            return List.of(new BotAction.SetMenuButton(update.chatId(), "Открыть кабинет", settings.miniAppUrl()),
                    new BotAction.SendMessage(update.chatId(), welcome, KEYBOARD));
        }
        if ("/state".equals(command) || CHECKIN_BUTTON.equals(text)) {
            return checkin.begin(update);
        }
        return List.of(); // Other commands/media belong to later issues.
    }

    private String command(BotUpdate update) {
        for (BotUpdate.Entity entity : update.entities()) {
            if (!"bot_command".equals(entity.type()) || entity.offset() != 0
                    || entity.length() <= 0 || entity.length() > update.text().length()) continue;
            int end = entity.length();
            if (end < update.text().length() && !Character.isWhitespace(update.text().charAt(end))) continue;
            String token = update.text().substring(0, end);
            int at = token.indexOf('@');
            if (at >= 0) {
                if (!token.substring(at + 1).equalsIgnoreCase(settings.botUsername())) return "";
                token = token.substring(0, at);
            }
            return token;
        }
        return "";
    }
}

