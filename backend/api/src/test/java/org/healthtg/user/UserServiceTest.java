package org.healthtg.user;

import org.junit.jupiter.api.Test;
import org.springframework.dao.DuplicateKeyException;

import java.time.ZoneId;
import java.util.Optional;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class UserServiceTest {
    private static final long TELEGRAM_ID = 10001;

    @Test
    void readsWinnerAfterConcurrentInsertConflict() {
        UserStore store = mock(UserStore.class);
        UserAccount winner = new UserAccount(UUID.randomUUID(), TELEGRAM_ID,
                ZoneId.of("Europe/Warsaw"), true);
        when(store.findByTelegramId(TELEGRAM_ID)).thenReturn(Optional.empty(), Optional.of(winner));
        when(store.save(any(UserAccount.class))).thenThrow(new DuplicateKeyException("duplicate telegramId"));

        UserAccount result = service(store).findOrCreate(TELEGRAM_ID);

        assertSame(winner, result);
    }

    @Test
    void preservesDuplicateFailureWhenWinnerCannotBeRead() {
        UserStore store = mock(UserStore.class);
        DuplicateKeyException duplicate = new DuplicateKeyException("duplicate telegramId");
        when(store.findByTelegramId(TELEGRAM_ID)).thenReturn(Optional.empty());
        when(store.save(any(UserAccount.class))).thenThrow(duplicate);

        assertSame(duplicate, assertThrows(DuplicateKeyException.class,
                () -> service(store).findOrCreate(TELEGRAM_ID)));
    }

    private static UserService service(UserStore store) {
        return new UserService(store, ZoneId.of("Europe/Warsaw"));
    }
}
