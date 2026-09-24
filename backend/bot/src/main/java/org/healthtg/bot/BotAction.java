package org.healthtg.bot;

import java.net.URI;
import java.util.List;

/** Intent for a future Telegram adapter; returning an action does not send anything. */
public sealed interface BotAction permits BotAction.SendMessage, BotAction.SetMenuButton {
    record ReplyKeyboard(List<String> buttons, boolean persistent, boolean resize) {
        public ReplyKeyboard { buttons = List.copyOf(buttons); }
    }

    record SendMessage(long chatId, String text, ReplyKeyboard keyboard) implements BotAction {}

    /** url == null means set a chat-specific commands menu, without inheriting a global Mini App URL. */
    record SetMenuButton(long chatId, String label, URI url) implements BotAction {}
}
