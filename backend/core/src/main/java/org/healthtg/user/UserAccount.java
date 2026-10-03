package org.healthtg.user;

import java.time.ZoneId;
import java.util.UUID;

public record UserAccount(UUID id, long telegramId, ZoneId timezone, boolean standAccess) {
}
