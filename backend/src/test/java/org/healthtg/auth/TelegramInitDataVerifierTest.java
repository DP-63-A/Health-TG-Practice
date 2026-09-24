package org.healthtg.auth;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.HexFormat;
import java.util.Map;
import java.util.TreeMap;
import java.util.stream.Collectors;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

class TelegramInitDataVerifierTest {
    private static final String BOT_TOKEN = "test-token:keep-out-of-reports";
    private static final Instant NOW = Instant.parse("2026-09-22T12:00:00Z");

    @Test
    void acceptsValidDataAtFifteenMinuteBoundary() {
        TelegramInitDataVerifier verifier = verifier();
        String initData = signed(Map.of(
                "auth_date", Long.toString(NOW.minusSeconds(900).getEpochSecond()),
                "query_id", "query-1",
                "user", "{\"id\":10001,\"first_name\":\"Ignored\"}"
        ));

        assertEquals(10001L, verifier.verify(initData).telegramId());
    }

    @Test
    void rejectsDataOlderThanFifteenMinutes() {
        String initData = signed(Map.of(
                "auth_date", Long.toString(NOW.minusSeconds(901).getEpochSecond()),
                "user", "{\"id\":10001}"
        ));

        assertThrows(AuthFailureException.class, () -> verifier().verify(initData));
    }

    @Test
    void rejectsChangedSignedField() {
        String initData = signed(Map.of(
                "auth_date", Long.toString(NOW.getEpochSecond()),
                "user", "{\"id\":10001}"
        )).replace("10001", "10002");

        assertThrows(AuthFailureException.class, () -> verifier().verify(initData));
    }

    @Test
    void rejectsFutureAuthDate() {
        String initData = signed(Map.of(
                "auth_date", Long.toString(NOW.plusSeconds(1).getEpochSecond()),
                "user", "{\"id\":10001}"
        ));

        assertThrows(AuthFailureException.class, () -> verifier().verify(initData));
    }

    @Test
    void rejectsDuplicateSecurityField() {
        String initData = signed(Map.of(
                "auth_date", Long.toString(NOW.getEpochSecond()),
                "user", "{\"id\":10001}"
        ));

        assertThrows(AuthFailureException.class, () -> verifier().verify(initData + "&auth_date=1"));
    }

    private TelegramInitDataVerifier verifier() {
        AuthProperties properties = new AuthProperties(BOT_TOKEN, "10001",
                Duration.ofMinutes(15), Duration.ofMinutes(60), ZoneId.of("Europe/Warsaw"));
        return new TelegramInitDataVerifier(properties, Clock.fixed(NOW, ZoneOffset.UTC), new ObjectMapper());
    }

    private static String signed(Map<String, String> source) {
        TreeMap<String, String> fields = new TreeMap<>(source);
        String check = fields.entrySet().stream()
                .map(entry -> entry.getKey() + "=" + entry.getValue())
                .collect(Collectors.joining("\n"));
        byte[] secret = hmac("WebAppData".getBytes(StandardCharsets.UTF_8),
                BOT_TOKEN.getBytes(StandardCharsets.UTF_8));
        fields.put("hash", HexFormat.of().formatHex(hmac(secret, check.getBytes(StandardCharsets.UTF_8))));
        return fields.entrySet().stream()
                .map(entry -> encode(entry.getKey()) + "=" + encode(entry.getValue()))
                .collect(Collectors.joining("&"));
    }

    private static byte[] hmac(byte[] key, byte[] value) {
        try {
            Mac mac = Mac.getInstance("HmacSHA256");
            mac.init(new SecretKeySpec(key, "HmacSHA256"));
            return mac.doFinal(value);
        } catch (Exception exception) {
            throw new AssertionError(exception);
        }
    }

    private static String encode(String value) {
        return URLEncoder.encode(value, StandardCharsets.UTF_8);
    }
}
