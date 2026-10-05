package org.healthtg.seed;

import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import java.util.UUID;

public record SeedReport(long randomSeed, LocalDate startDate, List<ProfileReport> profiles) {
    public record ProfileReport(SeedProfile profile, UUID userId, int days, int logicalRecords,
                                Map<String, Integer> confirmedByType, int changedRecords, int redeliveredRecords,
                                int cancelledDrafts, List<LocalDate> emptyDays) {
    }
}
