package org.healthtg.bot.text;

import java.time.DateTimeException;
import java.time.LocalDate;
import java.time.LocalTime;
import java.time.format.DateTimeFormatter;
import java.time.format.ResolverStyle;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Pattern;

/** Extracts only explicitly stated local calendar values, without a clock or guessed zone. */
final class ExplicitTime {
    private static final Pattern DATE = Pattern.compile("(?<![0-9])(?:[0-9]{1,2}\\.[0-9]{1,2}\\.[0-9]{4}|[0-9]{4}-[0-9]{1,2}-[0-9]{1,2})(?![0-9])");
    private static final Pattern TIME = Pattern.compile("(?<![0-9])[0-9]{1,2}:[0-9]{2}(?::[0-9]{2})?(?![0-9])");
    private static final DateTimeFormatter DOTTED = DateTimeFormatter.ofPattern("dd.MM.uuuu").withResolverStyle(ResolverStyle.STRICT);
    private static final DateTimeFormatter ISO = DateTimeFormatter.ofPattern("uuuu-MM-dd").withResolverStyle(ResolverStyle.STRICT);
    private static final Pattern RELATIVE = Pattern.compile("\\b(?:сегодня|вчера|позавчера|завтра|утром|вечером|ночью|понедельник\\p{L}*|вторник\\p{L}*|среду|среда|четверг\\p{L}*|пятниц\\p{L}*|суббот\\p{L}*|воскресень\\p{L}*|январ\\p{L}*|феврал\\p{L}*|март\\p{L}*|апрел\\p{L}*|ма[яй]|июн\\p{L}*|июл\\p{L}*|август\\p{L}*|сентябр\\p{L}*|октябр\\p{L}*|ноябр\\p{L}*|декабр\\p{L}*)\\b", Pattern.UNICODE_CHARACTER_CLASS);

    record Found(String remainder, LocalDate date, LocalTime time, List<TextParseResult.Issue> issues, boolean rejected, boolean ambiguous) {}

    static Found extract(String text) {
        var issues = new ArrayList<TextParseResult.Issue>();
        var remainder = new StringBuilder(text);
        LocalDate date = null;
        LocalTime time = null;
        boolean rejected = false;
        int dates = 0, times = 0;
        var datesMatcher = DATE.matcher(text);
        while (datesMatcher.find()) {
            dates++;
            try {
                String candidate = datesMatcher.group();
                if (!(candidate.matches("[0-9]{2}\\.[0-9]{2}\\.[0-9]{4}") || candidate.matches("[0-9]{4}-[0-9]{2}-[0-9]{2}"))) {
                    throw new DateTimeException("Unsupported date format");
                }
                LocalDate parsed = LocalDate.parse(candidate, candidate.contains(".") ? DOTTED : ISO);
                if (parsed.getYear() < 1) throw new DateTimeException("Year must be positive");
                date = parsed;
            } catch (DateTimeException exception) {
                rejected = true;
                issues.add(new TextParseResult.Issue("INVALID_DATE", "date", "Несуществующая дата или неподдержанный формат. Нужен ДД.ММ.ГГГГ либо ГГГГ-ММ-ДД."));
            }
            erase(remainder, datesMatcher.start(), datesMatcher.end());
        }
        var timesMatcher = TIME.matcher(text);
        while (timesMatcher.find()) {
            times++;
            try {
                String candidate = timesMatcher.group();
                if (!candidate.matches("[0-9]{2}:[0-9]{2}")) throw new DateTimeException("Only HH:mm supported");
                time = LocalTime.parse(candidate);
            } catch (DateTimeException exception) {
                rejected = true;
                issues.add(new TextParseResult.Issue("INVALID_TIME", "time", "Некорректное время; нужен формат ЧЧ:ММ."));
            }
            erase(remainder, timesMatcher.start(), timesMatcher.end());
        }
        boolean ambiguous = dates > 1 || times > 1;
        if (ambiguous) {
            issues.add(new TextParseResult.Issue("MULTIPLE_TIMES", "text", "Указано несколько дат или времён: разделите события либо уточните одно."));
            date = null;
            time = null;
        }
        var relative = RELATIVE.matcher(text);
        if (relative.find()) {
            issues.add(new TextParseResult.Issue("UNSUPPORTED_DATE_EXPRESSION", "date", "Уточните дату/время явно: относительные и словесные даты пока не вычисляются."));
        }
        return new Found(remainder.toString(), date, time, List.copyOf(issues), rejected, ambiguous);
    }

    static void erase(StringBuilder text, int start, int end) {
        for (int i = start; i < end; i++) text.setCharAt(i, ' ');
    }

    static String withoutRelativeExpressions(String text) {
        return RELATIVE.matcher(text).replaceAll(" ");
    }
}
