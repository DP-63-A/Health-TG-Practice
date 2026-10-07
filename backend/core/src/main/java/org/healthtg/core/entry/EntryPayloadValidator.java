package org.healthtg.core.entry;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.format.DateTimeParseException;
import java.util.Collection;
import java.util.Map;
import java.util.Set;

final class EntryPayloadValidator {
    // Resource bounds, not medical limits. Match the 2000-character numeric input budget.
    // Bound both coefficient and exponent before persistence or downstream arithmetic.
    private static final int MAX_NUMERIC_DIGITS = 2000;
    private static final Set<String> MEAL_FIELDS = Set.of(
            "description", "mass_g", "nutrients", "nutrients_basis");
    private static final Set<String> NUTRIENT_FIELDS = Set.of(
            "energy_kcal", "protein_g", "fat_g", "carbs_g");
    private static final Set<String> METRICS_FIELDS = Set.of(
            "code", "value", "unit", "local_date", "local_time", "qualifier");
    private static final Set<String> CHECKIN_FIELDS = Set.of("category", "score");
    private static final Set<String> NOTE_FIELDS = Set.of("text");
    private static final Set<String> METRIC_CODES = Set.of("steps", "sleep_duration_min", "heart_rate");
    private static final Set<String> NUTRIENT_BASES = Set.of("per_100g", "per_serving", "unknown");
    private static final Set<String> HEART_RATE_QUALIFIERS = Set.of("instant", "resting");
    private static final Set<String> FIELD_ORIGINS = Set.of("reported", "extracted", "estimated", "computed");
    /** Steps and sleep minutes are whole numbers; the bound keeps multi-day sums inside long. */
    private static final BigDecimal MAX_WHOLE_METRIC = BigDecimal.valueOf(1_000_000_000L);

    private EntryPayloadValidator() {
    }

    static void validateDraft(EntryType type, Map<String, Object> payload) {
        validateFinite(payload);
        switch (type) {
            case MEAL -> validateMeal(payload);
            case METRICS -> validateMetrics(payload);
            case CHECKIN -> validateCheckin(payload);
            case NOTE -> validateNote(payload);
        }
    }

    static void validateConfirmed(EntryType type, Map<String, Object> payload) {
        validateDraft(type, payload);
        if (type == EntryType.METRICS
                && (!(payload.get("unit") instanceof String unit) || unit.isBlank()
                || !(payload.get("local_date") instanceof String date) || date.isBlank())) {
            throw invalid("Confirmed metrics require unit and local_date");
        }
    }

    static void validateOrigins(Map<String, String> origins) {
        for (Map.Entry<String, String> origin : origins.entrySet()) {
            if (origin.getKey() == null || origin.getKey().isBlank() || origin.getValue() == null
                    || !FIELD_ORIGINS.contains(origin.getValue())) {
                throw invalid("Unknown field origin");
            }
        }
    }

    private static void validateMeal(Map<String, Object> payload) {
        rejectUnknown(payload, MEAL_FIELDS);
        requireText(payload, "description", 2000);
        validateNonNegativeNumber(payload, "mass_g", true);

        Object nutrients = payload.get("nutrients");
        if (payload.containsKey("nutrients")) {
            if (!(nutrients instanceof Map<?, ?> nutrientMap)) {
                throw invalid("nutrients must be an object");
            }
            rejectUnknown(nutrientMap, NUTRIENT_FIELDS);
            for (String field : NUTRIENT_FIELDS) {
                validateNonNegativeNumber(nutrientMap, field, true);
            }
        }

        validateEnum(payload, "nutrients_basis", NUTRIENT_BASES, true);
    }

    private static void validateMetrics(Map<String, Object> payload) {
        rejectUnknown(payload, METRICS_FIELDS);
        String code = requireEnum(payload, "code", METRIC_CODES);
        Number value = requireNumber(payload, "value");
        if (new BigDecimal(value.toString()).signum() < 0) {
            throw invalid("value must be non-negative for " + code);
        }
        if (code.equals("steps") || code.equals("sleep_duration_min")) {
            requireBoundedMetric(code, value);
        }

        validateOptionalText(payload, "unit", 32);
        validateLocalDate(payload.get("local_date"));
        validateLocalTime(payload.get("local_time"));
        validateEnum(payload, "qualifier", HEART_RATE_QUALIFIERS, true);
    }

    private static void requireBoundedMetric(String code, Number value) {
        BigDecimal decimal;
        try {
            decimal = new BigDecimal(value.toString());
        } catch (NumberFormatException exception) {
            throw invalid("value must be a finite number for " + code);
        }
        if (decimal.compareTo(MAX_WHOLE_METRIC) > 0) {
            throw invalid("value must not be greater than 1000000000 for " + code);
        }
    }

    private static void validateCheckin(Map<String, Object> payload) {
        rejectUnknown(payload, CHECKIN_FIELDS);
        String category = requireText(payload, "category", 64);
        CheckinCategory.fromCode(category);
        Object score = payload.get("score");
        if (!(score instanceof Byte || score instanceof Short || score instanceof Integer || score instanceof Long)
                || ((Number) score).longValue() < 1 || ((Number) score).longValue() > 5) {
            throw invalid("score must be an integer from 1 to 5");
        }
    }

    private static void validateNote(Map<String, Object> payload) {
        rejectUnknown(payload, NOTE_FIELDS);
        requireText(payload, "text", 2000);
    }

    private static void rejectUnknown(Map<?, ?> payload, Set<String> allowed) {
        for (Object key : payload.keySet()) {
            if (!(key instanceof String field) || !allowed.contains(field)) {
                throw invalid("Unknown payload field: " + key);
            }
        }
    }

    private static String requireEnum(Map<String, Object> payload, String field, Set<String> allowed) {
        String value = requireText(payload, field, 64);
        if (!allowed.contains(value)) throw invalid("Unknown " + field);
        return value;
    }

    private static void validateEnum(Map<String, Object> payload, String field, Set<String> allowed,
                                     boolean nullable) {
        if (!payload.containsKey(field)) return;
        Object value = payload.get(field);
        if (value == null && nullable) return;
        if (!(value instanceof String text) || !allowed.contains(text)) throw invalid("Unknown " + field);
    }

    private static String requireText(Map<String, Object> payload, String field, int maxLength) {
        Object value = payload.get(field);
        if (!(value instanceof String text) || text.isBlank()
                || text.codePointCount(0, text.length()) > maxLength) {
            throw invalid(field + " must be a non-blank string of at most " + maxLength + " characters");
        }
        return text;
    }

    private static void validateOptionalText(Map<String, Object> payload, String field, int maxLength) {
        if (!payload.containsKey(field) || payload.get(field) == null) return;
        requireText(payload, field, maxLength);
    }

    private static Number requireNumber(Map<String, Object> payload, String field) {
        Object value = payload.get(field);
        if (!(value instanceof Number number)) throw invalid(field + " must be a number");
        return number;
    }

    private static void validateNonNegativeNumber(Map<?, ?> payload, String field, boolean nullable) {
        if (!payload.containsKey(field)) return;
        Object value = payload.get(field);
        if (value == null && nullable) return;
        if (!(value instanceof Number number) || new BigDecimal(number.toString()).signum() < 0) {
            throw invalid(field + " must be a non-negative number or null");
        }
    }

    private static void validateLocalDate(Object value) {
        if (value == null || value instanceof LocalDate) return;
        if (!(value instanceof String text)) throw invalid("local_date must be an ISO date or null");
        try {
            LocalDate.parse(text);
        } catch (DateTimeParseException exception) {
            throw invalid("local_date must be an ISO date or null");
        }
    }

    private static void validateLocalTime(Object value) {
        if (value == null) return;
        if (!(value instanceof String text)
                || !text.matches("^([01][0-9]|2[0-3]):[0-5][0-9](:[0-5][0-9])?$")) {
            throw invalid("local_time must be HH:mm, HH:mm:ss, or null");
        }
    }

    private static void validateFinite(Object value) {
        if (value instanceof Double doubleValue && !Double.isFinite(doubleValue)
                || value instanceof Float floatValue && !Float.isFinite(floatValue)) {
            throw invalid("Numeric values must be finite");
        }
        if (value instanceof Number number) {
            BigDecimal decimal;
            try {
                decimal = new BigDecimal(number.toString());
            } catch (NumberFormatException exception) {
                throw invalid("Invalid numeric value");
            }
            if (decimal.precision() > MAX_NUMERIC_DIGITS
                    || Math.abs((long) decimal.scale()) > MAX_NUMERIC_DIGITS) {
                throw invalid("Numeric precision and absolute scale must not exceed " + MAX_NUMERIC_DIGITS);
            }
            // API consumers use IEEE-754 numbers. Keep their finite range without
            // using double for sign checks (tiny negative decimals underflow to -0.0).
            if (!Double.isFinite(decimal.doubleValue())) {
                throw invalid("Numeric magnitude exceeds the finite API number range");
            }
        }
        if (value instanceof Map<?, ?> map) map.values().forEach(EntryPayloadValidator::validateFinite);
        if (value instanceof Collection<?> collection) collection.forEach(EntryPayloadValidator::validateFinite);
    }

    private static EntryValidationException invalid(String message) {
        return new EntryValidationException(message);
    }
}
