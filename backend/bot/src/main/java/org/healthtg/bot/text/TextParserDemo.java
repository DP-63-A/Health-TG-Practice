package org.healthtg.bot.text;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.math.BigDecimal;
import java.util.Map;
import java.util.stream.Collectors;

/** Local demonstration only. Does not boot Spring or contact Telegram. Use synthetic messages. */
public final class TextParserDemo {
    private TextParserDemo() {}

    public static void main(String[] args) throws IOException {
        var parser = new TextInputParser();
        if (args.length > 0) {
            show(parser.parse(String.join(" ", args)));
            return;
        }
        System.out.println("Разбор текста без сохранения. Используйте вымышленные примеры. Пустая строка — выход.");
        var console = System.console();
        if (console != null) {
            String line;
            while ((line = console.readLine("> ")) != null && !line.isEmpty()) show(parser.parse(line));
        } else {
            var reader = new BufferedReader(new InputStreamReader(System.in));
            String line;
            while ((line = reader.readLine()) != null && !line.isEmpty()) show(parser.parse(line));
        }
    }

    private static void show(TextParseResult result) {
        String outcome = switch (result.outcome()) {
            case PARSED -> "Данные разобраны";
            case NEEDS_CLARIFICATION -> "Нужно уточнение";
            case NOTE_SUGGESTED -> "Предложение заметки";
            case REJECTED -> "Ошибка ввода";
        };
        System.out.println("Результат разбора: " + outcome + " (" + result.outcome() + ")");
        if (result.data() != null) {
            System.out.println("Тип: " + result.data().type() + "; данные: " + readable(result.data().payload()));
            System.out.println("Дата: " + readable(result.data().date()) + "; время: " + readable(result.data().time()));
            System.out.println("Происхождение: " + readable(result.data().fieldOrigins()));
        }
        result.issues().forEach(issue -> System.out.println(issue.code() + " [" + issue.field() + "]: " + issue.message()));
    }

    private static String readable(Object value) {
        if (value == null) return "неизвестно";
        if (value instanceof BigDecimal number) return number.toPlainString();
        if (value instanceof Map<?, ?> map) {
            return map.entrySet().stream().sorted(java.util.Comparator.comparing(entry -> entry.getKey().toString()))
                    .map(entry -> entry.getKey() + "=" + readable(entry.getValue()))
                    .collect(Collectors.joining(", ", "{", "}"));
        }
        return value.toString();
    }
}
