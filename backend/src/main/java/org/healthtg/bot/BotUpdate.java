package org.healthtg.bot;

import java.util.List;

/** Transport-neutral projection of a Telegram message. Offsets use UTF-16, like Telegram. */
public record BotUpdate(Kind kind, ChatType chatType, long chatId, Long senderId,
                        boolean senderIsBot, String text, List<Entity> entities,
                        Integer updateId, String callbackId, String callbackData) {
    public BotUpdate(Kind kind, ChatType chatType, long chatId, Long senderId,
                     boolean senderIsBot, String text, List<Entity> entities) {
        this(kind, chatType, chatId, senderId, senderIsBot, text, entities, null, null, null);
    }
    public BotUpdate {
        entities = entities == null ? List.of() : List.copyOf(entities);
    }

    public enum Kind { MESSAGE, EDITED_MESSAGE, CALLBACK, OTHER }
    public enum ChatType { PRIVATE, GROUP, SUPERGROUP, CHANNEL }
    public record Entity(String type, int offset, int length) {}
}
