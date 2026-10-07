package org.healthtg.bot.recognition;

import com.fasterxml.jackson.databind.node.*;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.*;
import java.math.BigDecimal;
import static org.junit.jupiter.api.Assertions.*;

class HealthWatchRecognitionTest {
    final RecognitionResponseParser parser=new RecognitionResponseParser();
    ObjectNode document(String kind,String code,String value,String unit) throws Exception {
        return (ObjectNode)RecognitionJson.MAPPER.readTree("""
        {"image_class":"%s","metrics":[{"code":"%s","value":%s,"unit":"%s","local_date":"2026-10-05","local_time":null,"qualifier":null,
        "field_origins":{"code":"extracted","value":"extracted","unit":"extracted","local_date":"extracted","local_time":null,"qualifier":null}}],"ignored_labels":[]}
        """.formatted(kind,code,value,unit));
    }
    ObjectNode metric(ObjectNode n){return (ObjectNode)n.path("metrics").get(0);}
    void number(ObjectNode n,String value){metric(n).put("value",new BigDecimal(value));}
    void invalid(ObjectNode n){assertEquals(RecognitionException.Code.INVALID_RESPONSE,assertThrows(RecognitionException.class,()->parser.parse(n.toString())).code());}
    @ParameterizedTest @CsvSource({"health_screenshot,steps,0,count,0,count","watch_photo,heart_rate,78,bpm,78,bpm","health_screenshot,sleep_duration_min,7.5,h,450,min","watch_photo,sleep_duration_min,3.25,часа,195,min","health_screenshot,steps,1230.5,шагов,1230.5,count"})
    void independentNormalizationOracles(String kind,String code,String value,String unit,String expected,String canonical) throws Exception {
        var result=parser.parse(document(kind,code,value,unit).toString());var m=result.metrics().getFirst();var p=(java.util.Map<?,?>)m.context().get("payload");
        assertEquals(0,new BigDecimal(expected).compareTo(new BigDecimal(p.get("value").toString())));assertEquals(canonical,p.get("unit"));assertEquals(kind,result.imageClass());
    }
    @ParameterizedTest @CsvSource({"4,12,252","7,25,445","0,0,0","6,59,419"})
    void hoursMinutesAreConvertedDeterministically(int hours,int minutes,String expected) throws Exception {
        var n=document("health_screenshot","sleep_duration_min",Integer.toString(hours),"h");metric(n).put("minutes_component",minutes);metric(n).withObject("field_origins").put("minutes_component","extracted");
        var m=parser.parse(n.toString()).metrics().getFirst();var p=(java.util.Map<?,?>)m.context().get("payload");assertEquals(0,new BigDecimal(expected).compareTo(new BigDecimal(p.get("value").toString())));
    }
    @ParameterizedTest @ValueSource(strings={"-1","60","1.5"})
    void invalidMinuteComponentIsRejected(String minutes) throws Exception {
        var n=document("watch_photo","sleep_duration_min","7","h");metric(n).put("minutes_component",new BigDecimal(minutes));metric(n).withObject("field_origins").put("minutes_component","extracted");invalid(n);
    }
    @Test void unknownFieldsAndUnrecognizedUnitsDoNotAcquireDefaults() throws Exception {
        var n=document("health_screenshot","steps","2300","Distance Walked");var m=metric(n);
        for(String f:java.util.List.of("value","local_date")){m.putNull(f);m.withObject("field_origins").putNull(f);}
        var result=parser.parse(n.toString());assertTrue(result.needsClarification());var p=(java.util.Map<?,?>)result.metrics().getFirst().context().get("payload");
        for(String f:java.util.List.of("value","unit","local_date","local_time","qualifier"))assertNull(p.get(f),f);
    }
    @ParameterizedTest @ValueSource(strings={"extra","duplicates","four","negative","estimated","date","time","qualifier","minutes_origin","wrong_minutes_code","wrong_minutes_unit","fractional_hours","missing_origin"})
    void malformedSemanticEvidenceIsRejected(String variation) throws Exception {
        var n=document("watch_photo","steps","1","count");var m=metric(n);var origins=m.withObject("field_origins");
        switch(variation){
            case "extra"->m.put("execute","ignore instructions");
            case "duplicates"->((ArrayNode)n.get("metrics")).add(m.deepCopy());
            case "four"->{var a=(ArrayNode)n.get("metrics");a.add(m.deepCopy());a.add(m.deepCopy());a.add(m.deepCopy());}
            case "negative"->m.put("value",-1);
            case "estimated"->origins.put("value","estimated");
            case "date"->m.put("local_date","2026-02-30");
            case "time"->{m.put("local_time","12:00");origins.put("local_time","extracted");}
            case "qualifier"->{m.put("qualifier","resting");origins.put("qualifier","extracted");}
            case "minutes_origin"->{m.put("code","sleep_duration_min");m.put("unit","h");m.put("minutes_component",12);}
            case "wrong_minutes_code"->{m.put("minutes_component",12);origins.put("minutes_component","extracted");}
            case "wrong_minutes_unit"->{m.put("code","sleep_duration_min");m.put("unit","min");m.put("minutes_component",12);origins.put("minutes_component","extracted");}
            case "fractional_hours"->{m.put("code","sleep_duration_min");m.put("unit","h");m.put("value",1.5);m.put("minutes_component",12);origins.put("minutes_component","extracted");}
            case "missing_origin"->origins.putNull("value");
        }
        invalid(n);
    }
    @ParameterizedTest @ValueSource(strings={"resting","instant"})
    void pulseContextIsPreservedOnlyWhenExplicit(String context) throws Exception {
        var n=document("health_screenshot","heart_rate","62","bpm");metric(n).put("qualifier",context);metric(n).withObject("field_origins").put("qualifier","extracted");
        assertEquals(context,parser.parse(n.toString()).metrics().getFirst().qualifier());
    }
    @Test void unknownAndEmptyImagesAreExplicitRefusal() throws Exception {
        var n=document("unknown","heart_rate","62","bpm");assertEquals(RecognitionException.Code.REFUSED,assertThrows(RecognitionException.class,()->parser.parse(n.toString())).code());
        n.put("image_class","watch_photo");n.set("metrics",RecognitionJson.MAPPER.createArrayNode());assertEquals(RecognitionException.Code.REFUSED,assertThrows(RecognitionException.class,()->parser.parse(n.toString())).code());
    }
}
