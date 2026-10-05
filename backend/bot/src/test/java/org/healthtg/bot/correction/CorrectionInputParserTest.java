package org.healthtg.bot.correction;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.stream.Stream;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import org.junit.jupiter.params.provider.NullAndEmptySource;
import org.junit.jupiter.params.provider.ValueSource;

import static org.healthtg.bot.correction.CorrectionResult.ErrorCode.*;
import static org.junit.jupiter.api.Assertions.*;

class CorrectionInputParserTest {
    private final CorrectionInputParser parser = new CorrectionInputParser();

    @ParameterizedTest
    @MethodSource("numbers")
    void parsesAnEntireNumberWithoutRoundingOrLosingScale(String input, String expected) {
        assertEquals(new BigDecimal(expected), success(parser.parseNumber(input)));
    }

    static Stream<Arguments> numbers() {
        return Stream.of(
                Arguments.of("12", "12"), Arguments.of("+12", "12"),
                Arguments.of("-12", "-12"), Arguments.of("0", "0"),
                Arguments.of("+0", "0"), Arguments.of("-0.00", "0.00"),
                Arguments.of("12.50", "12.50"), Arguments.of("12,50", "12.50"),
                Arguments.of("-0012,500", "-12.500"), Arguments.of("0012", "12"),
                Arguments.of("1,234", "1.234"), Arguments.of("1.234", "1.234"),
                Arguments.of("9007199254740993", "9007199254740993"),
                Arguments.of("12345678901234567890,12345678901234567890", "12345678901234567890.12345678901234567890"),
                Arguments.of("0.000000000000000000000000000001", "0.000000000000000000000000000001"));
    }

    @ParameterizedTest
    @NullAndEmptySource
    @ValueSource(strings = {" ", "\t\r\n", "\u00a0\u2007\u202f", "\u3000\u2003"})
    void missingValuesReturnErrorsForBothTypes(String input) {
        failure(parser.parseNumber(input), EMPTY_INPUT);
        failure(parser.parseDate(input), EMPTY_INPUT);
    }

    @ParameterizedTest
    @ValueSource(strings = {
            "+", "-", ".", ",", ".5", ",5", "12.", "12,", "+.5", "-.5",
            "++12", "--12", "+-12", "-+12", "12-", "12+",
            "1 000", "1\t000", "1\n000", "1\r000", "1\u00a0000", "1\u202f000",
            "1_000", "1'000", "1,234.56", "1.234,56", "1,000,000", "1.000.000",
            "12 кг", "12kg", "кг12", "масса 12", "12 яблок", "12\n13", "12/13",
            "1e3", "1E+3", "1e-3", "0x12", "NaN", "Infinity", "-Infinity", "∞",
            "−12", "–12", "＋12", "１２", "١٢", "1٢", "1٫2", "двенадцать",
            "\u200b12", "12\u200b", "\ufeff12", "12\ufeff", "1\u200b2", "\u000012",
            "12🙂", "ё🙂", "\ud83d", "\ude00"
    })
    void invalidNumbersAreNotPartiallyExtractedOrGuessed(String input) {
        failure(parser.parseNumber(input), INVALID_NUMBER_FORMAT);
    }

    @ParameterizedTest
    @MethodSource("spaces")
    void spacesAreAcceptedOnlyAroundTheEntireValue(String space) {
        assertEquals(new BigDecimal("-12.50"), success(parser.parseNumber(space + "-12,50" + space)));
        assertEquals(LocalDate.of(2024, 2, 29), success(parser.parseDate(space + "29.02.2024" + space)));
        assertEquals(LocalDate.of(2024, 2, 29), success(parser.parseDate(space + "2024-02-29" + space)));
        failure(parser.parseNumber("1" + space + "2"), INVALID_NUMBER_FORMAT);
        failure(parser.parseDate("2024-" + space + "02-29"), INVALID_DATE_FORMAT);
        failure(parser.parseNumber(space), EMPTY_INPUT);
        failure(parser.parseDate(space), EMPTY_INPUT);
    }

    static Stream<String> spaces() {
        // Explicit acceptance examples, independent of the parser's trimming implementation.
        return Stream.of(" ", "\t", "\n", "\r", "\f", "\u000b", "\u001c", "\u001f",
                "\u00a0", "\u1680", "\u2000", "\u2001", "\u2002", "\u2003", "\u2004",
                "\u2005", "\u2006", "\u2007", "\u2008", "\u2009", "\u200a", "\u2028",
                "\u2029", "\u202f", "\u205f", "\u3000");
    }

    @Test
    void syntaxDoesNotInventDomainRangesOrRoundFractionalFields() {
        assertEquals(new BigDecimal("-5"), success(parser.parseNumber("-5")));
        assertEquals(new BigDecimal("6"), success(parser.parseNumber("6")));
        assertEquals(new BigDecimal("1.5"), success(parser.parseNumber("1.5")));
        String large = "9".repeat(2000);
        assertEquals(new BigDecimal(large), success(parser.parseNumber(large)));
        // Whether these values may become a mass, a score or steps belongs to the field contract.
    }

    @ParameterizedTest
    @MethodSource("dates")
    void parsesBothExactDateFormsUsingCalendarRules(String dotted, String iso, LocalDate expected) {
        assertEquals(expected, success(parser.parseDate(dotted)));
        assertEquals(expected, success(parser.parseDate(iso)));
    }

    @Test
    void rejectsAnOtherwiseValidNumberAboveTheInputLimit() {
        failure(parser.parseNumber("9".repeat(2001)), TOO_LONG);
    }

    @ParameterizedTest
    @MethodSource("spaces")
    void inputLimitIncludesOuterSpacesBeforeTrimming(String space) {
        String number = space.repeat(998) + "12.5" + space.repeat(998);
        assertEquals(new BigDecimal("12.5"), success(parser.parseNumber(number)));
        failure(parser.parseNumber(number + space), TOO_LONG);
        failure(parser.parseNumber(space + number), TOO_LONG);

        for (String date : new String[]{"29.02.2024", "2024-02-29"}) {
            String paddedDate = space.repeat(995) + date + space.repeat(995);
            assertEquals(LocalDate.of(2024, 2, 29), success(parser.parseDate(paddedDate)));
            failure(parser.parseDate(paddedDate + space), TOO_LONG);
            failure(parser.parseDate(space + paddedDate), TOO_LONG);
        }
    }

    @ParameterizedTest
    @MethodSource("spaces")
    void excessiveBlankInputIsTooLongRatherThanEmpty(String space) {
        failure(parser.parseNumber(space.repeat(2000)), EMPTY_INPUT);
        failure(parser.parseDate(space.repeat(2000)), EMPTY_INPUT);
        failure(parser.parseNumber(space.repeat(2001)), TOO_LONG);
        failure(parser.parseDate(space.repeat(2001)), TOO_LONG);
    }

    @ParameterizedTest
    @ValueSource(strings = {"🙂", "ё", "9🙂"})
    void countsUnicodeCodePointsAndChecksLengthBeforeSyntax(String unit) {
        String boundary = unit.repeat(2000 / unit.codePointCount(0, unit.length()));
        failure(parser.parseNumber(boundary), INVALID_NUMBER_FORMAT);
        failure(parser.parseDate(boundary), INVALID_DATE_FORMAT);
        failure(parser.parseNumber(boundary + "ё"), TOO_LONG);
        failure(parser.parseDate(boundary + "ё"), TOO_LONG);
        failure(parser.parseNumber(boundary + "🙂"), TOO_LONG);
        failure(parser.parseDate(boundary + "🙂"), TOO_LONG);
    }

    @Test
    void excessiveInputDoesNotLeakItsContentsOrPreventTheNextCorrection() {
        String privateText = "личная заметка ё🙂";
        String input = privateText + "x".repeat(2001);
        var number = failure(parser.parseNumber(input), TOO_LONG);
        var date = failure(parser.parseDate(input), TOO_LONG);
        assertFalse(number.message().contains(privateText));
        assertFalse(date.message().contains(privateText));
        assertFalse(number.toString().contains(privateText));
        assertFalse(date.toString().contains(privateText));
        assertTrue(number.message().contains("2000"));
        assertTrue(date.message().contains("2000"));
        assertEquals(new BigDecimal("-12.50"), success(parser.parseNumber("-12,50")));
        assertEquals(LocalDate.of(2024, 2, 29), success(parser.parseDate("29.02.2024")));
    }

    static Stream<Arguments> dates() {
        return Stream.of(
                Arguments.of("01.01.0001", "0001-01-01", LocalDate.of(1, 1, 1)),
                Arguments.of("31.12.9999", "9999-12-31", LocalDate.of(9999, 12, 31)),
                Arguments.of("05.10.2026", "2026-10-05", LocalDate.of(2026, 10, 5)),
                Arguments.of("10.05.2026", "2026-05-10", LocalDate.of(2026, 5, 10)),
                Arguments.of("29.02.2024", "2024-02-29", LocalDate.of(2024, 2, 29)),
                Arguments.of("29.02.2000", "2000-02-29", LocalDate.of(2000, 2, 29)),
                Arguments.of("29.02.2400", "2400-02-29", LocalDate.of(2400, 2, 29)),
                Arguments.of("29.02.0004", "0004-02-29", LocalDate.of(4, 2, 29)),
                Arguments.of("28.02.1900", "1900-02-28", LocalDate.of(1900, 2, 28)),
                Arguments.of("30.04.2026", "2026-04-30", LocalDate.of(2026, 4, 30)));
    }

    @ParameterizedTest
    @ValueSource(strings = {
            "29.02.2023", "2023-02-29", "29.02.1900", "1900-02-29", "29.02.2100", "2100-02-29",
            "31.02.2024", "2024-02-31", "31.04.2026", "2026-04-31", "31.06.2026", "2026-06-31",
            "31.09.2026", "2026-09-31", "31.11.2026", "2026-11-31",
            "00.10.2026", "2026-10-00", "32.10.2026", "2026-10-32",
            "01.00.2026", "2026-00-01", "01.13.2026", "2026-13-01",
            "01.01.0000", "0000-01-01", "99.99.9999", "9999-99-99"
    })
    void impossibleCalendarDatesAreRejectedInsteadOfNormalised(String input) {
        failure(parser.parseDate(input), INVALID_DATE);
    }

    @ParameterizedTest
    @ValueSource(strings = {
            "5.10.2026", "05.1.2026", "2026-1-05", "2026-10-5", "05.10", "2026-10", "2026",
            "05/10/26", "05/10/2026", "10/05/2026", "05-10-2026", "2026.10.05",
            "05.10.26", "05.10.10000", "10000-10-05", "+2026-10-05", "-0001-10-05",
            "вчера", "сегодня", "5 октября 2026", "05.10.2026 12:00", "2026-10-05T00:00:00Z",
            "дата 05.10.2026", "05.10.2026г", "05.10.2026/06.10.2026", "05.10.2026\n06.10.2026",
            "05 .10.2026", "2026- 10-05", "05.10.２０２６", "٢٠٢٦-10-05",
            "\u200b05.10.2026", "05.10.2026\u200b", "\ufeff2026-10-05", "2026-10-05\ufeff",
            "2026-10-05🙂", "ё🙂", "\ud83d", "\ude00"
    })
    void unsupportedDatesAreNotInferredOrPartiallyExtracted(String input) {
        failure(parser.parseDate(input), INVALID_DATE_FORMAT);
    }

    @Test
    void errorsGiveFormatGuidanceWithoutEchoingRejectedContent() {
        String secretText = "личная заметка ё🙂";
        var number = failure(parser.parseNumber(secretText), INVALID_NUMBER_FORMAT);
        var date = failure(parser.parseDate(secretText), INVALID_DATE_FORMAT);
        assertFalse(number.message().contains(secretText));
        assertFalse(date.message().contains(secretText));
        assertTrue(number.message().contains("12"));
        assertTrue(date.message().contains("ДД.ММ.ГГГГ"));
        assertTrue(date.message().contains("ГГГГ-ММ-ДД"));
    }

    private static Object success(CorrectionResult<?> result) {
        return assertInstanceOf(CorrectionResult.Success.class, result).value();
    }

    private static CorrectionResult.Failure<?> failure(CorrectionResult<?> result,
                                                       CorrectionResult.ErrorCode expectedCode) {
        var failure = assertInstanceOf(CorrectionResult.Failure.class, result);
        assertEquals(expectedCode, failure.code());
        assertFalse(failure.message().isBlank(), "An error must tell the user how to correct their input");
        return failure;
    }
}
