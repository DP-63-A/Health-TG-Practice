package org.healthtg.bot;

/** BE2-05 extension point. Called only after the shared access check, by both entry routes. */
@FunctionalInterface
public interface QuickCheckin {
    String begin(long authorizedUserId);

    static QuickCheckin unavailable() {
        return userId -> "Отметки состояния пока недоступны: сценарий ещё не подключён. Данные не сохранены.";
    }
}
