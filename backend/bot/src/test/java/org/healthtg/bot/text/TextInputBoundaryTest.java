package org.healthtg.bot.text;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.NullAndEmptySource;
import org.junit.jupiter.params.provider.ValueSource;

import static org.healthtg.bot.text.TextParseResult.Outcome.*;
import static org.junit.jupiter.api.Assertions.*;

class TextInputBoundaryTest {
    private final TextInputParser parser = new TextInputParser();

    @ParameterizedTest @NullAndEmptySource
    @ValueSource(strings = {" ", "\t\r\n", "\u00a0\u2007\u202f"})
    void blankInputsAreRejected(String input) {
        var result = parser.parse(input);
        assertEquals(REJECTED, result.outcome());
        assertEquals(input, result.originalText());
        assertNull(result.data());
    }

    @ParameterizedTest @ValueSource(strings = {"я", "ё"})
    void exactly2000CodePointsAreAcceptedAnd2001Rejected(String codePoint) {
        String atLimit = codePoint.repeat(2000);
        var accepted = parser.parse(atLimit);
        assertEquals(NOTE_SUGGESTED, accepted.outcome());
        assertEquals(atLimit, accepted.originalText());
        assertEquals(atLimit, accepted.data().payload().get("text"));
        var rejected = parser.parse(atLimit + codePoint);
        assertEquals(REJECTED, rejected.outcome());
        assertTrue(rejected.issues().stream().anyMatch(i -> i.code().equals("TOO_LONG")));
    }

    @Test void paddingCountsBeforeTrimming() {
        assertEquals(REJECTED, parser.parse(" ".repeat(2000) + "я").outcome());
    }

    @ParameterizedTest @ValueSource(strings = {"😀", "🍎", "❤️", "👩‍👩‍👧‍👦"})
    void emojiAreRejectedExplicitly(String emoji) {
        var result = parser.parse("24.09.2026 съел яблоко " + emoji);
        assertEquals(REJECTED, result.outcome());
        assertTrue(result.issues().stream().anyMatch(i -> i.code().equals("EMOJI_NOT_SUPPORTED")));
    }

    @Test void longEmojiInputIsRejectedWithoutThrowing() {
        var result = assertDoesNotThrow(() -> parser.parse("24.09.2026 съел яблоко " + "🍎".repeat(1100)));
        assertEquals(REJECTED, result.outcome());
        assertTrue(result.issues().stream().anyMatch(i -> i.code().equals("EMOJI_NOT_SUPPORTED")));
    }

    @ParameterizedTest @ValueSource(strings = {"\ud83d", "\ude00", "а\ud83dб", "\ude00\ud83d"})
    void malformedUtf16CannotLeakIntoPayload(String input) {
        var result = parser.parse(input);
        assertEquals(REJECTED, result.outcome());
        assertEquals(input, result.originalText());
    }

    @Test void sourceTextIsUnmodifiedAndResultCollectionsCannotBeMutated() {
        String input = "  Сегодня я чувствую себя хорошо\n";
        var result = parser.parse(input);
        assertEquals(NOTE_SUGGESTED, result.outcome());
        assertEquals(input, result.originalText());
        assertEquals(input, result.data().payload().get("text"));
        assertThrows(UnsupportedOperationException.class, () -> result.issues().clear());
        assertThrows(UnsupportedOperationException.class, () -> result.data().payload().put("score", 5));
        assertThrows(UnsupportedOperationException.class, () -> result.data().fieldOrigins().put("score", "reported"));
    }

    @Test void hugeNumbersWithinInputLimitNeverThrowOrOverflowToSmallValues() {
        String digits = "9".repeat(1900);
        var result = assertDoesNotThrow(() -> parser.parse("25.09.2026 за день шаги " + digits));
        if (result.data() != null && result.data().payload().containsKey("value")) {
            TextInputParserTest.assertNumber(result, "value", digits);
        }
    }
}
