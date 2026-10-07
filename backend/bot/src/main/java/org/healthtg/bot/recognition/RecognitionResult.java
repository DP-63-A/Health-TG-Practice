package org.healthtg.bot.recognition;

import java.math.BigDecimal;
import java.util.List;
import java.util.Map;
import com.fasterxml.jackson.annotation.JsonProperty;

/** A proposal, never a persisted Entry. Snake-case names follow existing wire contracts. */
public record RecognitionResult(String description, @JsonProperty("mass_g") BigDecimal massG, Nutrients nutrients,
        @JsonProperty("nutrients_basis") String nutrientsBasis, @JsonProperty("occurred_at") String occurredAt,
        @JsonProperty("local_date") String localDate, @JsonProperty("local_time") String localTime,
        @JsonProperty("field_origins") Map<String, String> fieldOrigins, @JsonProperty("missing_fields") List<String> missingFields) {
    public RecognitionResult {
        fieldOrigins = Map.copyOf(fieldOrigins);
        missingFields = List.copyOf(missingFields);
    }
    public record Nutrients(@JsonProperty("energy_kcal") BigDecimal energyKcal, @JsonProperty("protein_g") BigDecimal proteinG,
                            @JsonProperty("fat_g") BigDecimal fatG, @JsonProperty("carbs_g") BigDecimal carbsG) { }
    public boolean needsClarification() { return !missingFields.isEmpty(); }
}
