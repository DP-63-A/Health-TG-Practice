package org.healthtg.bot.recognition;

import java.math.BigDecimal;
import java.util.*;

/** Raw visual evidence stays separate from deterministic unit conversion. */
public record MetricCandidate(String code, BigDecimal value, String unit, String localDate,
                              String localTime, String qualifier, Map<String,String> fieldOrigins, BigDecimal minutesComponent) {
    public MetricCandidate { fieldOrigins = Map.copyOf(fieldOrigins); }
    public MetricCandidate(String code,BigDecimal value,String unit,String localDate,String localTime,String qualifier,Map<String,String> origins) {
        this(code,value,unit,localDate,localTime,qualifier,origins,null);
    }

    public record Normalized(BigDecimal value, String unit, boolean converted) { }

    public static void validateValue(String code, BigDecimal value) {
        if (value.signum() < 0 || value.compareTo(BigDecimal.valueOf(1_000_000_000L)) > 0
                || ("steps".equals(code) && value.stripTrailingZeros().scale() > 0))
            throw new IllegalArgumentException("Metric value is outside its allowed range");
    }

    public static Normalized normalize(String code, BigDecimal value, String rawUnit) {
        if (value != null && (value.signum() < 0 || value.precision() > 2000
                || Math.abs((long)value.scale()) > 2000 || !Double.isFinite(value.doubleValue())))
            throw new IllegalArgumentException("Invalid metric number");
        if (rawUnit == null) return new Normalized(value, null, false);
        String u = rawUnit.strip().toLowerCase(Locale.ROOT);
        String canonical;
        BigDecimal factor = BigDecimal.ONE;
        switch (code) {
            case "steps" -> { if (!Set.of("count", "steps", "step", "шаг", "шага", "шаги", "шагов").contains(u))
                return new Normalized(value,null,false); canonical = "count"; }
            case "heart_rate" -> { if (!Set.of("bpm", "уд/мин", "уд./мин", "beats/min").contains(u))
                return new Normalized(value,null,false); canonical = "bpm"; }
            case "sleep_duration_min" -> {
                canonical = "min";
                if (Set.of("h", "hr", "hours", "hour", "ч", "час", "часа", "часов").contains(u)) factor = BigDecimal.valueOf(60);
                else if (!Set.of("min", "minutes", "minute", "мин", "минут", "минуты").contains(u))
                    return new Normalized(value,null,false);
            }
            default -> throw new IllegalArgumentException("Unsupported metric");
        }
        BigDecimal normalized = value == null ? null : value.multiply(factor);
        if (normalized != null && !Double.isFinite(normalized.doubleValue())) throw new IllegalArgumentException("Overflow");
        return new Normalized(normalized,canonical,!factor.equals(BigDecimal.ONE));
    }

    /** Persistence-safe map: decimals are strings until Entry creation (Mongo does not choose their precision). */
    public Map<String,Object> context() {
        var normalized = normalize(code,value,unit);
        if(minutesComponent!=null) {
            if(!code.equals("sleep_duration_min") || !normalized.converted() || value==null
                    || value.stripTrailingZeros().scale()>0 || minutesComponent.signum()<0
                    || minutesComponent.compareTo(BigDecimal.valueOf(60))>=0 || minutesComponent.stripTrailingZeros().scale()>0)
                throw new IllegalArgumentException("Invalid hours and minutes");
            normalized=new Normalized(normalized.value().add(minutesComponent),normalized.unit(),true);
        }
        // Keep an invalid model number as evidence, but ask the user instead of rounding it.
        if (normalized.value() != null) {
            try { validateValue(code, normalized.value()); }
            catch (IllegalArgumentException invalid) {
                normalized = new Normalized(null, normalized.unit(), false);
            }
        }
        // A missing sleep value has not been converted. The unit of the later answer is unknown.
        if (code.equals("sleep_duration_min") && normalized.value() == null)
            normalized = new Normalized(null, null, false);
        var p = new LinkedHashMap<String,Object>();
        p.put("code",code); p.put("value",normalized.value()==null?null:normalized.value().toPlainString());
        p.put("unit",normalized.unit()); p.put("local_date",localDate); p.put("local_time",localTime); p.put("qualifier",qualifier);
        var origins = new LinkedHashMap<String,Object>(fieldOrigins);
        if (normalized.value()==null) origins.remove("value");
        if (normalized.unit()==null) origins.remove("unit");
        else if (!Objects.equals(unit,normalized.unit())) origins.put("unit","computed");
        if (normalized.converted() && value!=null) origins.put("value","computed");
        var data = new LinkedHashMap<String,Object>(); data.put("payload",p); data.put("origins",origins);
        var evidence = new LinkedHashMap<String,Object>();
        evidence.put("value",value==null?null:value.toPlainString()); evidence.put("unit",unit);
        if(minutesComponent!=null) evidence.put("minutes_component",minutesComponent.toPlainString());
        data.put("raw_metric",evidence);
        return data;
    }
}
