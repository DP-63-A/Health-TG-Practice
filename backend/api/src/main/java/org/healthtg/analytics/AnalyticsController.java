package org.healthtg.analytics;

import org.healthtg.core.entry.OwnerContext;
import org.healthtg.security.CurrentUser;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1/analytics")
@ConditionalOnProperty(name = "health-tg.core.storage.enabled", matchIfMissing = true)
public class AnalyticsController {
    private final AnalyticsService analytics;

    public AnalyticsController(AnalyticsService analytics) {
        this.analytics = analytics;
    }

    @GetMapping
    public AnalyticsResponse get(@AuthenticationPrincipal CurrentUser user,
                                 @RequestParam String period,
                                 @RequestParam(defaultValue = "Europe/Warsaw") String timezone,
                                 @RequestParam(name = "checkin_category", defaultValue = "mood")
                                 String checkinCategory) {
        return analytics.get(new OwnerContext(user.id()), period, timezone, checkinCategory);
    }
}
