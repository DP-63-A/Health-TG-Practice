package org.healthtg.bot.visual;

import org.healthtg.bot.recognition.*;
import org.telegram.telegrambots.meta.generics.TelegramClient;
import org.telegram.telegrambots.meta.api.methods.GetFile;
import okhttp3.*;
import java.util.concurrent.TimeUnit;

/** Downloads only Telegram file paths; bounded bytes, no redirects, no secret-bearing exceptions. */
public final class TelegramImageLoader implements FoodPhotoFlow.ImageLoader {
    private final TelegramClient telegram;
    private final String token;
    private final OkHttpClient http = new OkHttpClient.Builder().connectTimeout(10, TimeUnit.SECONDS)
            .callTimeout(30, TimeUnit.SECONDS).followRedirects(false).followSslRedirects(false).build();
    public TelegramImageLoader(TelegramClient telegram, String token) { this.telegram = telegram; this.token = token; }
    public byte[] load(String fileId) throws RecognitionException {
        try {
            var file = telegram.execute(GetFile.builder().fileId(fileId).build());
            if (file.getFileSize() != null && file.getFileSize() > ImageValidator.MAX_BYTES)
                throw new RecognitionException(RecognitionException.Code.IMAGE_TOO_LARGE);
            String path = file.getFilePath();
            if (path == null || !path.matches("[A-Za-z0-9_./-]+") || path.contains("..") || path.startsWith("/"))
                throw new RecognitionException(RecognitionException.Code.INVALID_IMAGE);
            try (var response = http.newCall(new Request.Builder().url("https://api.telegram.org/file/bot" + token + "/" + path).build()).execute()) {
                if (!response.isSuccessful() || response.body() == null) throw new RecognitionException(RecognitionException.Code.NETWORK);
                if (response.body().contentLength() > ImageValidator.MAX_BYTES) throw new RecognitionException(RecognitionException.Code.IMAGE_TOO_LARGE);
                byte[] bytes = response.body().byteStream().readNBytes(ImageValidator.MAX_BYTES + 1);
                if (bytes.length > ImageValidator.MAX_BYTES) throw new RecognitionException(RecognitionException.Code.IMAGE_TOO_LARGE);
                return bytes;
            }
        } catch (RecognitionException safe) { throw safe; }
        catch (Exception failure) { throw new RecognitionException(RecognitionException.Code.NETWORK); }
    }
}
