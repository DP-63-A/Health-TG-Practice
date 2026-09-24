package org.healthtg.bot;

import java.util.HashMap;
import java.util.Map;
import java.util.Set;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import static org.junit.jupiter.api.Assertions.*;

class RuntimeSettingsTest {
    static final String SYNTHETIC_TOKEN = "123456:synthetic_TEST_only";
    static Map<String, String> environment() {
        return new HashMap<>(Map.of("TELEGRAM_BOT_TOKEN", SYNTHETIC_TOKEN,
                "TELEGRAM_ALLOWED_USER_IDS", "1001"));
    }

    @Test void readsTrimmedIdsAndOptionalUrlWithoutExposingConfiguration() {
        var env = environment();
        env.put("TELEGRAM_ALLOWED_USER_IDS", " 1001, 2002,1001 ");
        env.put("MINI_APP_URL", " https://example.com/app ");
        var settings = RuntimeSettings.from(env);
        assertEquals(Set.of(1001L, 2002L), settings.forUsername("test_bot").allowedUserIds());
        assertEquals("https://example.com/app", settings.forUsername("test_bot").miniAppUrl().toString());
        assertEquals(SYNTHETIC_TOKEN, settings.token());
        assertFalse(settings.toString().contains(SYNTHETIC_TOKEN));
        assertFalse(settings.toString().contains("1001"));
        assertNull(RuntimeSettings.from(environment()).forUsername("test_bot").miniAppUrl());
    }

    @ParameterizedTest @ValueSource(strings = {"", " ", "1001,", ",1001", "1001,,2002", "0", "-1", "abc", "9223372036854775808"})
    void invalidAllowlistStopsConfiguration(String value) {
        var env = environment(); env.put("TELEGRAM_ALLOWED_USER_IDS", value);
        var error = assertThrows(IllegalArgumentException.class, () -> RuntimeSettings.from(env));
        assertTrue(error.getMessage().contains("TELEGRAM_ALLOWED_USER_IDS"));
        assertNull(error.getCause());
        assertFalse(error.toString().contains(SYNTHETIC_TOKEN));
    }

    @ParameterizedTest @ValueSource(strings = {"", "bad-secret", "123:secret/path", "token with spaces"})
    void invalidTokenDoesNotAppearInError(String value) {
        var env = environment(); env.put("TELEGRAM_BOT_TOKEN", value);
        var error = assertThrows(IllegalArgumentException.class, () -> RuntimeSettings.from(env));
        assertTrue(error.getMessage().contains("TELEGRAM_BOT_TOKEN"));
        if (!value.isEmpty()) assertFalse(error.toString().contains(value));
        assertNull(error.getCause());
    }

    @ParameterizedTest @ValueSource(strings = {"http://example.com", "/app", "https://user:secret@example.com", "https://bad host/app"})
    void invalidMiniAppUrlIsRejectedWithoutRepeatingIt(String value) {
        var env = environment(); env.put("MINI_APP_URL", value);
        var error = assertThrows(IllegalArgumentException.class, () -> RuntimeSettings.from(env));
        assertTrue(error.getMessage().contains("MINI_APP_URL"));
        assertFalse(error.toString().contains(value)); assertNull(error.getCause());
    }
}
