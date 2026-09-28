package org.healthtg.bot.text;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.ValueSource;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalTime;

import static org.healthtg.bot.text.TextParseResult.Outcome.*;
import static org.junit.jupiter.api.Assertions.*;

/** Acceptance examples from #63 and the project contracts, through the public API only. */
class TextInputParserTest {
    private final TextInputParser parser = new TextInputParser();

    @ParameterizedTest
    @ValueSource(strings = {
            "Я спал 7 часов", "Я спала семь часов", "Поспал 7 ч", "Поспала семь часов",
            "Продолжительность моего сна составила 7 часов",
            "Суммарная продолжительность моего сна составила семь часов",
            "  Я\tСПАЛА\u00a0СЕМЬ\u2007ЧАСОВ  "
    })
    void sleepStylesAndGendersGiveSameKnownDurationWithoutInventedDate(String input) {
        var result = parser.parse(input);
        assertEquals(NEEDS_CLARIFICATION, result.outcome(), result.toString());
        assertMetric(result, "sleep_duration_min", "420", "min");
        assertEquals("computed", result.data().fieldOrigins().get("value"));
        assertNull(result.data().date());
        assertNull(result.data().time());
        assertFalse(result.data().payload().containsKey("local_date"));
        assertFalse(result.data().payload().containsKey("local_time"));
        assertFalse(result.data().fieldOrigins().containsKey("local_date"));
        assertIssueField(result, "local_date");
        assertIssueField(result, "day_scope");
        assertEquals(input, result.originalText());
    }

    @ParameterizedTest
    @ValueSource(strings = {
            "24.09.2026 за день прошёл 8000 шагов", "24.09.2026 за день прошла 8000 шагов",
            "24.09.2026 за день нашагал восемь тысяч", "24.09.2026 за день нашагала восемь тысяч",
            "24.09.2026 суточное количество шагов составило восемь тысяч",
            "2026-09-24 за сутки шаги: 8000"
    })
    void dailyStepsAreEquivalentAcrossStylesAndGenders(String input) {
        var result = parser.parse(input);
        assertEquals(PARSED, result.outcome(), result.toString());
        assertMetric(result, "steps", "8000", "count");
        assertEquals(LocalDate.of(2026, 9, 24), result.data().date());
        assertEquals("2026-09-24", result.data().payload().get("local_date"));
        assertNull(result.data().time());
        assertFalse(result.data().payload().containsKey("local_time"));
        assertEquals("reported", result.data().fieldOrigins().get("value"));
        assertEquals("text", result.data().sourceKind());
        assertTrue(result.issues().isEmpty());
    }

    @ParameterizedTest
    @CsvSource(delimiter = '|', value = {
            "25.09.2026 сон за день: 7 ч 10 мин|430|computed",
            "25.09.2026 сон за день: 1,5 часа|90|computed",
            "25.09.2026 сон за день: 1.5 часа|90|computed",
            "25.09.2026 сон за день: 90 минут|90|reported",
            "25.09.2026 сон за день: ноль минут|0|reported",
            "25.09.2026 сон за день: 7 часов и 10 минут|430|computed"
    })
    void explicitlyStatedDailySleepConvertsExactly(String input, String minutes, String origin) {
        var result = parser.parse(input);
        assertEquals(PARSED, result.outcome(), result.toString());
        assertMetric(result, "sleep_duration_min", minutes, "min");
        assertEquals(origin, result.data().fieldOrigins().get("value"));
    }

    @Test void wakingDateAndTimeStayExplicitWithoutCreatingAnInstant() {
        var result = parser.parse("25.09.2026 спала 7 часов 10 минут, проснулась в 07:30");
        assertEquals(PARSED, result.outcome(), result.toString());
        assertMetric(result, "sleep_duration_min", "430", "min");
        assertEquals(LocalDate.of(2026, 9, 25), result.data().date());
        assertEquals(LocalTime.of(7, 30), result.data().time());
        assertFalse(result.data().payload().containsKey("occurred_at"));
        assertFalse(result.data().payload().containsKey("revision"));
        assertFalse(result.data().payload().containsKey("id"));
    }

    @ParameterizedTest
    @ValueSource(strings = {"Пульс", "Сердцебиение", "Частота сердечных сокращений"})
    void restingHeartRateHasOnlyStatedQualifier(String label) {
        var result = parser.parse("25.09.2026 в 10:15 " + label + " в покое 68 уд/мин");
        assertEquals(PARSED, result.outcome(), result.toString());
        assertMetric(result, "heart_rate", "68", "bpm");
        assertEquals("resting", result.data().payload().get("qualifier"));
        assertEquals("reported", result.data().fieldOrigins().get("qualifier"));
        assertEquals(LocalTime.of(10, 15), result.data().time());
    }

    @Test void barePulseDoesNotInventUnitQualifierOrDate() {
        var result = parser.parse("Пульс 70");
        assertEquals(NEEDS_CLARIFICATION, result.outcome());
        assertNumber(result, "value", "70");
        assertEquals("heart_rate", result.data().payload().get("code"));
        assertFalse(result.data().payload().containsKey("unit"));
        assertFalse(result.data().payload().containsKey("qualifier"));
        assertNull(result.data().date());
        assertIssueField(result, "unit");
        assertIssueField(result, "local_date");
    }

    @ParameterizedTest
    @ValueSource(strings = {
            "25.09.2026 в 13:30 съел пасту, 200 г", "25.09.2026 в 13:30 съела пасту, 200 г",
            "25.09.2026 в 13:30 поел пасту, 200 граммов", "25.09.2026 в 13:30 поела пасту, 200 граммов",
            "25.09.2026 в 13:30 на обед была паста, 200 г"
    })
    void mealVariantsKeepDescriptionAndStatedMassWithoutInventedNutrition(String input) {
        var result = parser.parse(input);
        assertEquals(PARSED, result.outcome(), result.toString());
        assertEquals("meal", result.data().type());
        assertTrue(result.data().payload().get("description").toString().contains("паст"));
        assertNumber(result, "mass_g", "200");
        assertEquals("reported", result.data().fieldOrigins().get("mass_g"));
        assertFalse(result.data().payload().containsKey("nutrients"));
        assertFalse(result.data().payload().containsKey("nutrients_basis"));
        assertEquals(LocalTime.of(13, 30), result.data().time());
    }

    @Test void missingOptionalMealMassIsNotAnErrorOrInventedZero() {
        var result = parser.parse("25.09.2026 еда: паста");
        assertEquals(PARSED, result.outcome(), result.toString());
        assertEquals("паста", result.data().payload().get("description"));
        assertFalse(result.data().payload().containsKey("mass_g"));
        assertFalse(result.data().payload().containsKey("nutrients"));
        assertTrue(result.issues().isEmpty());
    }

    @Test void kilogramsAreConvertedAndMarkedComputed() {
        var result = parser.parse("25.09.2026 съела 0,125 кг пасты");
        assertEquals(PARSED, result.outcome(), result.toString());
        assertNumber(result, "mass_g", "125");
        assertEquals("computed", result.data().fieldOrigins().get("mass_g"));
    }

    @ParameterizedTest @ValueSource(strings = {"0", "ноль", "+0", "плюс ноль"})
    void explicitZeroIsKeptAsZero(String zero) {
        var result = parser.parse("25.09.2026 за день прошла " + zero + " шагов");
        assertEquals(PARSED, result.outcome(), result.toString());
        assertNumber(result, "value", "0");
    }

    @Test void repeatedInvocationsDoNotRetainPreviousData() {
        var input = "Пульс 70";
        var before = parser.parse(input);
        parser.parse("25.09.2026 за день прошла 8000 шагов");
        assertEquals(before, parser.parse(input));
    }

    static void assertMetric(TextParseResult result, String code, String value, String unit) {
        assertNotNull(result.data(), result.toString());
        assertEquals("metrics", result.data().type());
        assertEquals(code, result.data().payload().get("code"));
        assertNumber(result, "value", value);
        assertEquals(unit, result.data().payload().get("unit"));
    }

    static void assertNumber(TextParseResult result, String field, String expected) {
        assertNotNull(result.data(), result.toString());
        assertNotNull(result.data().payload().get(field), result.toString());
        assertEquals(0, new BigDecimal(expected).compareTo(new BigDecimal(result.data().payload().get(field).toString())), result.toString());
    }

    static void assertIssueField(TextParseResult result, String field) {
        assertTrue(result.issues().stream().anyMatch(issue -> issue.field().equals(field)), result.toString());
    }
}
