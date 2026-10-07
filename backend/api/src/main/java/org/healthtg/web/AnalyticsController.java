package org.healthtg.web;

import jakarta.validation.constraints.NotBlank;
import org.healthtg.analytics.AnalyticsService;
import org.healthtg.core.entry.OwnerContext;
import org.healthtg.security.CurrentUser;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1/analytics")
@Validated
@ConditionalOnProperty(name = "health-tg.core.storage.enabled", matchIfMissing = true)
public class AnalyticsController {
    private final AnalyticsService analytics;

    public AnalyticsController(AnalyticsService analytics) {
        this.analytics = analytics;
    }

    @GetMapping
    public AnalyticsService.AnalyticsResponse get(@AuthenticationPrincipal CurrentUser user,
                                                   @RequestParam @NotBlank String period,
                                                   @RequestParam(required = false) String timezone,
                                                   @RequestParam(name = "checkin_category", required = false)
                                                   String checkinCategory) {
        return analytics.calculate(new OwnerContext(user.id()), period, timezone, checkinCategory);
    }
}
