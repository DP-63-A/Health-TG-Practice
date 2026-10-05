package org.healthtg.bot.correction;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeParseException;
import java.time.format.ResolverStyle;
import java.util.Locale;
import java.util.regex.Pattern;

import static org.healthtg.bot.correction.CorrectionResult.ErrorCode.*;

/** Stateless syntax validation. Field-specific ranges and persistence belong to the caller. */
public final class CorrectionInputParser {
    private static final int MAX_CODE_POINTS = 2000;
    private static final Pattern NUMBER = Pattern.compile("[+-]?[0-9]+(?:[.,][0-9]+)?");
    private static final Pattern DOTTED_DATE = Pattern.compile("[0-9]{2}\\.[0-9]{2}\\.[0-9]{4}");
    private static final Pattern ISO_DATE = Pattern.compile("[0-9]{4}-[0-9]{2}-[0-9]{2}");
    private static final DateTimeFormatter DOTTED = formatter("dd.MM.uuuu");
    private static final DateTimeFormatter ISO = formatter("uuuu-MM-dd");

    public CorrectionResult<BigDecimal> parseNumber(String input) {
        if (isTooLong(input)) {
            return new CorrectionResult.Failure<>(TOO_LONG, "Введите не более 2000 символов, включая пробелы.");
        }
        String value = trimOuterSpaces(input);
        if (value.isEmpty()) {
            return new CorrectionResult.Failure<>(EMPTY_INPUT, "Введите число, например 12 или 12,5.");
        }
        if (!NUMBER.matcher(value).matches()) {
            return new CorrectionResult.Failure<>(INVALID_NUMBER_FORMAT,
                    "Введите одно число, например 12 или 12,5: без единиц, внутренних пробелов и разделителей тысяч.");
        }
        return new CorrectionResult.Success<>(new BigDecimal(value.replace(',', '.')));
    }

    public CorrectionResult<LocalDate> parseDate(String input) {
        if (isTooLong(input)) {
            return new CorrectionResult.Failure<>(TOO_LONG, "Введите не более 2000 символов, включая пробелы.");
        }
        String value = trimOuterSpaces(input);
        if (value.isEmpty()) {
            return new CorrectionResult.Failure<>(EMPTY_INPUT, "Введите дату в формате ДД.ММ.ГГГГ или ГГГГ-ММ-ДД.");
        }
        DateTimeFormatter format;
        if (DOTTED_DATE.matcher(value).matches()) {
            format = DOTTED;
        } else if (ISO_DATE.matcher(value).matches()) {
            format = ISO;
        } else {
            return new CorrectionResult.Failure<>(INVALID_DATE_FORMAT,
                    "Введите полную дату в формате ДД.ММ.ГГГГ или ГГГГ-ММ-ДД, например 05.10.2026.");
        }
        try {
            LocalDate date = LocalDate.parse(value, format);
            if (date.getYear() >= 1) {
                return new CorrectionResult.Success<>(date);
            }
        } catch (DateTimeParseException ignored) {
            // A syntactically complete date must also exist in the calendar.
        }
        return new CorrectionResult.Failure<>(INVALID_DATE,
                "Такой даты нет. Проверьте день, месяц и год от 0001 до 9999, включая високосный год.");
    }

    private static boolean isTooLong(String input) {
        return input != null && input.codePointCount(0, input.length()) > MAX_CODE_POINTS;
    }

    private static DateTimeFormatter formatter(String pattern) {
        return DateTimeFormatter.ofPattern(pattern, Locale.ROOT).withResolverStyle(ResolverStyle.STRICT);
    }

    /** Removes Java whitespace and Unicode space characters only at the boundaries. */
    private static String trimOuterSpaces(String input) {
        if (input == null) return "";
        int start = 0;
        int end = input.length();
        while (start < end && isSpace(input.codePointAt(start))) {
            start += Character.charCount(input.codePointAt(start));
        }
        while (end > start && isSpace(input.codePointBefore(end))) {
            end -= Character.charCount(input.codePointBefore(end));
        }
        return input.substring(start, end);
    }

    private static boolean isSpace(int codePoint) {
        return Character.isWhitespace(codePoint) || Character.isSpaceChar(codePoint);
    }
}
