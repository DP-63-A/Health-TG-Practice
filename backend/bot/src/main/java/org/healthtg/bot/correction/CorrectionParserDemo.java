package org.healthtg.bot.correction;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.PrintWriter;
import java.nio.charset.StandardCharsets;

/** Local UTF-8 console demonstration; no transport, database or field mutations. */
public final class CorrectionParserDemo {
    private CorrectionParserDemo() {}

    public static void main(String[] args) throws IOException {
        var parser = new CorrectionInputParser();
        var reader = new BufferedReader(new InputStreamReader(System.in, StandardCharsets.UTF_8));
        var writer = new PrintWriter(System.out, true, StandardCharsets.UTF_8);
        writer.println("Проверка ввода: число, дата; ё, 🙂. Команды: number 12,5 | date 05.10.2026 | exit");
        String line;
        while ((line = reader.readLine()) != null) {
            if (line.equals("exit")) break;
            writer.println("Ввод: " + line);
            CorrectionResult<?> result;
            if (line.startsWith("number ")) {
                result = parser.parseNumber(line.substring(7));
            } else if (line.startsWith("date ")) {
                result = parser.parseDate(line.substring(5));
            } else {
                writer.println("Нужна команда number или date, затем пробел и значение; exit — выход.");
                continue;
            }
            switch (result) {
                case CorrectionResult.Success<?> success -> writer.println("Значение: " + success.value());
                case CorrectionResult.Failure<?> failure -> writer.println(failure.code() + ": " + failure.message());
            }
        }
    }
}
