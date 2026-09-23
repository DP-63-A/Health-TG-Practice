package org.healthtg.bot;

/** BE2-05 extension point. Called only after the shared access check, by both entry routes. */
@FunctionalInterface
public interface QuickCheckin {
    String begin(long authorizedUserId);

    default java.util.List<BotAction> begin(BotUpdate update) {
        return java.util.List.of(new BotAction.SendMessage(update.chatId(), begin(update.senderId()),
                new BotAction.ReplyKeyboard(java.util.List.of(BotHandler.CHECKIN_BUTTON), true, true)));
    }

    default java.util.List<BotAction> callback(BotUpdate update) {
        return java.util.List.of(new BotAction.AnswerCallback(update.callbackId()),
                new BotAction.SendMessage(update.chatId(),
                        "Отметки состояния сейчас недоступны. Прежние тестовые записи после перезапуска недоступны; "
                                + "эта кнопка не сохраняет и не отменяет отметку.",
                        new BotAction.ReplyKeyboard(java.util.List.of(BotHandler.CHECKIN_BUTTON), true, true)));
    }

    static QuickCheckin unavailable() {
        return userId -> "Отметки состояния пока недоступны: сценарий ещё не подключён. Данные не сохранены.";
    }
}
