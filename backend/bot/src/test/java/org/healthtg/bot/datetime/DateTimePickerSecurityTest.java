package org.healthtg.bot.datetime;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.healthtg.core.dialog.DialogState;
import org.junit.jupiter.api.Test;
import java.net.*;
import java.nio.charset.StandardCharsets;
import java.time.*;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;

class DateTimePickerSecurityTest {
    final ZoneId zone=ZoneId.of("Europe/Vilnius");
    final DateTimePicker.Form full=new DateTimePicker.Form(null,null,true,false,false,"Дата ёжика 🙂");
    final DialogState state=new DialogState(UUID.randomUUID(),null,"text_clarification",DateTimePicker.rotate(Map.of()),7,Instant.EPOCH,"main:10");
    Map<String,String> params(DialogState s,ZoneId z){
        var uri=DateTimePicker.url(URI.create("https://example.test/app/?ignored=yes#old"),s,z,full);
        assertEquals("/app/datetime-picker.html",uri.getPath()); assertNull(uri.getQuery());
        var result=new HashMap<String,String>();
        for(String pair:uri.getRawFragment().split("&")){var p=pair.split("=",2);result.put(p[0],URLDecoder.decode(p[1],StandardCharsets.UTF_8));}
        return result;
    }
    String data(String date,String time) throws Exception {
        var node=new ObjectMapper().createObjectNode().put("v",1).put("token",params(state,zone).get("token")).put("revision",7).put("date",date);
        if(time!=null)node.put("time",time); return node.toString();
    }
    @Test void unknownFieldsStayEmptyAndTimezoneIsProfile(){var p=params(state,zone);assertEquals("",p.get("date"));assertEquals("",p.get("time"));assertEquals(zone.getId(),p.get("zone"));assertEquals(full.label(),p.get("label"));}
    @Test void acceptsExplicitSelectionWithFullPrecision() throws Exception {var s=DateTimePicker.parse(data("2026-10-06","14:30:10.123456789"),state,zone,full);assertEquals(LocalTime.parse("14:30:10.123456789"),s.time());}
    @Test void tokenBindsOwnerRevisionStepNonceAndProfileTimezone() throws Exception {
        var changed=List.of(new DialogState(UUID.randomUUID(),null,state.step(),state.context(),7,Instant.EPOCH,"main:10"),new DialogState(state.ownerId(),null,state.step(),state.context(),8,Instant.EPOCH,"main:10"),new DialogState(state.ownerId(),null,"food_clarify",state.context(),7,Instant.EPOCH,"main:10"),new DialogState(state.ownerId(),null,state.step(),DateTimePicker.rotate(state.context()),7,Instant.EPOCH,"main:10"));
        String raw=data("2026-10-06","14:30");for(var other:changed)assertThrows(IllegalArgumentException.class,()->DateTimePicker.parse(raw,other,zone,full));
        assertThrows(IllegalArgumentException.class,()->DateTimePicker.parse(raw,state,ZoneId.of("UTC"),full));
    }
    @Test void rejectsDstGapAndOverlapWithoutShifting() throws Exception {
        for(String date:List.of("2026-03-29","2026-10-25")){String raw=data(date,"03:30");assertThrows(IllegalArgumentException.class,()->DateTimePicker.parse(raw,state,zone,full));}
        assertEquals(Instant.parse("2026-03-29T01:30:00Z"),DateTimePicker.instant(LocalDate.parse("2026-03-29"),LocalTime.parse("04:30"),zone));
    }
    @Test void serverEnforcesLockedFields() throws Exception {
        var form=new DateTimePicker.Form("2026-10-05","12:00",true,true,true,"Known");
        assertThrows(IllegalArgumentException.class,()->DateTimePicker.parse(data("2026-10-06","12:00"),state,zone,form));
        assertThrows(IllegalArgumentException.class,()->DateTimePicker.parse(data("2026-10-05","13:00"),state,zone,form));
    }
    @Test void datesOnlyCannotInjectTimeAndMustBeReal() throws Exception {
        var form=new DateTimePicker.Form(null,null,false,false,false,"Metric");
        assertNull(DateTimePicker.parse(data("2026-10-05",null),state,zone,form).time());
        for(String date:List.of("2026-02-29","0000-01-01","2026-13-01","2026-10-5"))assertThrows(IllegalArgumentException.class,()->DateTimePicker.parse(data(date,null),state,zone,form));
        assertThrows(IllegalArgumentException.class,()->DateTimePicker.parse(data("2026-10-05","12:00"),state,zone,form));
    }
    @Test void rejectsTrailingJsonDuplicatesTypesAndOversizedPayload() throws Exception {
        String raw=data("2026-10-05","12:00");
        for(String invalid:List.of(raw+"{}",raw.replace("\"v\":1","\"v\":1,\"v\":1"),raw.replace("\"revision\":7","\"revision\":7.0"),raw.replace("\"v\":1","\"v\":\"1\""),"ё".repeat(513),"null","[]"))assertThrows(IllegalArgumentException.class,()->DateTimePicker.parse(invalid,state,zone,full));
    }
}
