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
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.ValueSource;
import java.net.*;
import java.nio.charset.StandardCharsets;
import java.time.*;
import java.util.*;
import java.util.concurrent.atomic.AtomicBoolean;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;
import static org.mockito.AdditionalAnswers.delegatesTo;

class DateTimePickerFailureTest extends HealthWatchTestSupport {
    static final URI BASE=URI.create("https://example.test");
    @Override CoreBotFlow flow(EntryCoreService e,DialogStateService d,FileStorageService f){return new CoreBotFlow(users,e,d,Clock.fixed(NOW,ZoneOffset.UTC),BASE).withPhotos(new FoodPhotoFlow(e,d,f,recognition,loader,new DraftReviewFlow(e,d,BASE),"FIXTURE").withImageClassSelection());}
    String data(List<BotAction> actions,String date,String time){
        var uri=actions.stream().filter(BotAction.SendMessage.class::isInstance).map(BotAction.SendMessage.class::cast).map(BotAction.SendMessage::keyboard).filter(Objects::nonNull).map(BotAction.ReplyKeyboard::pickerUrl).filter(Objects::nonNull).findFirst().orElseThrow();
        var p=new HashMap<String,String>();for(String pair:uri.getRawFragment().split("&")){var v=pair.split("=",2);p.put(v[0],URLDecoder.decode(v[1],StandardCharsets.UTF_8));}
        var n=new ObjectMapper().createObjectNode().put("v",1).put("token",p.get("token")).put("revision",Long.parseLong(p.get("revision"))).put("date",date);if(time!=null)n.put("time",time);return n.toString();
    }
    BotUpdate selection(long id,String raw){return new BotUpdate(id,BotUpdate.Kind.MESSAGE,BotUpdate.ChatType.PRIVATE,1001,1001L,false,null,List.of(),null,null,NOW,null,raw);}
    void restart(){context.close();reopen();}
    @ParameterizedTest @ValueSource(booleans={false,true})
    void lostNoteReviewSaveRecoversSameEntry(boolean after){
        var offer=flow().handleMessage(msg(10,"Всё ещё устал"));var prompt=flow().handleCallback(cb(11,button(offer,"Создать заметку")));var u=selection(12,data(prompt,"2026-10-06","12:00"));
        var broken=mock(DialogStateService.class,delegatesTo(dialogs));doAnswer(i->{SaveDialogStateCommand c=i.getArgument(0);if(c.step().equals("draft_review")){if(after)dialogs.save(c);throw new IllegalStateException("lost review save");}return dialogs.save(c);}).when(broken).save(any());
        assertThrows(IllegalStateException.class,()->flow(entries,broken,files).handleMessage(u));var e=active();restart();flow().handleMessage(u);assertEquals(e,active());assertEquals("draft_review",dialogs.find(owner()).orElseThrow().step());
    }
    @ParameterizedTest @ValueSource(booleans={false,true})
    void lostFoodSelectionSaveIsSafeToRetry(boolean after){
        var choose=flow().handleMessage(photo(10));var prompt=flow().handleCallback(cb(11,button(choose,"Еда")));var u=selection(12,data(prompt,"2026-10-06","14:30"));
        var broken=mock(DialogStateService.class,delegatesTo(dialogs));doAnswer(i->{SaveDialogStateCommand c=i.getArgument(0);if(c.updateKey().botKey().equals("main-food-answer")){if(after)dialogs.save(c);throw new IllegalStateException("lost selection save");}return dialogs.save(c);}).when(broken).save(any());
        assertThrows(IllegalStateException.class,()->flow(entries,broken,files).handleMessage(u));restart();flow().handleMessage(u);assertTrue(entries.findActiveDraft(owner()).isEmpty());
        var ready=flow().handleMessage(msg(13,"не знаю"));flow().handleCallback(cb(14,button(ready,"Создать черновик")));assertEquals(LocalDateTime.parse("2026-10-06T14:30"),active().occurredAt().atZone(users.findOrCreate(1001).timezone()).toLocalDateTime());assertEquals(1,fileCount());
    }
    @ParameterizedTest @CsvSource({"create,false","create,true","bind,false","bind,true","review,false","review,true"})
    void foodCommitAfterPickerSurvivesPartialWriteAndOldWebAppReplay(String point,boolean after){
        var choose=flow().handleMessage(photo(10));var prompt=flow().handleCallback(cb(11,button(choose,"Еда")));var selection=selection(12,data(prompt,"2026-10-06","14:30"));flow().handleMessage(selection);
        var ready=flow().handleMessage(msg(13,"не знаю"));var commit=cb(14,button(ready,"Создать черновик"));
        var d=mock(DialogStateService.class,delegatesTo(dialogs));var e=mock(EntryCoreService.class,delegatesTo(entries));var f=mock(FileStorageService.class,delegatesTo(files));var hit=new AtomicBoolean();
        doAnswer(i->{SaveDialogStateCommand c=i.getArgument(0);if(point.equals("review")&&c.step().equals("draft_review")&&!hit.getAndSet(true)){if(after)dialogs.save(c);throw new IllegalStateException("review fault");}return dialogs.save(c);}).when(d).save(any());
        doAnswer(i->{if(point.equals("create")&&!hit.getAndSet(true)){if(after)entries.createDraft(i.getArgument(0));throw new IllegalStateException("create fault");}return entries.createDraft(i.getArgument(0));}).when(e).createDraft(any());
        doAnswer(i->{if(point.equals("bind")&&!hit.getAndSet(true)){if(after)files.bindToEntry(i.getArgument(0),i.getArgument(1),i.getArgument(2));throw new IllegalStateException("bind fault");}return files.bindToEntry(i.getArgument(0),i.getArgument(1),i.getArgument(2));}).when(f).bindToEntry(any(),any(),any());
        var recovery=flow(e,d,f).handleCallback(commit);assertTrue(hit.get());restart();var beforeReplay=dialogs.find(owner()).orElseThrow();flow().handleMessage(selection);assertEquals(beforeReplay,dialogs.find(owner()).orElseThrow());flow().handleCallback(commit);assertEquals(beforeReplay,dialogs.find(owner()).orElseThrow());
        if(!beforeReplay.step().equals("draft_review"))flow().handleCallback(cb(15,button(recovery,"Продолжить")));
        var entry=active();assertEquals(LocalDateTime.parse("2026-10-06T14:30"),entry.occurredAt().atZone(users.findOrCreate(1001).timezone()).toLocalDateTime());assertEquals(1,fileCount());assertEquals(EntryStatus.DRAFT,entry.status());assertEquals("draft_review",dialogs.find(owner()).orElseThrow().step());
    }
    @Test void preciseExistingEventTimeIsPreservedByUnchangedPickerSubmission(){
        var instant=LocalDateTime.parse("2026-10-06T12:34:56.123").atZone(users.findOrCreate(1001).timezone()).toInstant();
        var entry=entries.createDraft(new CreateDraftCommand(owner(),EntryType.NOTE,SourceKind.TEXT,Map.of(),instant,Map.of("text","ёжик"),Map.of(),new TelegramUpdateKey("main",10))).entry();
        var prompt=flow().handleCallback(cb(11,"dr:d:"+entry.id()+":"+entry.revision()));flow().handleMessage(selection(12,data(prompt,"2026-10-06","12:34:56.123")));assertEquals(instant,active().occurredAt());
    }
    @ParameterizedTest @ValueSource(booleans={false,true})
    void lostSelectionAcknowledgementReplaysActualNextQuestionOrCreateButton(boolean metric){
        if(metric){
            String json="""
            {"image_class":"health_screenshot","metrics":[
            {"code":"steps","value":1000,"unit":"count","local_date":null,"local_time":null,"qualifier":null,"field_origins":{"code":"extracted","value":"extracted","unit":"extracted","local_date":null,"local_time":null,"qualifier":null}}],"ignored_labels":[]}
            """;
            recognition=new FoodRecognitionService(new ImageValidator(),new FixtureRecognitionProvider(json),new RecognitionResponseParser());
        }
        var choose=flow().handleMessage(photo(10));
        var prompt=flow().handleCallback(cb(11,button(choose,metric?"Экран здоровья":"Еда")));
        var update=selection(12,data(prompt,"2026-10-06",metric?null:"14:30"));
        var broken=mock(DialogStateService.class,delegatesTo(dialogs));
        doAnswer(i->{SaveDialogStateCommand c=i.getArgument(0);var saved=dialogs.save(c);if(c.updateKey().botKey().equals("main-food-answer"))throw new IllegalStateException("selection saved but acknowledgement lost");return saved;}).when(broken).save(any());
        assertThrows(IllegalStateException.class,()->flow(entries,broken,files).handleMessage(update));
        var saved=dialogs.find(owner()).orElseThrow();restart();
        var replay=flow().handleMessage(update);
        assertEquals(saved,dialogs.find(owner()).orElseThrow());assertTrue(entries.findActiveDraft(owner()).isEmpty());
        if(metric){
            boolean hasCreate=replay.stream().filter(BotAction.SendInlineMessage.class::isInstance).map(BotAction.SendInlineMessage.class::cast)
                    .flatMap(m->m.rows().stream()).flatMap(List::stream).anyMatch(b->b.text().equals("Создать черновик"));
            assertTrue(hasCreate,"After lost date acknowledgement, actual returned actions must offer the current create button; the user has never received it");
            flow().handleCallback(cb(13,button(replay,"Создать черновик")));
            assertEquals("2026-10-06",active().payload().get("local_date"));
        } else {
            assertTrue(text(replay).contains("массу")&&text(replay).contains("не знаю"),
                    "After lost selection acknowledgement, repeat must show the current mass question and the allowed unknown answer, not refer to a lost earlier message");
            var ready=flow().handleMessage(msg(13,"не знаю"));flow().handleCallback(cb(14,button(ready,"Создать черновик")));
            assertEquals(LocalDateTime.parse("2026-10-06T14:30"),active().occurredAt().atZone(users.findOrCreate(1001).timezone()).toLocalDateTime());
        }
        assertEquals(EntryStatus.DRAFT,active().status());assertEquals(1,fileCount());
    }    @ParameterizedTest @ValueSource(booleans={false,true})
    void lostTextSelectionAcknowledgementReplaysCurrentUnitOrDayScopeQuestion(boolean steps){
        var prompt=flow().handleMessage(msg(10,steps?"1000 шагов":"пульс 72"));
        var update=selection(12,data(prompt,"2026-10-06",null));
        var broken=mock(DialogStateService.class,delegatesTo(dialogs));
        doAnswer(i->{SaveDialogStateCommand c=i.getArgument(0);var saved=dialogs.save(c);if(c.updateKey().storageKey().equals("main:12"))throw new IllegalStateException("text answer saved but reply lost");return saved;}).when(broken).save(any());
        assertThrows(IllegalStateException.class,()->flow(entries,broken,files).handleMessage(update));
        var saved=dialogs.find(owner()).orElseThrow();restart();
        var replay=flow().handleMessage(update);assertEquals(saved,dialogs.find(owner()).orElseThrow());
        assertTrue(text(replay).contains(steps?"итог за день":"единицу пульса"),"User must receive the actual next clarification, not a reference to a lost message");
        flow().handleMessage(msg(13,steps?"да":"bpm"));
        assertEquals("2026-10-06",active().payload().get("local_date"));assertEquals(NOW,active().occurredAt());assertEquals(EntryStatus.DRAFT,active().status());
    }
    @ParameterizedTest @ValueSource(booleans={false,true})
    void onlyExactAcceptedUpdateMayReplayCurrentPromptWithoutMutatingState(boolean photo){
        List<BotAction> prompt;
        if(photo){var choose=flow().handleMessage(photo(10));prompt=flow().handleCallback(cb(11,button(choose,"Еда")));}
        else prompt=flow().handleMessage(msg(10,"пульс 72"));
        String accepted=data(prompt,"2026-10-06",photo?"14:30":null);
        flow().handleMessage(selection(12,accepted));var saved=dialogs.find(owner()).orElseThrow();restart();
        String nextQuestion=photo?"массу":"единицу пульса";
        var tampered=flow().handleMessage(selection(12,accepted.replace("2026-10-06","2026-10-05")));
        assertEquals(saved,dialogs.find(owner()).orElseThrow());assertFalse(text(tampered).contains(nextQuestion),"Same ID with a changed payload is not an acknowledgement retry");
        var newUpdate=flow().handleMessage(selection(13,accepted));
        assertEquals(saved,dialogs.find(owner()).orElseThrow());assertFalse(text(newUpdate).contains(nextQuestion),"Same payload under another update ID must not bypass stale token validation");
        var exact=flow().handleMessage(selection(12,accepted));
        assertEquals(saved,dialogs.find(owner()).orElseThrow());assertTrue(text(exact).contains(nextQuestion));assertTrue(entries.findActiveDraft(owner()).isEmpty());
    }}
