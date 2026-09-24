package org.healthtg.auth;

import org.springframework.boot.context.properties.ConfigurationProperties;

import java.time.Duration;
import java.time.ZoneId;
import java.util.Arrays;
import java.util.Set;
import java.util.stream.Collectors;

@ConfigurationProperties("health-tg.auth")
public record AuthProperties(
        String telegramBotToken,
        String allowedTelegramIds,
        Duration initDataTtl,
        Duration sessionTtl,
        ZoneId defaultTimezone
) {
    public AuthProperties {
        telegramBotToken = telegramBotToken == null ? "" : telegramBotToken;
        allowedTelegramIds = allowedTelegramIds == null ? "" : allowedTelegramIds;
        initDataTtl = initDataTtl == null ? Duration.ofMinutes(15) : initDataTtl;
        sessionTtl = sessionTtl == null ? Duration.ofMinutes(60) : sessionTtl;
        defaultTimezone = defaultTimezone == null ? ZoneId.of("Europe/Warsaw") : defaultTimezone;
    }

    public Set<Long> allowedIds() {
        if (allowedTelegramIds.isBlank()) {
            return Set.of();
        }
        return Arrays.stream(allowedTelegramIds.split(","))
                .map(String::trim)
                .filter(value -> !value.isEmpty())
                .map(Long::parseLong)
                .collect(Collectors.toUnmodifiableSet());
    }
}
