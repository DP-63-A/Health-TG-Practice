package org.healthtg.bot.text;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import static org.healthtg.bot.text.TextParseResult.Outcome.*;
import static org.junit.jupiter.api.Assertions.*;

class TextInputSafetyTest {
    private final TextInputParser parser = new TextInputParser();

    @ParameterizedTest @ValueSource(strings = {
            "Хочу спать 8 часов", "Планирую пройти 8000 шагов", "Прошла бы 8000 шагов",
            "Если я спала 7 часов", "Дочь спала 8 часов", "Сын спал 8 часов",
            "У мамы пульс 70 уд/мин", "Не считала шаги", "Спала нормально",
            "После завтрака чувствую себя хорошо", "Сколько надо спать часов?"
    })
    void nonMeasurementsSuggestOriginalNoteWithoutNumericHealthFacts(String input) {
        var result = parser.parse(input);
        assertEquals(NOTE_SUGGESTED, result.outcome(), result.toString());
        assertEquals("note", result.data().type());
        assertEquals(input, result.data().payload().get("text"));
        assertEquals(input, result.originalText());
        assertFalse(result.data().payload().containsKey("value"));
        assertFalse(result.data().payload().containsKey("score"));
        assertFalse(result.issues().isEmpty());
    }

    @ParameterizedTest @ValueSource(strings = {
            "Спала 6 или 7 часов", "Спала не 6, а 7 часов", "Сон: примерно 7 часов",
            "Спала 7 часов и прошла 8000 шагов", "В 09:00 завтрак, в 13:00 обед",
            "25.09.2026 за день шаги 6000 8000", "25.09.2026 сон за день 6 часов 7 часов",
            "25.09.2026 пульс 70 уд/мин, 80 уд/мин", "25.09.2026 съела пасту 100 г и суп 200 г",
            "25.09.2026 за день 7тысяч шагов", "25.09.2026 за день 7 тысяч шагов",
            "25.09.2026 за день один два шага", "25.09.2026 за день сто сто шагов",
            "25.09.2026 за день шаги 1e3", "25.09.2026 за день шаги 1/2",
            "25.09.2026 за день шаги 1.234.567", "25.09.2026 съела пасту 100 ккал"
    })
    void ambiguityDoesNotPickFirstOrPartialNumber(String input) {
        var result = parser.parse(input);
        assertEquals(NEEDS_CLARIFICATION, result.outcome(), result.toString());
        assertNoKnownMeasurement(result);
        assertEquals(input, result.originalText());
        assertFalse(result.issues().isEmpty());
    }

    @ParameterizedTest @ValueSource(strings = {
            "За день прошла -100 шагов", "За день прошла −100 шагов",
            "За день прошла минус сто шагов", "Сон за день: -7 часов",
            "Сон за день: минус семь часов", "Съела пасту -100 г"
    })
    void explicitNegativeStepsSleepAndMassAreRejected(String input) {
        var result = parser.parse(input);
        assertEquals(REJECTED, result.outcome(), result.toString());
        assertNoKnownMeasurement(result);
    }

    @ParameterizedTest @ValueSource(strings = {
            "25.09.2026 за день прошла - 100 шагов",
            "25.09.2026 за день прошла минус -100 шагов",
            "25.09.2026 за день прошла минус −100 шагов",
            "25.09.2026 сон за день: - 7 часов",
            "25.09.2026 съела пасту минус -100 г",
            "25.09.2026 съела пасту - 100 г"
    })
    void unsupportedSignsNeverTurnNegativeOrAmbiguousInputIntoPositiveKnownValue(String input) {
        var result = parser.parse(input);
        assertNotEquals(PARSED, result.outcome(), result.toString());
        assertNoKnownMeasurement(result);
    }

    @ParameterizedTest @ValueSource(strings = {
            "25.09.2026 за день прошла –100 шагов",
            "25.09.2026 за день прошла — сто шагов",
            "25.09.2026 сон за день: – семь часов",
            "25.09.2026 съела пасту —100 г"
    })
    void enAndEmDashBeforeNumbersAreNotSilentlyDiscarded(String input) {
        var result = parser.parse(input);
        assertNotEquals(PARSED, result.outcome(), result.toString());
        assertNoKnownMeasurement(result);
    }

    @ParameterizedTest @ValueSource(strings = {
            "25.09.2026 за день прошла 8 тысяч шагов",
            "25.09.2026 сон за день 7 с половиной часов"
    })
    void unsupportedNumericNotationIsNotSilentlyTruncated(String input) {
        var result = parser.parse(input);
        assertNotEquals(PARSED, result.outcome(), result.toString());
        assertNoKnownMeasurement(result);
    }

    @ParameterizedTest @ValueSource(strings = {
            "25.09.2026 пульс 70 кг", "25.09.2026 пульс 70 шагов",
            "25.09.2026 за день шаги 8000 bpm", "25.09.2026 за день прошла 8000 кг",
            "25.09.2026 сон за день: 7 кг", "25.09.2026 съела пасту 200 уд/мин"
    })
    void numbersWithForeignUnitsAreNotReliableValuesForTheDetectedMetric(String input) {
        var result = parser.parse(input);
        assertNotEquals(PARSED, result.outcome(), result.toString());
        assertNoKnownMeasurement(result);
    }

    @ParameterizedTest @ValueSource(strings = {
            "25.09.2026: «спала 7 часов»", "25.09.2026 еда: \"съела пасту\"",
            "25.09.2026 сестрёнка съела пасту 200 г",
            "25.09.2026 коллега съела пасту 200 г",
            "25.09.2026 я собиралась съесть пасту, 200 г",
            "25.09.2026 на обед будет паста 200 г"
    })
    void quotationsOtherPeopleAndFuturePlansAreNotCertainSelfReportedEvents(String input) {
        var result = parser.parse(input);
        assertNotEquals(PARSED, result.outcome(), result.toString());
    }

    @ParameterizedTest @ValueSource(strings = {
            "25.09.2026 на завтрак съела пасту и на обед суп",
            "25.09.2026 съела пасту, затем суп",
            "25.09.2026 съела пасту; 26.09.2026 съела суп"
    })
    void multipleMealsAreNotCollapsedIntoOneMeal(String input) {
        var result = parser.parse(input);
        assertEquals(NEEDS_CLARIFICATION, result.outcome(), result.toString());
        assertNoKnownMeasurement(result);
    }

    @ParameterizedTest @ValueSource(strings = {
            "31.02.2026 пульс 68 уд/мин", "29.02.2025 пульс 68 уд/мин",
            "2026-13-01 пульс 68 уд/мин", "00.01.2026 пульс 68 уд/мин",
            "25.09.2026 в 24:00 пульс 68 уд/мин", "25.09.2026 в 12:60 пульс 68 уд/мин"
    })
    void invalidExplicitDatesAndTimesAreRejectedRatherThanAdjusted(String input) {
        var result = parser.parse(input);
        assertEquals(REJECTED, result.outcome(), result.toString());
        assertNoKnownMeasurement(result);
    }

    @Test void validLeapDayIsRetained() {
        var result = parser.parse("29.02.2024 пульс 68 уд/мин");
        assertEquals(PARSED, result.outcome(), result.toString());
        assertEquals("2024-02-29", result.data().payload().get("local_date"));
    }

    @ParameterizedTest @ValueSource(strings = {"сегодня", "вчера", "позавчера", "в понедельник"})
    void relativeDatesNeverUseTheMachineClock(String relative) {
        var result = parser.parse(relative + " за день прошла 8000 шагов");
        assertEquals(NEEDS_CLARIFICATION, result.outcome(), result.toString());
        assertNotNull(result.data());
        TextInputParserTest.assertNumber(result, "value", "8000");
        assertNull(result.data().date());
        assertFalse(result.data().payload().containsKey("local_date"));
        TextInputParserTest.assertIssueField(result, "date");
    }

    @Test void conflictingExplicitAndRelativeDateDoesNotSilentlyChooseOne() {
        var result = parser.parse("25.09.2026 вчера за день прошла 8000 шагов");
        assertEquals(NEEDS_CLARIFICATION, result.outcome(), result.toString());
        TextInputParserTest.assertIssueField(result, "date");
    }

    @Test void timeWithoutDateDoesNotInventDate() {
        var result = parser.parse("В 10:15 пульс 68 уд/мин");
        assertEquals(NEEDS_CLARIFICATION, result.outcome(), result.toString());
        assertEquals("10:15", result.data().payload().get("local_time"));
        assertNull(result.data().date());
    }

    private static void assertNoKnownMeasurement(TextParseResult result) {
        if (result.data() != null) {
            assertFalse(result.data().payload().containsKey("value"), result.toString());
            assertFalse(result.data().payload().containsKey("mass_g"), result.toString());
        }
    }
}
