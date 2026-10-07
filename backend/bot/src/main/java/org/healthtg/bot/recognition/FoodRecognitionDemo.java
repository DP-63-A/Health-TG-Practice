package org.healthtg.bot.recognition;

import java.io.IOException;
import java.io.OutputStreamWriter;
import java.io.PrintWriter;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;

/** Local command line entry point: intentionally does not start Spring or Telegram. */
public final class FoodRecognitionDemo {
    private FoodRecognitionDemo() { }
    public static void main(String[] args) throws IOException {
        var out = new PrintWriter(new OutputStreamWriter(System.out, StandardCharsets.UTF_8), true);
        var err = new PrintWriter(new OutputStreamWriter(System.err, StandardCharsets.UTF_8), true);
        if (args.length < 2 || args.length > 3 || (!args[0].equals("fixture") && !args[0].equals("live"))) {
            err.println("Использование: fixture|live <путь к PNG/JPEG> [модель для live]");
            System.exit(2);
        }
        try {
            RecognitionProvider provider;
            if (args[0].equals("fixture")) {
                if (args.length != 2) throw new RecognitionException(RecognitionException.Code.CONFIGURATION);
                provider = FixtureRecognitionProvider.bundled();
                out.println("FIXTURE: сохранённый учебный ответ; содержимое изображения не распознаётся.");
            } else {
                String model = args.length == 3 ? args[2] : GeminiRecognitionProvider.DEFAULT_MODEL;
                provider = new GeminiRecognitionProvider(System.getenv("GEMINI_API_KEY"), model);
                out.println("LIVE: один запрос к модели " + model);
            }
            var outcome = new FoodRecognitionService(new ImageValidator(), provider, new RecognitionResponseParser())
                    .recognize(Path.of(args[1]));
            out.println(outcome.result().needsClarification() ? "Нужны уточнения; ничего не сохранено." : "Распознано; ничего не сохранено.");
            out.println(RecognitionJson.MAPPER.writerWithDefaultPrettyPrinter().writeValueAsString(outcome));
        } catch (RecognitionException e) {
            err.println("Распознавание не выполнено: " + e.code());
            System.exit(1);
        }
    }
}
