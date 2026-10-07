package org.healthtg.bot;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.healthtg.bot.visual.FoodPhotoFlow;
import org.healthtg.bot.recognition.*;
import org.healthtg.bot.draft.DraftReviewFlow;
import org.healthtg.core.dialog.*;
import org.healthtg.core.entry.*;
import org.healthtg.core.file.*;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.*;
import java.math.BigDecimal;
import java.net.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.time.*;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;
import static org.mockito.AdditionalAnswers.delegatesTo;

/** Requirements at the boundary between Issue118 date selection and updated Health Watch. */
class DateTimePickerHealthSyncTest extends HealthWatchTestSupport {
    static final URI BASE=URI.create("https://example.test/app");
    @Override CoreBotFlow flow(EntryCoreService e,DialogStateService d,FileStorageService f){
        return new CoreBotFlow(users,e,d,Clock.fixed(NOW,ZoneOffset.UTC),BASE)
                .withPhotos(new FoodPhotoFlow(e,d,f,recognition,loader,new DraftReviewFlow(e,d,BASE),"FIXTURE").withImageClassSelection());
    }
    void restart(){context.close();reopen();}
    List<BotAction> startMetric(String code,String value,String unit){
        String json="""
            {"image_class":"watch_photo","metrics":[
            {"code":"%s","value":%s,"unit":"%s","local_date":null,"local_time":null,"qualifier":null,
            "field_origins":{"code":"extracted","value":%s,"unit":"extracted","local_date":null,"local_time":null,"qualifier":null}}],"ignored_labels":[]}
            """.formatted(code,value,unit,value.equals("null")?"null":"\"extracted\"");
        recognition=new FoodRecognitionService(new ImageValidator(),new FixtureRecognitionProvider(json),new RecognitionResponseParser());
        var choose=flow().handleMessage(photo(10));
        return flow().handleCallback(cb(11,button(choose,"Часы")));
    }
    String data(List<BotAction> actions){
        var uri=actions.stream().filter(BotAction.SendMessage.class::isInstance).map(BotAction.SendMessage.class::cast)
                .map(BotAction.SendMessage::keyboard).filter(Objects::nonNull).map(BotAction.ReplyKeyboard::pickerUrl)
                .filter(Objects::nonNull).findFirst().orElseThrow();
        var p=new HashMap<String,String>();
        for(String pair:uri.getRawFragment().split("&")){var v=pair.split("=",2);p.put(v[0],URLDecoder.decode(v[1],StandardCharsets.UTF_8));}
        return new ObjectMapper().createObjectNode().put("v",1).put("token",p.get("token"))
                .put("revision",Long.parseLong(p.get("revision"))).put("date","2026-10-06").toString();
    }
    BotUpdate selection(long id,String raw){return new BotUpdate(id,BotUpdate.Kind.MESSAGE,BotUpdate.ChatType.PRIVATE,1001,1001L,false,null,List.of(),null,null,NOW,null,raw);}
    void noCreate(List<BotAction> actions){
        assertTrue(entries.findActiveDraft(owner()).isEmpty());
        assertEquals(0,fileCount());
        assertFalse(actions.stream().filter(BotAction.SendInlineMessage.class::isInstance).map(BotAction.SendInlineMessage.class::cast)
                .flatMap(m->m.rows().stream()).flatMap(List::stream).anyMatch(b->b.text().equals("Создать черновик")));
    }
    void value(String expected){assertEquals(0,new BigDecimal(expected).compareTo(new BigDecimal(active().payload().get("value").toString())));}

    @ParameterizedTest @CsvSource({"h,ч,450,computed","min,мин,7.5,reported","unknown,ч,450,computed"})
    void sleepExplicitUnitThenPickerSurvivesRestartAndReplay(String modelUnit,String answer,String expected,String origin){
        noCreate(startMetric("sleep_duration_min","null",modelUnit));
        restart();noCreate(flow().handleMessage(msg(12,"7,5")));
        restart();var dateQuestion=flow().handleMessage(msg(13,answer));noCreate(dateQuestion);
        var choice=selection(14,data(dateQuestion));restart();var ready=flow().handleMessage(choice);
        assertTrue(entries.findActiveDraft(owner()).isEmpty());
        var saved=dialogs.find(owner()).orElseThrow();restart();var replay=flow().handleMessage(choice);
        assertEquals(saved,dialogs.find(owner()).orElseThrow());
        flow().handleCallback(cb(15,button(replay,"Создать черновик")));
        var draft=active();value(expected);assertEquals("min",draft.payload().get("unit"));
        assertEquals("2026-10-06",draft.payload().get("local_date"));assertEquals(NOW,draft.occurredAt());
        assertNull(draft.payload().get("local_time"));assertEquals(origin,draft.fieldOrigins().get("value"));
        assertEquals("reported",draft.fieldOrigins().get("local_date"));assertEquals(EntryStatus.DRAFT,draft.status());
        flow().handleMessage(choice);flow().handleCallback(cb(15,button(ready,"Создать черновик")));
        assertEquals(draft,active());assertEquals(1,fileCount());
    }

    @ParameterizedTest @ValueSource(strings={"0","1000000000"})
    void invalidStepsCannotSaveBeforeCorrectionAndPickerKeepsBoundary(String boundary){
        noCreate(startMetric("steps","1230.5","count"));
        noCreate(flow().handleMessage(msg(12,"-1")));
        noCreate(flow().handleMessage(msg(13,"1000000001")));
        noCreate(flow().handleMessage(msg(14,"1230,5")));
        restart();var dateQuestion=flow().handleMessage(msg(15,boundary));noCreate(dateQuestion);
        var choice=selection(16,data(dateQuestion));var ready=flow().handleMessage(choice);restart();
        flow().handleCallback(cb(17,button(ready,"Создать черновик")));var draft=active();
        value(boundary);assertEquals("2026-10-06",draft.payload().get("local_date"));assertEquals(NOW,draft.occurredAt());
        assertEquals(EntryStatus.DRAFT,draft.status());flow().handleMessage(choice);assertEquals(draft,active());
        assertEquals(1,fileCount());
    }

    @ParameterizedTest @ValueSource(booleans={false,true})
    void pickerThenLostFileIdSaveCanCancelReservedFileAcrossRestart(boolean failCleanup){
        var choice=selection(12,data(startMetric("steps","1000","count")));
        var ready=flow().handleMessage(choice);
        var broken=mock(DialogStateService.class,delegatesTo(dialogs));
        doAnswer(i->{SaveDialogStateCommand c=i.getArgument(0);
            if(c.updateKey().botKey().equals("main-food-stored"))throw new IllegalStateException("lost file id save");
            return dialogs.save(c);}).when(broken).save(any());
        var error=flow(entries,broken,files).handleCallback(cb(13,button(ready,"Создать черновик")));
        var state=dialogs.find(owner()).orElseThrow();UUID id=UUID.fromString((String)state.context().get("reserved_file_id"));
        assertFalse(state.context().containsKey("file_id"));assertEquals(1,fileCount());
        assertTrue(entries.findActiveDraft(owner()).isEmpty());
        var cleanup=mock(FileStorageService.class,delegatesTo(files));
        if(failCleanup)doThrow(new IllegalStateException("disk unavailable")).when(cleanup).discardUnreferenced(owner(),id);
        flow(entries,dialogs,cleanup).handleCallback(cb(14,button(error,"Отменить")));
        if(failCleanup)assertEquals("food_cancelling",dialogs.find(owner()).orElseThrow().step());
        restart();if(failCleanup)flow().handleMessage(msg(15,"продолжить"));
        assertEquals(0,fileCount());assertEquals("idle",dialogs.find(owner()).orElseThrow().step());
        assertFalse(Files.exists(root.resolve(id.toString().substring(0,2)).resolve(id+".bin")));
        flow().handleMessage(choice);flow().handleMessage(selection(16,choice.webAppData()));
        assertEquals(0,fileCount());assertTrue(entries.findActiveDraft(owner()).isEmpty());
        assertEquals("idle",dialogs.find(owner()).orElseThrow().step());
    }
}
