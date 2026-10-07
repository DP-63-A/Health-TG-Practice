package org.healthtg.bot;

import java.util.List;
import java.time.Instant;

/** Transport-neutral projection of a Telegram message. Offsets use UTF-16, like Telegram. */
public record BotUpdate(long updateId, Kind kind, ChatType chatType, long chatId, Long senderId,
                        boolean senderIsBot, String text, List<Entity> entities,
                        String callbackId, String callbackData, Instant messageSentAt, Image image, String webAppData) {
    public BotUpdate(long updateId, Kind kind, ChatType chatType, long chatId, Long senderId,
                     boolean senderIsBot, String text, List<Entity> entities, String callbackId,
                     String callbackData, Instant messageSentAt, Image image) {
        this(updateId, kind, chatType, chatId, senderId, senderIsBot, text, entities, callbackId, callbackData, messageSentAt, image, null);
    }
    public BotUpdate {
        entities = entities == null ? List.of() : List.copyOf(entities);
    }

    public record Image(String fileId, Long size, String mediaType, boolean album) {}
    public BotUpdate(long updateId, Kind kind, ChatType chatType, long chatId, Long senderId,
                     boolean senderIsBot, String text, List<Entity> entities,
                     String callbackId, String callbackData, Instant messageSentAt) {
        this(updateId, kind, chatType, chatId, senderId, senderIsBot, text, entities,
                callbackId, callbackData, messageSentAt, null);
    }
    /** Compatibility for events without a message timestamp; never substitutes processing time. */
    public BotUpdate(long updateId, Kind kind, ChatType chatType, long chatId, Long senderId,
                     boolean senderIsBot, String text, List<Entity> entities,
                     String callbackId, String callbackData) {
        this(updateId, kind, chatType, chatId, senderId, senderIsBot, text, entities,
                callbackId, callbackData, null);
    }

    public BotUpdate(Kind kind, ChatType chatType, long chatId, Long senderId,
                     boolean senderIsBot, String text, List<Entity> entities) {
        this(-1, kind, chatType, chatId, senderId, senderIsBot, text, entities, null, null);
    }

    public enum Kind { MESSAGE, EDITED_MESSAGE, CALLBACK, OTHER }
    public enum ChatType { PRIVATE, GROUP, SUPERGROUP, CHANNEL }
    public record Entity(String type, int offset, int length) {}
}
