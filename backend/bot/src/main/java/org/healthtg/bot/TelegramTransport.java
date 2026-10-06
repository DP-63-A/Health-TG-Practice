package org.healthtg.bot;

import org.telegram.telegrambots.meta.generics.TelegramClient;

import java.util.Objects;

record TelegramTransport(TelegramClient client, Runnable close) {
    TelegramTransport {
        Objects.requireNonNull(client);
        Objects.requireNonNull(close);
    }
}
