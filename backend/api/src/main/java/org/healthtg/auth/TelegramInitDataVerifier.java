package org.healthtg.auth;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.stereotype.Component;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Clock;
import java.time.Instant;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.stream.Collectors;

@Component
public class TelegramInitDataVerifier {
    private final AuthProperties properties;
    private final Clock clock;
    private final ObjectMapper objectMapper;

    public TelegramInitDataVerifier(AuthProperties properties, Clock clock, ObjectMapper objectMapper) {
        this.properties = properties;
        this.clock = clock;
        this.objectMapper = objectMapper;
    }

    public VerifiedTelegramUser verify(String initData) {
        if (initData == null || initData.isBlank() || properties.telegramBotToken().isBlank()) {
            throw invalid();
        }

        Map<String, String> fields = parse(initData);
        String suppliedHash = required(fields, "hash");
        String dataCheckString = fields.entrySet().stream()
                .filter(entry -> !entry.getKey().equals("hash"))
                .sorted(Map.Entry.comparingByKey())
                .map(entry -> entry.getKey() + "=" + entry.getValue())
                .collect(Collectors.joining("\n"));

        byte[] secretKey = hmac("WebAppData".getBytes(StandardCharsets.UTF_8),
                properties.telegramBotToken().getBytes(StandardCharsets.UTF_8));
        byte[] expectedHash = hmac(secretKey, dataCheckString.getBytes(StandardCharsets.UTF_8));
        byte[] actualHash;
        try {
            actualHash = HexFormat.of().parseHex(suppliedHash);
        } catch (IllegalArgumentException exception) {
            throw invalid();
        }
        if (!MessageDigest.isEqual(expectedHash, actualHash)) {
            throw invalid();
        }

        long authDate = parseLong(required(fields, "auth_date"));
        long now = Instant.now(clock).getEpochSecond();
        if (authDate > now || now - authDate > properties.initDataTtl().toSeconds()) {
            throw invalid();
        }

        try {
            JsonNode user = objectMapper.readTree(required(fields, "user"));
            JsonNode id = user.get("id");
            if (id == null || !id.canConvertToLong()) {
                throw invalid();
            }
            return new VerifiedTelegramUser(id.longValue());
        } catch (AuthFailureException exception) {
            throw exception;
        } catch (Exception exception) {
            throw invalid();
        }
    }

    private static Map<String, String> parse(String initData) {
        Map<String, String> result = new LinkedHashMap<>();
        for (String part : initData.split("&", -1)) {
            int separator = part.indexOf('=');
            if (separator <= 0) {
                throw invalid();
            }
            String key = decode(part.substring(0, separator));
            String value = decode(part.substring(separator + 1));
            if (result.putIfAbsent(key, value) != null) {
                throw invalid();
            }
        }
        return result;
    }

    private static String decode(String value) {
        try {
            return URLDecoder.decode(value, StandardCharsets.UTF_8);
        } catch (IllegalArgumentException exception) {
            throw invalid();
        }
    }

    private static String required(Map<String, String> fields, String key) {
        String value = fields.get(key);
        if (value == null || value.isBlank()) {
            throw invalid();
        }
        return value;
    }

    private static long parseLong(String value) {
        try {
            return Long.parseLong(value);
        } catch (NumberFormatException exception) {
            throw invalid();
        }
    }

    private static byte[] hmac(byte[] key, byte[] value) {
        try {
            Mac mac = Mac.getInstance("HmacSHA256");
            mac.init(new SecretKeySpec(key, "HmacSHA256"));
            return mac.doFinal(value);
        } catch (Exception exception) {
            throw new IllegalStateException("HMAC-SHA256 is unavailable", exception);
        }
    }

    private static AuthFailureException invalid() {
        return new AuthFailureException("Telegram authentication failed");
    }
}
