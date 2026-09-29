package org.healthtg.bot.text;

import java.time.LocalDate;
import java.time.LocalTime;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/** The outcome of parsing only: never a stored entry status or a persistence command. */
public record TextParseResult(Outcome outcome, String originalText, ParsedData data, List<Issue> issues) {
    public TextParseResult {
        Objects.requireNonNull(outcome, "outcome");
        issues = List.copyOf(issues);
    }

    public enum Outcome { PARSED, NEEDS_CLARIFICATION, NOTE_SUGGESTED, REJECTED }

    public record Issue(String code, String field, String message) {
        public Issue {
            Objects.requireNonNull(code);
            Objects.requireNonNull(field);
            Objects.requireNonNull(message);
        }
    }

    /** Known fields only. An incomplete payload may intentionally fail a server payload schema. */
    public record ParsedData(String type, Map<String, Object> payload, Map<String, String> fieldOrigins,
                             LocalDate date, LocalTime time) {
        public ParsedData {
            Objects.requireNonNull(type);
            payload = Map.copyOf(payload);
            fieldOrigins = Map.copyOf(fieldOrigins);
        }

        public String sourceKind() { return "text"; }
    }
}
