package org.healthtg.seed;

import org.springframework.boot.context.properties.ConfigurationProperties;

import java.time.LocalDate;

/**
 * Configuration of the local demo seed/reset. Nothing here is defaulted to an enabled state: reset and seed refuse
 * to run unless the environment explicitly declares itself a demo environment and names the one database that
 * may be modified.
 */
@ConfigurationProperties("health-tg.seed")
public record SeedProperties(
        boolean demoEnvironment,
        String allowedDatabase,
        Long randomSeed,
        LocalDate startDate,
        Accounts accounts
) {
    public static final long DEFAULT_RANDOM_SEED = 20260916L;
    public static final LocalDate DEFAULT_START_DATE = LocalDate.of(2026, 9, 14);

    public SeedProperties {
        allowedDatabase = allowedDatabase == null ? "" : allowedDatabase.trim();
        randomSeed = randomSeed == null ? DEFAULT_RANDOM_SEED : randomSeed;
        startDate = startDate == null ? DEFAULT_START_DATE : startDate;
        accounts = accounts == null ? new Accounts(null, null, null) : accounts;
    }

    /** Telegram ids of the allowed test accounts; supplied by the environment, never committed. */
    public record Accounts(String regularTelegramId, String irregularTelegramId, String incompleteTelegramId) {
        public Accounts {
            regularTelegramId = blankToEmpty(regularTelegramId);
            irregularTelegramId = blankToEmpty(irregularTelegramId);
            incompleteTelegramId = blankToEmpty(incompleteTelegramId);
        }

        String rawFor(SeedProfile profile) {
            return switch (profile) {
                case REGULAR -> regularTelegramId;
                case IRREGULAR -> irregularTelegramId;
                case INCOMPLETE -> incompleteTelegramId;
            };
        }

        private static String blankToEmpty(String value) {
            return value == null ? "" : value.trim();
        }
    }
}
