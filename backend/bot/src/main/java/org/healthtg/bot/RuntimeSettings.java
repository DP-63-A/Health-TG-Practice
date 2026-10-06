package org.healthtg.bot;

import java.net.URI;
import java.util.Arrays;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

/** Secrets are read explicitly; validation never repeats their values. */
public final class RuntimeSettings {
    private final String token;
    private final Set<Long> allowedUserIds;
    private final URI miniAppUrl;

    private RuntimeSettings(String token, Set<Long> allowedUserIds, URI miniAppUrl) {
        this.token = token;
        this.allowedUserIds = Set.copyOf(allowedUserIds);
        this.miniAppUrl = miniAppUrl;
    }

    public static RuntimeSettings from(Map<String, String> environment) {
        String token = environment.getOrDefault("TELEGRAM_BOT_TOKEN", "").trim();
        if (!token.matches("[0-9]+:[A-Za-z0-9_-]+")) {
            throw new IllegalArgumentException("Укажите TELEGRAM_BOT_TOKEN в локальном окружении.");
        }
        Set<Long> ids;
        try {
            ids = Arrays.stream(environment.getOrDefault("TELEGRAM_ALLOWED_USER_IDS", "").split(",", -1))
                    .map(String::trim).map(Long::parseLong).collect(Collectors.toUnmodifiableSet());
            if (ids.isEmpty() || ids.stream().anyMatch(id -> id <= 0)) throw new IllegalArgumentException();
        } catch (IllegalArgumentException e) {
            throw new IllegalArgumentException("TELEGRAM_ALLOWED_USER_IDS должен содержать положительные ID через запятую.");
        }
        URI url = null;
        try {
            String raw = environment.getOrDefault("MINI_APP_URL", "").trim();
            if (!raw.isEmpty()) url = URI.create(raw);
            new BotSettings(ids, "placeholder_bot", url);
        } catch (IllegalArgumentException e) {
            throw new IllegalArgumentException("MINI_APP_URL должен быть HTTPS-адресом без логина и пароля.");
        }
        return new RuntimeSettings(token, ids, url);
    }

    public String token() { return token; }
    public URI miniAppUrl() { return miniAppUrl; }
    public BotSettings forUsername(String username) { return new BotSettings(allowedUserIds, username, miniAppUrl); }
    @Override public String toString() { return "RuntimeSettings[redacted]"; }
}
