package com.health.analytics;

import org.junit.jupiter.api.Test;
import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.List;
import static com.health.analytics.AnalyticsFunctions.*;
import static org.junit.jupiter.api.Assertions.*;

class DailyStepsDatesTest {
    private static final LocalDate DAY = LocalDate.of(2026, 10, 4);
    private static final Instant EVENING = Instant.parse("2026-10-04T17:00:00Z");
    private static final Instant MORNING = Instant.parse("2026-10-05T06:00:00Z");
    private static Entry steps(String id, Status status, Instant occurred, Instant updated,
                               LocalDate day, int value) {
        return new Entry(id, "metrics", status, occurred, updated, 1L, day, null,
                Metric.STEPS, BigDecimal.valueOf(value), null, null, null, null, null, null);
    }
    private static Period day(LocalDate date, String zone) {
        return new Period(date, date, ZoneId.of(zone));
    }

    @Test void nextMorningReportWinsForYesterdayRegardlessOfInputOrderOrTimezone() {
        Entry first = steps("first", Status.CONFIRMED, EVENING, MORNING.plusSeconds(100), DAY, 8000);
        Entry latest = steps("latest", Status.CONFIRMED, MORNING, MORNING, DAY, 9000);
        for (String zone : List.of("Europe/Vilnius", "Pacific/Kiritimati", "America/Los_Angeles")) {
            var result = dailyMetric(List.of(first, latest), Metric.STEPS, day(DAY, zone));
            assertEquals(BigDecimal.valueOf(9000), result.values().get(DAY));
            assertEquals(1, result.aggregate().daysWithData());
            assertEquals(result, dailyMetric(List.of(latest, first), Metric.STEPS, day(DAY, zone)));
            assertTrue(dailyMetric(List.of(first, latest), Metric.STEPS, day(DAY.plusDays(1), zone)).values().isEmpty());
        }
    }

    @Test void equalMessageTimeUsesUpdatedTimeThenId() {
        var older = steps("z", Status.CONFIRMED, EVENING, EVENING, DAY, 1000);
        var lowerId = steps("a", Status.CONFIRMED, EVENING, MORNING, DAY, 2000);
        var higherId = steps("b", Status.CONFIRMED, EVENING, MORNING, DAY, 3000);
        assertEquals(BigDecimal.valueOf(3000), dailyMetric(List.of(higherId, older, lowerId),
                Metric.STEPS, day(DAY, "UTC")).aggregate().total());
    }

    @Test void unknownDayUnknownTimestampAndInactiveStatusesDoNotReplaceConfirmedTotal() {
        var valid = steps("valid", Status.CONFIRMED, EVENING, EVENING, DAY, 8000);
        var unknownDay = steps("unknown-day", Status.CONFIRMED, MORNING, MORNING, null, 99000);
        var unknownTime = steps("unknown-time", Status.CONFIRMED, null, MORNING, DAY, 99000);
        for (Status inactive : List.of(Status.DRAFT, Status.CANCELLED, Status.DELETED)) {
            var ignored = steps("inactive", inactive, MORNING, MORNING, DAY, 99000);
            assertEquals(BigDecimal.valueOf(8000), dailyMetric(List.of(valid, ignored, unknownDay, unknownTime),
                    Metric.STEPS, day(DAY, "UTC")).aggregate().total());
        }
        assertTrue(dailyMetric(List.of(unknownDay), Metric.STEPS, day(DAY.plusDays(1), "UTC")).values().isEmpty());
    }
}
