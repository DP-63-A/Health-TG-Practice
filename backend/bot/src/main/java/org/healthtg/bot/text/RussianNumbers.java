package org.healthtg.bot.text;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Pattern;

/** Bounded, exact numbers; no fuzzy correction and no silently concatenated numeral tokens. */
final class RussianNumbers {
    private static final Map<String, Integer> VALUES = new HashMap<>();
    static {
        String[] small = {"ноль", "один", "два", "три", "четыре", "пять", "шесть", "семь", "восемь", "девять",
                "десять", "одиннадцать", "двенадцать", "тринадцать", "четырнадцать", "пятнадцать",
                "шестнадцать", "семнадцать", "восемнадцать", "девятнадцать"};
        for (int i = 0; i < small.length; i++) VALUES.put(small[i], i);
        VALUES.put("нуль", 0); VALUES.put("одна", 1); VALUES.put("одно", 1); VALUES.put("две", 2);
        String[] tens = {"двадцать", "тридцать", "сорок", "пятьдесят", "шестьдесят", "семьдесят", "восемьдесят", "девяносто"};
        for (int i = 0; i < tens.length; i++) VALUES.put(tens[i], (i + 2) * 10);
        String[] hundreds = {"сто", "двести", "триста", "четыреста", "пятьсот", "шестьсот", "семьсот", "восемьсот", "девятьсот"};
        for (int i = 0; i < hundreds.length; i++) VALUES.put(hundreds[i], (i + 1) * 100);
    }
    private static final Pattern TOKENS = Pattern.compile("[+-]?[0-9]+(?:[.,][0-9]+)*|[а-я]+|[a-z]+|[^\\s]", Pattern.UNICODE_CHARACTER_CLASS);
    record Number(int start, int end, BigDecimal value, boolean valid) {}
    private record Token(int start, int end, String value) {}

    static List<Number> scan(String text) {
        var tokens = new ArrayList<Token>();
        var matcher = TOKENS.matcher(text);
        while (matcher.find()) tokens.add(new Token(matcher.start(), matcher.end(), matcher.group()));
        var result = new ArrayList<Number>();
        for (int i = 0; i < tokens.size(); i++) {
            Token first = tokens.get(i);
            String token = first.value();
            if ((token.equals("–") || token.equals("—")) && i + 1 < tokens.size()
                    && isNumeral(tokens.get(i + 1).value())) {
                // A dash before a number can mean a separator or a minus: do not guess the sign.
                result.add(new Number(first.start(), tokens.get(++i).end(), null, false));
                continue;
            }
            boolean negative = token.equals("минус") || token.equals("-"),
                    sign = negative || token.equals("плюс") || token.equals("+");
            if (!sign && !isNumeral(token)) continue;
            int start = first.start();
            if (sign) {
                if (i + 1 >= tokens.size() || !isNumeral(tokens.get(i + 1).value())) {
                    result.add(new Number(start, first.end(), null, false)); continue;
                }
                token = tokens.get(++i).value();
                if (token.startsWith("-") || token.startsWith("+")) {
                    // Two signs are not an arithmetic expression in an observed measurement.
                    result.add(new Number(start, tokens.get(i).end(), null, false));
                    continue;
                }
            }
            Token last = tokens.get(i);
            if (Character.isDigit(token.charAt(0)) || token.charAt(0) == '-' || token.charAt(0) == '+') {
                BigDecimal value = null;
                try { value = new BigDecimal(token.replace(',', '.')); } catch (NumberFormatException ignored) { }
                if (negative && value != null) value = value.negate();
                result.add(new Number(start, last.end(), value, value != null));
                continue;
            }
            List<String> words = new ArrayList<>();
            words.add(token);
            while (i + 1 < tokens.size() && isWordNumber(tokens.get(i + 1).value())
                    && text.substring(last.end(), tokens.get(i + 1).start()).isBlank()) {
                last = tokens.get(++i);
                words.add(last.value());
            }
            Integer value = parseWords(words);
            result.add(new Number(start, last.end(), value == null ? null : BigDecimal.valueOf(negative ? -value : value), value != null));
        }
        return result;
    }

    private static boolean isNumeral(String token) {
        return isWordNumber(token) || token.matches("[+-]?[0-9]+(?:[.,][0-9]+)*");
    }

    private static boolean isWordNumber(String token) {
        return VALUES.containsKey(token) || token.matches("тысяч(?:а|и)?");
    }

    private static Integer parseWords(List<String> words) {
        int thousand = -1;
        for (int i = 0; i < words.size(); i++) {
            if (words.get(i).startsWith("тысяч")) {
                if (thousand >= 0) return null;
                thousand = i;
            }
        }
        if (thousand < 0) return underThousand(words);
        // Both alternatives must stay boxed: an invalid subgroup deliberately returns null.
        Integer left = thousand == 0 ? Integer.valueOf(1) : underThousand(words.subList(0, thousand));
        Integer right = thousand == words.size() - 1 ? Integer.valueOf(0) : underThousand(words.subList(thousand + 1, words.size()));
        if (left == null || left == 0 || right == null) return null;
        return left * 1000 + right;
    }

    private static Integer underThousand(List<String> words) {
        if (words.isEmpty()) return null;
        int sum = 0, previous = 1000;
        for (String word : words) {
            Integer value = VALUES.get(word);
            if (value == null) return null;
            int rank = value >= 100 ? 100 : value >= 20 ? 10 : value >= 10 ? 2 : 1;
            if (rank >= previous || (previous == 2) || (previous == 10 && rank != 1)
                    || (value == 0 && words.size() > 1)) return null;
            sum += value;
            previous = rank;
        }
        return sum;
    }
}
