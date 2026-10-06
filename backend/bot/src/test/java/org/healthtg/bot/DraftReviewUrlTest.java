package org.healthtg.bot;

import org.healthtg.bot.draft.DraftReviewFlow;
import org.healthtg.core.entry.*;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import java.net.URI;
import java.time.*;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;

class DraftReviewUrlTest {
    @ParameterizedTest
    @CsvSource({
        "https://example.test, https://example.test/diary/",
        "https://example.test/, https://example.test/diary/",
        "https://example.test/mini-app, https://example.test/mini-app/diary/",
        "https://example.test/mini-app/, https://example.test/mini-app/diary/",
        "https://example.test?start=a%2Fb&v=x+y#part, https://example.test/diary/",
        "https://example.test/app%2Fone/%D1%91/?next=%2Fdiary%3Fx%3D1#%D1%91, https://example.test/app%2Fone/%D1%91/diary/",
        "https://example.test:8443/nested/app/?x=1&x=2, https://example.test:8443/nested/app/diary/",
        "https://example.test/mini%20app?mode=demo%2Bone#section%20one, https://example.test/mini%20app/diary/"
    })
    void cardAppendsRouteWithoutLosingOrDoubleEncodingConfiguredComponents(String base, String prefix) {
        UUID id = UUID.fromString("22222222-2222-4222-8222-222222222221");
        Instant now = Instant.parse("2026-10-06T12:00:00Z");
        Entry entry = new Entry(id, UUID.randomUUID(), EntryType.METRICS, EntryStatus.DRAFT,
                SourceKind.TEXT, Map.of(), now, now, now, 1,
                Map.of("code", "heart_rate", "value", 72), Map.of(), null, "main:1");
        BotUpdate update = new BotUpdate(BotUpdate.Kind.MESSAGE, BotUpdate.ChatType.PRIVATE,
                1, 1L, false, "", List.of());
        var actions = new DraftReviewFlow(null, null, URI.create(base)).card(update, entry, ZoneOffset.UTC, "");
        var message = (BotAction.SendInlineMessage) actions.getFirst();
        URI result = message.rows().stream().flatMap(List::stream).map(BotAction.InlineButton::webAppUrl)
                .filter(Objects::nonNull).findFirst().orElseThrow();
        URI configured = URI.create(base);
        assertEquals(prefix + id + (configured.getRawQuery() == null ? "" : "?" + configured.getRawQuery())
                + (configured.getRawFragment() == null ? "" : "#" + configured.getRawFragment()), result.toASCIIString());
    }
}
