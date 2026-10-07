package org.healthtg.bot.datetime;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.healthtg.core.dialog.DialogState;
import java.net.URI;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.*;
import java.util.*;

/** The URL is presentation only. Every returned value is checked against the owner's durable dialog. */
public final class DateTimePicker {
    private static final ObjectMapper JSON = new ObjectMapper()
            .enable(com.fasterxml.jackson.databind.DeserializationFeature.FAIL_ON_TRAILING_TOKENS)
            .enable(com.fasterxml.jackson.core.JsonParser.Feature.STRICT_DUPLICATE_DETECTION);
    private DateTimePicker() {}
    public record Form(String date, String time, boolean withTime, boolean dateLocked, boolean timeLocked,
                       String label) {}
    public record Selection(LocalDate date, LocalTime time) {}

    public static Map<String, Object> rotate(Map<String, Object> original) {
        var result = new LinkedHashMap<>(original);
        result.put("picker_nonce", UUID.randomUUID().toString());
        return result;
    }

    /** Receipt is written with the already validated selection, before acknowledging it. */
    public static void recordReceipt(Map<String, Object> context, String raw, long updateId) {
        context.put("picker_receipt_hash", fingerprint(raw));
        context.put("picker_receipt_update", updateId);
    }

    public static boolean matchesReceipt(DialogState state, String raw, long updateId, String phase) {
        if (raw == null || raw.getBytes(StandardCharsets.UTF_8).length > 1024
                || !state.telegramUpdateKey().equals(phase + ":" + updateId)
                || !(state.context().get("picker_receipt_update") instanceof Number receiptUpdate)
                || !Long.toString(updateId).equals(receiptUpdate.toString())) return false;
        return fingerprint(raw).equals(state.context().get("picker_receipt_hash"));
    }

    private static String fingerprint(String raw) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(raw.getBytes(StandardCharsets.UTF_8)));
        } catch (java.security.NoSuchAlgorithmException impossible) { throw new IllegalStateException(impossible); }
    }

    private static String token(DialogState state, ZoneId zone) {
        Object nonce = state.context().get("picker_nonce");
        if (!(nonce instanceof String text)) throw new IllegalArgumentException("No picker context");
        UUID.fromString(text);
        try {
            String input = text + "|" + state.ownerId() + "|" + state.revision() + "|" + state.step() + "|" + zone.getId();
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(input.getBytes(StandardCharsets.UTF_8)));
        } catch (java.security.NoSuchAlgorithmException impossible) { throw new IllegalStateException(impossible); }
    }

    public static URI url(URI base, DialogState state, ZoneId zone, Form form) {
        var values = new LinkedHashMap<String, String>();
        values.put("token", token(state, zone)); values.put("revision", Long.toString(state.revision()));
        values.put("zone", zone.getId()); values.put("label", form.label());
        values.put("withTime", Boolean.toString(form.withTime()));
        values.put("dateLocked", Boolean.toString(form.dateLocked()));
        values.put("timeLocked", Boolean.toString(form.timeLocked()));
        values.put("date", Objects.toString(form.date(), "")); values.put("time", Objects.toString(form.time(), ""));
        String fragment = values.entrySet().stream().map(e -> encode(e.getKey()) + "=" + encode(e.getValue()))
                .collect(java.util.stream.Collectors.joining("&"));
        String path = Objects.toString(base.getRawPath(), "");
        return URI.create(base.getScheme() + "://" + base.getRawAuthority() + path
                + (path.endsWith("/") ? "" : "/") + "datetime-picker.html#" + fragment);
    }

    public static Selection parse(String raw, DialogState state, ZoneId zone, Form form) {
        if (raw == null || raw.getBytes(StandardCharsets.UTF_8).length > 1024) throw new IllegalArgumentException("Invalid picker data");
        try {
            JsonNode node = JSON.readTree(raw);
            if (node == null || !node.isObject() || node.size() != (form.withTime() ? 5 : 4)
                    || !node.path("v").isInt() || node.path("v").intValue() != 1
                    || !node.path("revision").isIntegralNumber() || !node.path("revision").canConvertToLong()
                    || node.path("revision").longValue() != state.revision()
                    || !node.path("token").isTextual() || !token(state, zone).equals(node.path("token").textValue())
                    || !node.path("date").isTextual()) throw new IllegalArgumentException("Stale picker");
            String date = node.path("date").textValue();
            if (!date.matches("[0-9]{4}-[0-9]{2}-[0-9]{2}")) throw new IllegalArgumentException("Invalid date");
            LocalDate parsedDate = LocalDate.parse(date);
            if (parsedDate.getYear() < 1) throw new IllegalArgumentException("Invalid year");
            LocalTime time = null;
            if (form.withTime()) {
                if (!node.path("time").isTextual() || !node.path("time").textValue().matches("[0-9]{2}:[0-9]{2}(:[0-9]{2}(\\.[0-9]{1,9})?)?"))
                    throw new IllegalArgumentException("Invalid time");
                time = LocalTime.parse(node.path("time").textValue());
                instant(parsedDate, time, zone);
            }
            if (form.dateLocked() && !parsedDate.equals(LocalDate.parse(form.date()))) throw new IllegalArgumentException("Known date changed");
            if (form.timeLocked() && !Objects.equals(time, LocalTime.parse(form.time()))) throw new IllegalArgumentException("Known time changed");
            return new Selection(parsedDate, time);
        } catch (java.io.IOException | DateTimeException invalid) { throw new IllegalArgumentException("Invalid date/time", invalid); }
    }

    public static Instant instant(LocalDate date, LocalTime time, ZoneId zone) {
        var local = date.atTime(time);
        var offsets = zone.getRules().getValidOffsets(local);
        if (offsets.size() != 1) throw new IllegalArgumentException("Это время не существует или неоднозначно из-за перевода часов. Выберите другое время.");
        return local.toInstant(offsets.getFirst());
    }
    private static String encode(String value) { return URLEncoder.encode(value, StandardCharsets.UTF_8); }
}
