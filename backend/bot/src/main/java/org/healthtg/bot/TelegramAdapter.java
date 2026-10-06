package org.healthtg.bot;

import java.util.List;
import java.time.Instant;
import org.telegram.telegrambots.meta.api.methods.AnswerCallbackQuery;
import org.telegram.telegrambots.meta.api.methods.menubutton.SetChatMenuButton;
import org.telegram.telegrambots.meta.api.methods.send.SendMessage;
import org.telegram.telegrambots.meta.api.objects.Update;
import org.telegram.telegrambots.meta.api.objects.menubutton.MenuButtonCommands;
import org.telegram.telegrambots.meta.api.objects.menubutton.MenuButtonWebApp;
import org.telegram.telegrambots.meta.api.objects.webapp.WebAppInfo;
import org.telegram.telegrambots.meta.api.objects.replykeyboard.ReplyKeyboardMarkup;
import org.telegram.telegrambots.meta.api.objects.replykeyboard.InlineKeyboardMarkup;
import org.telegram.telegrambots.meta.api.objects.replykeyboard.buttons.InlineKeyboardButton;
import org.telegram.telegrambots.meta.api.objects.replykeyboard.buttons.InlineKeyboardRow;
import org.telegram.telegrambots.meta.api.objects.replykeyboard.buttons.KeyboardRow;
import org.telegram.telegrambots.meta.generics.TelegramClient;
import org.telegram.telegrambots.meta.exceptions.TelegramApiException;
import org.telegram.telegrambots.meta.exceptions.TelegramApiRequestException;

/** Maps SDK objects at the boundary; all permission decisions stay in BotHandler. */
public final class TelegramAdapter {
    private static final String EXPIRED_CALLBACK =
            "Bad Request: query is too old and response timeout expired or query ID is invalid";
    private final TelegramClient client;
    private final BotHandler handler;

    public TelegramAdapter(TelegramClient client, BotHandler handler) {
        this.client = client;
        this.handler = handler;
    }

    public void accept(Update update) throws TelegramApiException {
        for (BotAction action : handler.handle(project(update))) {
            if (action instanceof BotAction.SendMessage message) {
                KeyboardRow row = new KeyboardRow();
                row.addAll(message.keyboard().buttons().stream()
                        .map(org.telegram.telegrambots.meta.api.objects.replykeyboard.buttons.KeyboardButton::new).toList());
                var keyboard = ReplyKeyboardMarkup.builder().keyboard(List.of(row))
                        .isPersistent(message.keyboard().persistent()).resizeKeyboard(message.keyboard().resize()).build();
                client.execute(SendMessage.builder().chatId(message.chatId()).text(message.text())
                        .replyMarkup(keyboard).build());
            } else if (action instanceof BotAction.SendInlineMessage message) {
                List<InlineKeyboardRow> rows = message.rows().stream().map(row -> new InlineKeyboardRow(
                        row.stream().map(button -> InlineKeyboardButton.builder().text(button.text())
                                .callbackData(button.callbackData()).build()).toList())).toList();
                client.execute(SendMessage.builder().chatId(message.chatId()).text(message.text())
                        .replyMarkup(InlineKeyboardMarkup.builder().keyboard(rows).build()).build());
            } else if (action instanceof BotAction.AnswerCallback answer) {
                try {
                    client.execute(AnswerCallbackQuery.builder().callbackQueryId(answer.callbackId())
                            .text(answer.text()).build());
                } catch (TelegramApiRequestException rejected) {
                    if (!Integer.valueOf(400).equals(rejected.getErrorCode())
                            || !EXPIRED_CALLBACK.equals(rejected.getApiResponse())) {
                        throw rejected;
                    }
                }
            } else if (action instanceof BotAction.SetMenuButton menu) {
                var button = menu.url() == null ? new MenuButtonCommands()
                        : MenuButtonWebApp.builder().text(menu.label())
                        .webAppInfo(new WebAppInfo(menu.url().toString())).build();
                client.execute(SetChatMenuButton.builder().chatId(menu.chatId()).menuButton(button).build());
            }
        }
    }

    public static BotUpdate project(Update update) {
        if (update == null || update.getUpdateId() == null) return null;
        if (update.getCallbackQuery() != null) {
            var callback = update.getCallbackQuery();
            if (callback.getFrom() == null || callback.getFrom().getId() == null
                    || callback.getFrom().getIsBot() == null || callback.getMessage() == null
                    || callback.getMessage().getChatId() == null || callback.getMessage().getChat() == null
                    || !"private".equals(callback.getMessage().getChat().getType())) return null;
            return new BotUpdate(update.getUpdateId(), BotUpdate.Kind.CALLBACK, BotUpdate.ChatType.PRIVATE,
                    callback.getMessage().getChatId(), callback.getFrom().getId(), callback.getFrom().getIsBot(),
                    null, List.of(), callback.getId(), callback.getData());
        }
        if (update.getMessage() == null) return null;
        var message = update.getMessage();
        if (message.getChat() == null || message.getChatId() == null || message.getFrom() == null
                || message.getFrom().getId() == null || message.getFrom().getIsBot() == null) return null;
        BotUpdate.ChatType type;
        if ("private".equals(message.getChat().getType())) type = BotUpdate.ChatType.PRIVATE;
        else return null;
        var entities = message.getEntities() == null ? List.<BotUpdate.Entity>of()
                : message.getEntities().stream().filter(e -> e != null && e.getOffset() != null && e.getLength() != null)
                .map(e -> new BotUpdate.Entity(e.getType(), e.getOffset(), e.getLength())).toList();
        return new BotUpdate(update.getUpdateId(), BotUpdate.Kind.MESSAGE, type, message.getChatId(),
                message.getFrom().getId(), message.getFrom().getIsBot(), message.getText(), entities, null, null,
                message.getDate() == null ? null : Instant.ofEpochSecond(message.getDate()));
    }
}
