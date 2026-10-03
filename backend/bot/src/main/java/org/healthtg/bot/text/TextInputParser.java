package org.healthtg.bot.text;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static org.healthtg.bot.text.TextParseResult.Outcome.*;

/** Deterministic, bounded text parsing. No transport, persistence or inferred timestamps. */
public final class TextInputParser {
    public static final int MAX_CODE_POINTS = 2000;
    private static final Pattern SLEEP = words("сон|сна|сном|спал|спала|поспал|поспала|проспал|проспала|спать");
    private static final Pattern STEPS = words("шаг|шага|шагов|шаги|шагам|нашагал|нашагала");
    private static final Pattern HEART = words("пульс|пульса|сердцебиение|сердцебиения|частота сердечных сокращений");
    private static final Pattern MEAL = words("съел|съела|поел|поела|скушал|скушала|ел|ела|еда|на завтрак|на обед|на ужин|прием пищи");
    private static final Pattern MEAL_VERB = words("съел|съела|поел|поела|скушал|скушала|ел|ела");
    private static final Pattern MEAL_PREFIX = words("я|в|за|на завтрак|на обед|на ужин|еда|прием пищи");
    private static final Pattern PREPARING_FOOD = words("готовлю|готовил|готовила|приготовил|приготовила|приготовлю|заказал|заказала|заказываю|закажу|купил|купила|покупаю|куплю");
    private static final Pattern NON_FACT = words("хочу|хочется|хотел|хотела|планирую|планировал|планировала|буду|будет|будем|будут|бы|если|надо|нужно|должен|должна|собираюсь|мечтаю|цель|целью|желаю|можно|сколько|разве|ли");
    private static final Pattern OTHER = words("дочь|дочка|сын|ребенок|ребенка|муж|жена|мама|папа|мать|отец|отца|мамы|папы|жены|мужа|брата|брат|сестра|сестры|друг|подруга|друга|подруги|он|она|они|мы|вы|ты|его|ее|их|наш|наша|сосед|пациент|пациента");
    private static final Pattern ALTERNATIVE = words("или|либо|примерно|около|приблизительно|возможно|кажется|наверное|где-то");
    private static final Pattern NEGATION = words("не|нет|ни");
    private static final Pattern DAILY = words("за день|за сутки|суточный|суточная|суточное|суточные|итого за день|дневной итог|дневной итого");
    private static final Pattern WAKING = words("проснулся|проснулась|пробуждение|пробуждения");
    private static final Pattern MULTI = words("потом|затем|снова|еще|также|после этого");
    private static final Pattern HOUR = unit("ч|час|часа|часов");
    private static final Pattern MINUTE = unit("мин|минута|минуты|минут|минуту");
    private static final Pattern MASS = unit("кг|килограмм|килограмма|килограммов|г|гр|грамм|грамма|граммов");
    private static final Pattern BPM = unit("bpm|уд/мин|уд\\s*/\\s*мин|удар/мин|ударов/мин|ударов в минуту|удара в минуту|удар в минуту");
    private static final Pattern METRIC_WORDS = words("я|у|меня|мой|моя|мое|моего|мои|мне|за|день|сутки|суточный|суточная|суточное|суточные|дневной|итог|итого|в|на|к|от|составил|составила|составило|составляет|был|была|было|были|равен|равна|равно|продолжительность|длительность|количество|суммарная|общее|общая|всего|получилось|прошел|прошла|прошагал|прошагала|накопилось|и|проснулся|проснулась|пробуждение|пробуждения|покое|текущий|сейчас|измерил|измерила|измерение");

    public TextParseResult parse(String input) {
        if (input == null) return rejected(null, "EMPTY_INPUT", "text", "Текст не передан.");
        if (input.codePointCount(0, input.length()) > MAX_CODE_POINTS) {
            return rejected(input, "TOO_LONG", "text", "Максимальная длина — 2000 Unicode-кодовых точек.");
        }
        if (!wellFormed(input)) return rejected(input, "INVALID_UNICODE", "text", "Строка содержит повреждённый символ Unicode.");
        if (input.codePoints().anyMatch(TextInputParser::isEmoji)) {
            return rejected(input, "EMOJI_NOT_SUPPORTED", "text", "Эмодзи в текстовых записях не поддерживаются.");
        }
        String text = normalize(input);
        if (text.isBlank()) return rejected(input, "EMPTY_INPUT", "text", "Введите непустой текст.");
        if (text.codePoints().noneMatch(Character::isLetterOrDigit)) {
            return note(input, "UNRECOGNIZED_TEXT", "Структурированные данные не найдены; можно предложить заметку.");
        }
        var temporal = ExplicitTime.extract(text);
        if (temporal.rejected()) return new TextParseResult(REJECTED, input, null, temporal.issues());
        if (temporal.ambiguous()) return new TextParseResult(NEEDS_CLARIFICATION, input, null, temporal.issues());

        boolean sleep = has(SLEEP, text), steps = has(STEPS, text), heart = has(HEART, text), meal = has(MEAL, text);
        int kinds = (sleep ? 1 : 0) + (steps ? 1 : 0) + (heart ? 1 : 0) + (meal ? 1 : 0);
        if (kinds == 0) return note(input, "UNRECOGNIZED_TEXT", "Не удалось надёжно распознать запись; можно предложить заметку.");
        if (text.matches("(?s).*[\"«»„“”].*")) {
            return clarification(input, "QUOTED_CONTEXT", "text", "В тексте есть цитата: уточните, является ли это сообщением о вашем событии.");
        }
        if (kinds > 1 || has(MULTI, text) || text.contains(";")) {
            return clarification(input, "MULTIPLE_EVENTS", "text", "Возможно несколько событий: отправьте их отдельно.");
        }
        if (has(NON_FACT, text) || has(OTHER, text) || text.contains("?")) {
            return note(input, "NOT_SELF_REPORT", "Вопрос, намерение или рассказ о другом человеке не считается фактом пользователя.");
        }
        if (has(ALTERNATIVE, text) || (has(NEGATION, text) && !RussianNumbers.scan(temporal.remainder()).isEmpty())) {
            return clarification(input, "AMBIGUOUS_MEANING", "text", "Есть отрицание, поправка или неоднозначность; уточните одно значение.");
        }
        if (has(NEGATION, text)) return note(input, "NEGATED_REPORT", "Не превращаем отрицание в измерение или ноль.");
        return meal ? meal(input, text, temporal) : metric(input, text, temporal, sleep ? "sleep_duration_min" : steps ? "steps" : "heart_rate");
    }

    private TextParseResult metric(String input, String text, ExplicitTime.Found temporal, String code) {
        String body = temporal.remainder();
        List<RussianNumbers.Number> numbers = RussianNumbers.scan(body);
        if (numbers.stream().anyMatch(n -> !n.valid())) {
            return clarification(input, "UNSUPPORTED_NUMBER", "value", "Число записано неоднозначно или в неподдерживаемой форме.");
        }
        if (numbers.isEmpty()) return note(input, "NO_MEASUREMENT", "Числового измерения нет; можно предложить заметку.");
        if (numbers.stream().anyMatch(n -> n.value().signum() < 0)) {
            if (code.equals("heart_rate")) return clarification(input, "UNSUPPORTED_SIGNED_HEART_RATE", "value", "Отрицательная запись пульса не поддержана правилами разбора; уточните значение.");
            return rejected(input, "NEGATIVE_VALUE", "value", "Измерение не может быть отрицательным.");
        }
        var issues = new ArrayList<>(temporal.issues());
        var payload = new LinkedHashMap<String, Object>();
        var origins = new LinkedHashMap<String, String>();
        var consumed = new StringBuilder(body);
        put(payload, origins, "code", code, "reported");
        BigDecimal value;
        String valueOrigin = "reported";
        String measureUnit = null;
        if (code.equals("sleep_duration_min")) {
            if (numbers.size() > 2) return clarification(input, "AMBIGUOUS_VALUE", "value", "Укажите одну длительность сна.");
            value = BigDecimal.ZERO;
            boolean seenHours = false, seenMinutes = false;
            for (var number : numbers) {
                var hour = after(HOUR, body, number.end());
                var minute = after(MINUTE, body, number.end());
                if (hour == null && minute == null) {
                    return clarification(input, "MISSING_UNIT", "unit", "Для длительности укажите часы или минуты.");
                }
                boolean hours = hour != null;
                if ((hours && seenHours) || (!hours && seenMinutes)) {
                    return clarification(input, "MULTIPLE_VALUES", "value", "Указано несколько длительностей; уточните одну.");
                }
                if (hours) { seenHours = true; valueOrigin = "computed"; }
                else seenMinutes = true;
                Matcher matched = hours ? hour : minute;
                value = value.add(number.value().multiply(hours ? BigDecimal.valueOf(60) : BigDecimal.ONE));
                ExplicitTime.erase(consumed, number.start(), matched.end());
            }
            measureUnit = "min";
            if (!has(DAILY, text) && !has(WAKING, text)) {
                issues.add(issue("MISSING_DAY_SCOPE", "day_scope", "Уточните, является ли сон дневным итогом, и дату пробуждения."));
            }
        } else {
            if (numbers.size() != 1) return clarification(input, "AMBIGUOUS_VALUE", "value", "Указано несколько чисел; нельзя выбрать значение автоматически.");
            var number = numbers.getFirst();
            value = number.value();
            ExplicitTime.erase(consumed, number.start(), number.end());
            if (code.equals("steps")) {
                if (value.stripTrailingZeros().scale() > 0) return rejected(input, "NON_INTEGER_STEPS", "value", "Количество шагов должно быть целым.");
                measureUnit = "count";
                if (!has(DAILY, text)) issues.add(issue("MISSING_DAY_SCOPE", "day_scope", "Уточните, является ли количество шагов итогом дня."));
            } else {
                Matcher bpm = after(BPM, body, number.end());
                if (bpm != null) {
                    measureUnit = "bpm";
                    ExplicitTime.erase(consumed, bpm.start(), bpm.end());
                } else issues.add(issue("MISSING_UNIT", "unit", "Не указана единица пульса; уточните уд/мин."));
                boolean resting = text.contains("в покое"), instant = has(words("текущий|сейчас"), text);
                if (resting && instant) return clarification(input, "AMBIGUOUS_QUALIFIER", "qualifier", "Уточните один контекст пульса.");
                if (resting || instant) put(payload, origins, "qualifier", resting ? "resting" : "instant", "reported");
            }
        }
        put(payload, origins, "value", value.stripTrailingZeros(), valueOrigin);
        if (measureUnit != null) put(payload, origins, "unit", measureUnit, "reported");
        addMetricTime(temporal, payload, origins);
        if (temporal.date() == null) issues.add(issue("MISSING_DATE", "local_date", "Дата неизвестна; уточните календарную дату события."));
        String residue = ExplicitTime.withoutRelativeExpressions(consumed.toString());
        for (Pattern pattern : List.of(SLEEP, STEPS, HEART, METRIC_WORDS)) residue = pattern.matcher(residue).replaceAll(" ");
        residue = residue.replaceAll("[\\s,.:!—–()]+", " ").strip();
        if (!residue.isEmpty()) {
            issues.add(issue("UNSUPPORTED_CONTEXT", "text", "Часть выражения не распознана; уточните смысл: " + residue));
            // A foreign unit or unknown subject can change what this number describes. Only the
            // recognized label and date remain known; do not expose a speculative health value.
            for (String field : List.of("value", "unit", "qualifier")) {
                payload.remove(field);
                origins.remove(field);
            }
        }
        return known(input, "metrics", payload, origins, temporal, issues);
    }

    private TextParseResult meal(String input, String text, ExplicitTime.Found temporal) {
        String body = temporal.remainder();
        if (!isOwnMealStatement(body)) {
            return clarification(input, "UNSUPPORTED_MEAL_CONTEXT", "text", "Неясно, кто и что сделал; уточните сообщение о своём приёме пищи.");
        }
        List<RussianNumbers.Number> numbers = RussianNumbers.scan(body);
        if (numbers.stream().anyMatch(n -> !n.valid())) return clarification(input, "UNSUPPORTED_NUMBER", "mass_g", "Не удалось однозначно разобрать массу.");
        if (numbers.size() > 1) return clarification(input, "MULTIPLE_VALUES", "text", "Несколько чисел или порций: уточните одну запись о еде.");
        var payload = new LinkedHashMap<String, Object>();
        var origins = new LinkedHashMap<String, String>();
        var description = new StringBuilder(body);
        if (!numbers.isEmpty()) {
            var number = numbers.getFirst();
            if (number.value().signum() < 0) return rejected(input, "NEGATIVE_MASS", "mass_g", "Масса не может быть отрицательной.");
            var mass = after(MASS, body, number.end());
            if (mass == null) return clarification(input, "UNSUPPORTED_MEAL_NUMBER", "mass_g", "Неизвестно, что означает число; для массы укажите г или кг.");
            if (body.substring(mass.end()).stripLeading().startsWith("/")) {
                return clarification(input, "UNSUPPORTED_MASS_UNIT", "mass_g", "Составная единица не является массой порции; уточните массу в г или кг.");
            }
            if (has(words("и|плюс"), body)
                    || (!cleanMealDescription(body.substring(0, number.start())).isEmpty()
                    && !cleanMealDescription(body.substring(mass.end())).isEmpty())) {
                return clarification(input, "AMBIGUOUS_MASS_SCOPE", "mass_g", "Неясно, к какой еде относится масса; уточните одну порцию и её массу.");
            }
            boolean kilograms = mass.group(1).startsWith("к");
            put(payload, origins, "mass_g", number.value().multiply(kilograms ? BigDecimal.valueOf(1000) : BigDecimal.ONE).stripTrailingZeros(), kilograms ? "computed" : "reported");
            ExplicitTime.erase(description, number.start(), mass.end());
        }
        int meals = count(words("на завтрак|на обед|на ужин"), description.toString());
        if (meals > 1) return clarification(input, "MULTIPLE_MEALS", "text", "Разделите приёмы пищи на отдельные сообщения.");
        String descriptionText = cleanMealDescription(description.toString());
        if (descriptionText.isEmpty() || descriptionText.codePoints().noneMatch(Character::isLetter)) {
            return clarification(input, "MISSING_DESCRIPTION", "description", "Укажите, что вы ели.");
        }
        // Keep the user's words, punctuation and letter case; the normalized copy is used only to locate spans.
        String userDescription = recoverDescription(input, text, descriptionText);
        put(payload, origins, "description", userDescription, "reported");
        var issues = new ArrayList<>(temporal.issues());
        if (temporal.date() == null) issues.add(issue("MISSING_DATE", "date", "Дата неизвестна; уточните дату приёма пищи."));
        if (has(words("сегодня|вчера|позавчера|завтра|утром|вечером|ночью"), descriptionText)) {
            issues.add(issue("UNSUPPORTED_CONTEXT", "text", "В описании есть неподдержанное обозначение времени."));
        }
        return known(input, "meal", payload, origins, temporal, issues);
    }

    private static boolean isOwnMealStatement(String body) {
        if (has(PREPARING_FOOD, body)) return false;
        var verb = MEAL_VERB.matcher(body);
        if (verb.find()) {
            // Only known self-report connectors may precede the action. An arbitrary noun
            // before it can be an unknown person: do not classify that noun as food.
            String prefix = MEAL_PREFIX.matcher(body.substring(0, verb.start())).replaceAll(" ");
            if (!prefix.replaceAll("[\\s,:.!—–]+", "").isEmpty()) return false;
            return !verb.find();
        }
        String start = body.replaceFirst("(?U)^[\\s,:.!—–]*(?:(?:в|за|я)\\s+)*", "");
        return words("еда|прием пищи|на (?:завтрак|обед|ужин)\\s+(?:был|была|было|были)").matcher(start).lookingAt();
    }

    private static String cleanMealDescription(String body) {
        return body.replaceAll("(?U)\\b(?:я|съел|съела|поел|поела|скушал|скушала|ел|ела|еда|прием пищи|был|была|было|были)\\b", " ")
                .replaceAll("(?U)\\bна (?:завтрак|обед|ужин)\\b", " ")
                .replaceAll("^[\\s,:.!—–]*(?:в\\s+)?|[\\s,:.!—–]+$", " ")
                .replaceAll("\\s+", " ").strip();
    }

    private static String recoverDescription(String original, String normalized, String description) {
        // Exact contiguous descriptions can retain original case. Discontinuous ones retain normalized words,
        // while originalText is always available and remains the authoritative source.
        int index = normalized.indexOf(description);
        if (index >= 0 && original.length() == normalized.length()) return original.substring(index, index + description.length());
        return description;
    }

    private static void addMetricTime(ExplicitTime.Found temporal, Map<String, Object> payload, Map<String, String> origins) {
        if (temporal.date() != null) put(payload, origins, "local_date", temporal.date().toString(), "reported");
        if (temporal.time() != null) put(payload, origins, "local_time", temporal.time().toString(), "reported");
    }

    private static TextParseResult known(String input, String type, Map<String, Object> payload, Map<String, String> origins,
                                         ExplicitTime.Found temporal, List<TextParseResult.Issue> issues) {
        return new TextParseResult(issues.isEmpty() ? PARSED : NEEDS_CLARIFICATION, input,
                new TextParseResult.ParsedData(type, payload, origins, temporal.date(), temporal.time()), issues);
    }

    private static TextParseResult note(String input, String code, String message) {
        return new TextParseResult(NOTE_SUGGESTED, input,
                new TextParseResult.ParsedData("note", Map.of("text", input), Map.of("text", "reported"), null, null),
                List.of(issue(code, "text", message)));
    }

    private static TextParseResult clarification(String input, String code, String field, String message) {
        return new TextParseResult(NEEDS_CLARIFICATION, input, null, List.of(issue(code, field, message)));
    }

    private static TextParseResult rejected(String input, String code, String field, String message) {
        return new TextParseResult(REJECTED, input, null, List.of(issue(code, field, message)));
    }

    private static TextParseResult.Issue issue(String code, String field, String message) {
        return new TextParseResult.Issue(code, field, message);
    }

    private static void put(Map<String, Object> payload, Map<String, String> origins, String key, Object value, String origin) {
        payload.put(key, value); origins.put(key, origin);
    }

    private static Pattern words(String alternatives) { return Pattern.compile("\\b(?:" + alternatives + ")\\b", Pattern.UNICODE_CHARACTER_CLASS); }
    private static Pattern unit(String alternatives) { return Pattern.compile("\\s*(" + alternatives + ")(?![\\p{L}0-9])", Pattern.UNICODE_CHARACTER_CLASS); }
    private static boolean has(Pattern pattern, String text) { return pattern.matcher(text).find(); }
    private static int count(Pattern pattern, String text) { int count = 0; var matcher = pattern.matcher(text); while (matcher.find()) count++; return count; }
    private static Matcher after(Pattern pattern, String text, int offset) {
        var matcher = pattern.matcher(text).region(offset, text.length());
        return matcher.lookingAt() ? matcher : null;
    }

    private static String normalize(String input) {
        var normalized = new StringBuilder();
        input.toLowerCase(Locale.ROOT).replace('ё', 'е').replace('−', '-').codePoints().forEach(cp ->
                normalized.appendCodePoint(Character.isWhitespace(cp) || Character.isSpaceChar(cp) ? ' ' : cp));
        return normalized.toString();
    }

    private static boolean wellFormed(String text) {
        for (int i = 0; i < text.length(); i++) {
            char c = text.charAt(i);
            if (Character.isHighSurrogate(c)) {
                if (++i >= text.length() || !Character.isLowSurrogate(text.charAt(i))) return false;
            } else if (Character.isLowSurrogate(c)) return false;
        }
        return true;
    }

    private static boolean isEmoji(int codePoint) {
        return codePoint == 0x200D || codePoint == 0x20E3 || codePoint == 0xFE0F
                || codePoint >= 0x1F000 && codePoint <= 0x1FAFF
                || codePoint >= 0x2600 && codePoint <= 0x27BF;
    }
}
