package org.healthtg.bot;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.healthtg.bot.visual.FoodPhotoFlow;
import org.healthtg.bot.recognition.*;
import org.healthtg.bot.draft.DraftReviewFlow;
import org.healthtg.core.dialog.*;
import org.healthtg.core.entry.*;
import org.healthtg.core.file.*;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.dao.DataAccessResourceFailureException;
import java.net.*;
import java.nio.charset.StandardCharsets;
import java.time.*;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;
import static org.mockito.AdditionalAnswers.delegatesTo;

/** Independent Issue118 acceptance: real Mongo and file storage; only recognition is a fixture. */
class DateTimePickerStorageTest extends HealthWatchTestSupport {
    static final URI BASE=URI.create("https://example.test/app");
    @Override CoreBotFlow flow(EntryCoreService e,DialogStateService d,FileStorageService f){return new CoreBotFlow(users,e,d,Clock.fixed(NOW,ZoneOffset.UTC),BASE).withPhotos(new FoodPhotoFlow(e,d,f,recognition,loader,new DraftReviewFlow(e,d,BASE),"FIXTURE").withImageClassSelection());}
    Map<String,String> picker(List<BotAction> actions){
        var uri=actions.stream().filter(BotAction.SendMessage.class::isInstance).map(BotAction.SendMessage.class::cast).map(BotAction.SendMessage::keyboard).filter(Objects::nonNull).map(BotAction.ReplyKeyboard::pickerUrl).filter(Objects::nonNull).findFirst().orElseThrow();
        var p=new HashMap<String,String>();for(String pair:uri.getRawFragment().split("&")){var v=pair.split("=",2);p.put(v[0],URLDecoder.decode(v[1],StandardCharsets.UTF_8));}return p;
    }
    String data(Map<String,String> p,String date,String time){var n=new ObjectMapper().createObjectNode().put("v",1).put("token",p.get("token")).put("revision",Long.parseLong(p.get("revision"))).put("date",date);if(time!=null)n.put("time",time);return n.toString();}
    BotUpdate selection(long id,String raw){return new BotUpdate(id,BotUpdate.Kind.MESSAGE,BotUpdate.ChatType.PRIVATE,1001,1001L,false,null,List.of(),null,null,NOW,null,raw);}
    void restart(){context.close();reopen();}
    List<BotAction> note(){var offer=flow().handleMessage(msg(10,"Всё ещё устал"));return flow().handleCallback(cb(11,button(offer,"Создать заметку")));}
    List<BotAction> editDate(long id){var e=active();return flow().handleCallback(cb(id,"dr:d:"+e.id()+":"+e.revision()));}
    @Test void noteBeforeEntrySurvivesRestartAndReplayThenConfirmsExactlyOnce(){
        var p=picker(note());assertEquals("",p.get("date"));assertEquals("",p.get("time"));assertTrue(entries.findActiveDraft(owner()).isEmpty());
        var u=selection(12,data(p,"2026-10-06","14:30"));restart();flow().handleMessage(u);var draft=active();
        assertEquals(EntryStatus.DRAFT,draft.status());assertEquals("Всё ещё устал",draft.payload().get("text"));assertEquals(LocalDateTime.parse("2026-10-06T14:30"),draft.occurredAt().atZone(users.findOrCreate(1001).timezone()).toLocalDateTime());assertEquals("reported",draft.fieldOrigins().get("occurred_at"));
        flow().handleMessage(u);flow().handleMessage(selection(13,u.webAppData()));assertEquals(draft,active());
        flow().handleCallback(cb(14,"dr:s:"+draft.id()+":"+draft.revision()));assertEquals(1,entries.listEntries(new ListEntriesQuery(owner(),EntryStatus.CONFIRMED,EntryType.NOTE,null,null,ZoneOffset.UTC)).size());
    }
    @Test void manualDateInvalidatesOldWindowAndLocksKnownDate(){
        var old=picker(note());var current=picker(flow().handleMessage(msg(12,"06.10.2026")));var state=dialogs.find(owner()).orElseThrow();
        flow().handleMessage(selection(13,data(old,"2026-10-05","12:00")));assertEquals(state,dialogs.find(owner()).orElseThrow());
        assertEquals("true",current.get("dateLocked"));flow().handleMessage(selection(14,data(current,"2026-10-05","12:00")));assertEquals(state,dialogs.find(owner()).orElseThrow());
        flow().handleMessage(selection(15,data(current,"2026-10-06","12:00")));assertEquals(LocalDateTime.parse("2026-10-06T12:00"),active().occurredAt().atZone(users.findOrCreate(1001).timezone()).toLocalDateTime());
    }
    @Test void foreignOwnerCannotApplyOtherOwnersWindow(){
        var p=picker(note());var original=dialogs.find(owner()).orElseThrow();
        var foreign=new BotUpdate(12,BotUpdate.Kind.MESSAGE,BotUpdate.ChatType.PRIVATE,2002,2002L,false,null,List.of(),null,null,NOW,null,data(p,"2026-10-06","12:00"));
        flow().handleMessage(foreign);assertEquals(original,dialogs.find(owner()).orElseThrow());assertTrue(entries.findActiveDraft(new OwnerContext(users.findOrCreate(2002).id())).isEmpty());
    }
    @Test void metricDateDoesNotInventTimeOrReplaceMessageTimestamp(){
        var p=picker(flow().handleMessage(msg(10,"пульс 72 ударов в минуту")));assertEquals("false",p.get("withTime"));
        flow().handleMessage(selection(11,data(p,"2026-10-06",null)));var e=active();assertEquals("2026-10-06",e.payload().get("local_date"));assertEquals(NOW,e.occurredAt());assertNull(e.payload().get("local_time"));assertEquals("reported",e.fieldOrigins().get("local_date"));
    }
    @ParameterizedTest @ValueSource(booleans={false,true})
    void noteLostCreateResponseRecoversWithoutDuplicate(boolean after){
        var p=picker(note());var u=selection(12,data(p,"2026-10-06","12:00"));
        var broken=mock(EntryCoreService.class,delegatesTo(entries));doAnswer(i->{if(after)entries.createDraft(i.getArgument(0));throw new DataAccessResourceFailureException("simulated");}).when(broken).createDraft(any());
        assertThrows(DataAccessResourceFailureException.class,()->flow(broken,dialogs,files).handleMessage(u));restart();flow().handleMessage(u);var e=active();flow().handleMessage(u);assertEquals(e,active());assertEquals(EntryStatus.DRAFT,e.status());
    }
    @ParameterizedTest @ValueSource(booleans={false,true})
    void pendingDraftPatchSurvivesFailureBeforeOrAfterStorage(boolean after){
        flow().handleMessage(msg(10,"06.10.2026 пульс 72 ударов в минуту"));var old=active();var p=picker(editDate(11));var u=selection(12,data(p,"2026-10-05",null));
        var broken=mock(EntryCoreService.class,delegatesTo(entries));doAnswer(i->{if(after)entries.patch(i.getArgument(0));throw new DataAccessResourceFailureException("simulated");}).when(broken).patch(any());
        assertThrows(DataAccessResourceFailureException.class,()->flow(broken,dialogs,files).handleMessage(u));restart();flow().handleMessage(u);flow().handleMessage(u);
        var e=active();assertEquals(old.id(),e.id());assertEquals(2,e.revision());assertEquals("2026-10-05",e.payload().get("local_date"));assertEquals(old.occurredAt(),e.occurredAt());assertEquals(old.payload().get("value"),e.payload().get("value"));assertEquals(EntryStatus.DRAFT,e.status());
    }
    @Test void externalRevisionCannotBeOverwrittenByStaleWindow(){
        flow().handleMessage(msg(10,"06.10.2026 пульс 72 ударов в минуту"));var p=picker(editDate(11));var e=active();var payload=new LinkedHashMap<>(e.payload());payload.put("value",85);
        entries.patch(new PatchEntryCommand(owner(),e.id(),e.revision(),null,payload,e.fieldOrigins()));var changed=active();
        flow().handleMessage(selection(12,data(p,"2026-10-05",null)));assertEquals(changed,active());
    }
    @Test void noteDraftPickerChangesBothFieldsRetainingIdAndPayload(){
        flow().handleMessage(selection(12,data(picker(note()),"2026-10-06","12:00")));var old=active();
        var p=picker(editDate(13));assertEquals("2026-10-06",p.get("date"));flow().handleMessage(selection(14,data(p,"2026-10-05","15:45")));
        var e=active();assertEquals(old.id(),e.id());assertEquals(old.payload(),e.payload());assertEquals(2,e.revision());assertEquals(LocalDateTime.parse("2026-10-05T15:45"),e.occurredAt().atZone(users.findOrCreate(1001).timezone()).toLocalDateTime());
    }
    @Test void foodPickerLeavesMassQuestionAndPreservesSelectionAcrossRestart(){
        var choose=flow().handleMessage(photo(10));var p=picker(flow().handleCallback(cb(11,button(choose,"Еда"))));
        var response=flow().handleMessage(selection(12,data(p,"2026-10-06","14:30")));assertTrue(entries.findActiveDraft(owner()).isEmpty());assertTrue(text(response).contains("масс"));
        restart();var ready=flow().handleMessage(msg(13,"не знаю"));flow().handleCallback(cb(14,button(ready,"Создать черновик")));
        var e=active();assertEquals(EntryType.MEAL,e.type());assertEquals(LocalDateTime.parse("2026-10-06T14:30"),e.occurredAt().atZone(users.findOrCreate(1001).timezone()).toLocalDateTime());assertEquals(1,fileCount());assertEquals(1,calls.get());
    }
    @ParameterizedTest @ValueSource(strings={"health_screenshot","watch_photo"})
    void metricQueueRejectsPreviousCandidatesWindow(String kind){
        String json="""
        {"image_class":"%s","metrics":[
        {"code":"steps","value":1000,"unit":"count","local_date":null,"local_time":null,"qualifier":null,"field_origins":{"code":"extracted","value":"extracted","unit":"extracted","local_date":null,"local_time":null,"qualifier":null}},
        {"code":"heart_rate","value":72,"unit":"bpm","local_date":null,"local_time":null,"qualifier":null,"field_origins":{"code":"extracted","value":"extracted","unit":"extracted","local_date":null,"local_time":null,"qualifier":null}}],"ignored_labels":[]}
        """.formatted(kind);
        recognition=new FoodRecognitionService(new ImageValidator(),new FixtureRecognitionProvider(json),new RecognitionResponseParser());
        var choose=flow().handleMessage(photo(10));var p=picker(flow().handleCallback(cb(11,button(choose,kind.equals("watch_photo")?"Часы":"Экран здоровья"))));
        assertEquals("День итога шагов",p.get("label"));var ready=flow().handleMessage(selection(12,data(p,"2026-10-06",null)));
        var card=flow().handleCallback(cb(13,button(ready,"Создать черновик")));var first=active();assertEquals(NOW,first.occurredAt());assertNull(first.payload().get("local_time"));
        var next=picker(flow().handleCallback(cb(14,button(card,"Сохранить"))));var pending=dialogs.find(owner()).orElseThrow();
        flow().handleMessage(selection(15,data(p,"2026-10-01",null)));assertEquals(pending,dialogs.find(owner()).orElseThrow());
        restart();ready=flow().handleMessage(selection(16,data(next,"2026-10-05",null)));flow().handleCallback(cb(17,button(ready,"Создать черновик")));
        assertEquals("2026-10-05",active().payload().get("local_date"));assertEquals("heart_rate",active().payload().get("code"));assertNotEquals(first.id(),active().id());assertEquals(2,fileCount());
    }
    @Test void cancellationMakesOldWindowHarmlessForNewDialog(){
        var prompt=note();var old=picker(prompt);flow().handleCallback(cb(12,button(prompt,"Отменить ввод")));
        var offer=flow().handleMessage(msg(13,"Другая заметка"));flow().handleCallback(cb(14,button(offer,"Создать заметку")));var fresh=dialogs.find(owner()).orElseThrow();
        flow().handleMessage(selection(15,data(old,"2026-10-06","12:00")));assertEquals(fresh,dialogs.find(owner()).orElseThrow());assertTrue(entries.findActiveDraft(owner()).isEmpty());
    }
    @Test void textMealRetainsKnownTimeWhilePickerFillsDate(){
        var p=picker(flow().handleMessage(msg(10,"в 13:30 съел пасту, 200 г")));assertEquals("13:30",p.get("time"));assertEquals("true",p.get("timeLocked"));var before=dialogs.find(owner()).orElseThrow();
        flow().handleMessage(selection(11,data(p,"2026-10-06","14:00")));assertEquals(before,dialogs.find(owner()).orElseThrow());
        flow().handleMessage(selection(12,data(p,"2026-10-06","13:30")));assertEquals(EntryType.MEAL,active().type());assertEquals(LocalDateTime.parse("2026-10-06T13:30"),active().occurredAt().atZone(users.findOrCreate(1001).timezone()).toLocalDateTime());
    }    @Test void sleepPickerSelectsWakeDateAndPreservesExtractedTimeAndOriginalTimestamp(){
        String json="""
        {"image_class":"watch_photo","metrics":[
        {"code":"sleep_duration_min","value":420,"unit":"min","local_date":null,"local_time":"07:30","qualifier":null,"field_origins":{"code":"extracted","value":"extracted","unit":"extracted","local_date":null,"local_time":"extracted","qualifier":null}}],"ignored_labels":[]}
        """;
        recognition=new FoodRecognitionService(new ImageValidator(),new FixtureRecognitionProvider(json),new RecognitionResponseParser());
        var choose=flow().handleMessage(photo(10));var p=picker(flow().handleCallback(cb(11,button(choose,"Часы"))));
        assertEquals("Дата пробуждения",p.get("label"));assertEquals("false",p.get("withTime"));
        var ready=flow().handleMessage(selection(12,data(p,"2026-10-06",null)));restart();flow().handleCallback(cb(13,button(ready,"Создать черновик")));
        var sleep=active();assertEquals("sleep_duration_min",sleep.payload().get("code"));assertEquals(420,((Number)sleep.payload().get("value")).intValue());
        assertEquals("2026-10-06",sleep.payload().get("local_date"));assertEquals("07:30",sleep.payload().get("local_time"));assertEquals(NOW,sleep.occurredAt());
        assertEquals("reported",sleep.fieldOrigins().get("local_date"));assertEquals("extracted",sleep.fieldOrigins().get("local_time"));assertEquals(EntryStatus.DRAFT,sleep.status());
    }}
