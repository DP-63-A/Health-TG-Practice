package org.healthtg.bot.recognition;

import java.math.BigDecimal;
import java.util.List;
import java.util.Map;
import com.fasterxml.jackson.annotation.JsonProperty;

/** A proposal, never a persisted Entry. Snake-case names follow existing wire contracts. */
public record RecognitionResult(String description, @JsonProperty("mass_g") BigDecimal massG, Nutrients nutrients,
        @JsonProperty("nutrients_basis") String nutrientsBasis, @JsonProperty("occurred_at") String occurredAt,
        @JsonProperty("local_date") String localDate, @JsonProperty("local_time") String localTime,
        @JsonProperty("field_origins") Map<String, String> fieldOrigins, @JsonProperty("missing_fields") List<String> missingFields,
        @JsonProperty("image_class") String imageClass, List<MetricCandidate> metrics,
        @JsonProperty("ignored_labels") List<String> ignoredLabels) {
    public RecognitionResult {
        fieldOrigins = Map.copyOf(fieldOrigins);
        missingFields = List.copyOf(missingFields);
        metrics = List.copyOf(metrics);
        ignoredLabels = List.copyOf(ignoredLabels);
    }
    public RecognitionResult(String description, BigDecimal massG, Nutrients nutrients, String nutrientsBasis,
                             String occurredAt, String localDate, String localTime, Map<String,String> fieldOrigins,
                             List<String> missingFields) {
        this(description, massG, nutrients, nutrientsBasis, occurredAt, localDate, localTime,
                fieldOrigins, missingFields, "food_photo", List.of(), List.of());
    }
    public record Nutrients(@JsonProperty("energy_kcal") BigDecimal energyKcal, @JsonProperty("protein_g") BigDecimal proteinG,
                            @JsonProperty("fat_g") BigDecimal fatG, @JsonProperty("carbs_g") BigDecimal carbsG) { }
    public boolean needsClarification() { return !missingFields.isEmpty(); }
}
