package org.healthtg.bot;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import java.net.URI;
import java.util.HashSet;
import java.util.Set;
import static org.junit.jupiter.api.Assertions.*;

class BotSettingsTest {
    @ParameterizedTest @ValueSource(strings = {"http://example.com", "/mini-app", "https:/mini-app", "ftp://example.com", "https://user:password@example.com"})
    void rejectsNonHttpsRelativeAndCredentialUrls(String url) {
        assertThrows(IllegalArgumentException.class, () -> new BotSettings(Set.of(1001L), "demo_bot", URI.create(url)));
    }
    @ParameterizedTest @ValueSource(strings = {"https://example.com", "https://example.com/mini-app?mode=demo", "HTTPS://example.com/app"})
    void acceptsAbsoluteHttps(String url) {
        assertEquals(URI.create(url), new BotSettings(Set.of(1001L), "demo_bot", URI.create(url)).miniAppUrl());
    }
    @ParameterizedTest @ValueSource(longs = {0, -1, Long.MIN_VALUE})
    void rejectsInvalidAllowlistIdentity(long id) {
        assertThrows(IllegalArgumentException.class, () -> new BotSettings(Set.of(id), "demo_bot", null));
    }
    @Test void allowlistCannotBeMutatedAfterConstruction() {
        var ids = new HashSet<>(Set.of(1001L));
        var settings = new BotSettings(ids, "demo_bot", null);
        ids.add(2002L);
        assertEquals(Set.of(1001L), settings.allowedUserIds());
        assertThrows(UnsupportedOperationException.class, () -> settings.allowedUserIds().add(2002L));
    }
    @Test void settingsDiagnosticDoesNotExposeIdentityOrUrlCredentials() {
        var settings = new BotSettings(Set.of(1001L), "demo_bot", URI.create("https://example.com/?private=value"));
        assertFalse(settings.toString().contains("1001"));
        assertFalse(settings.toString().contains("demo_bot"));
        assertFalse(settings.toString().contains("private=value"));
    }
}
