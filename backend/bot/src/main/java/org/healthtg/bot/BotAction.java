package org.healthtg.bot;

import java.net.URI;
import java.util.List;

/** Intent for a future Telegram adapter; returning an action does not send anything. */
public sealed interface BotAction permits BotAction.SendMessage, BotAction.SendInlineMessage,
        BotAction.AnswerCallback, BotAction.SetMenuButton {
    record ReplyKeyboard(List<String> buttons, boolean persistent, boolean resize) {
        public ReplyKeyboard { buttons = List.copyOf(buttons); }
    }

    record SendMessage(long chatId, String text, ReplyKeyboard keyboard) implements BotAction {}

    record InlineButton(String text, String callbackData, URI webAppUrl) {
        public InlineButton(String text, String callbackData) { this(text, callbackData, null); }
        public InlineButton {
            if ((callbackData == null) == (webAppUrl == null)) throw new IllegalArgumentException("One button target required");
            if (callbackData != null && callbackData.getBytes(java.nio.charset.StandardCharsets.UTF_8).length > 64)
                throw new IllegalArgumentException("Callback exceeds Telegram limit");
            if (webAppUrl != null && (!"https".equalsIgnoreCase(webAppUrl.getScheme())
                    || webAppUrl.getHost() == null || webAppUrl.getUserInfo() != null))
                throw new IllegalArgumentException("HTTPS WebApp URL required");
        }
    }
    record SendInlineMessage(long chatId, String text, List<List<InlineButton>> rows) implements BotAction {
        public SendInlineMessage { rows = rows.stream().map(List::copyOf).toList(); }
    }
    record AnswerCallback(String callbackId, String text) implements BotAction {}

    /** url == null means set a chat-specific commands menu, without inheriting a global Mini App URL. */
    record SetMenuButton(long chatId, String label, URI url) implements BotAction {}
}
