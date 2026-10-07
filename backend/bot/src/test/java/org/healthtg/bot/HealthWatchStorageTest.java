package org.healthtg.bot;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.networknt.schema.*;
import org.healthtg.bot.recognition.*;
import org.healthtg.core.dialog.*;
import org.healthtg.core.entry.*;
import org.healthtg.core.file.*;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.*;
import org.springframework.data.mongodb.core.MongoTemplate;
import java.math.BigDecimal;
import java.nio.file.*;
import java.util.*;
import java.util.concurrent.atomic.AtomicBoolean;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;
import static org.mockito.AdditionalAnswers.delegatesTo;

/** Independent requirements tests using real Mongo, core services and disk-backed files. */
class HealthWatchStorageTest extends HealthWatchTestSupport {
    static final ObjectMapper JSON=new ObjectMapper();
    String metricJson(String kind) {
        return """
            {"image_class":"%s","metrics":[
              {"code":"steps","value":1230,"unit":"count","local_date":"2026-10-05","local_time":null,"qualifier":null,"field_origins":{"code":"extracted","value":"extracted","unit":"extracted","local_date":"extracted","local_time":null,"qualifier":null}},
              {"code":"sleep_duration_min","value":4,"minutes_component":12,"unit":"h","local_date":"2026-10-05","local_time":null,"qualifier":null,"field_origins":{"code":"extracted","value":"extracted","minutes_component":"extracted","unit":"extracted","local_date":"extracted","local_time":null,"qualifier":null}},
              {"code":"heart_rate","value":48,"unit":"bpm","local_date":"2026-10-05","local_time":null,"qualifier":null,"field_origins":{"code":"extracted","value":"extracted","unit":"extracted","local_date":"extracted","local_time":null,"qualifier":null}}
            ],"ignored_labels":["Температура Ёжик 🌡"]}
            """.formatted(kind);
    }
    void useJson(String json) {
        recognition=new FoodRecognitionService(new ImageValidator(),new RecognitionProvider(){
            public Mode mode(){return Mode.FIXTURE;}
            public Response recognize(ImageValidator.ValidatedImage image){calls.incrementAndGet();return new Response(json,"synthetic23",null);}
        },new RecognitionResponseParser());
    }
    List<BotAction> start(String kind) {
        useJson(metricJson(kind));
        var select=flow().handleMessage(photo(10));assertEquals(0,calls.get());
        return flow().handleCallback(cb(11,button(select,kind.equals("watch_photo")?"Часы":"Экран здоровья")));
    }
    List<BotAction> create(List<BotAction> actions,long update){return flow().handleCallback(cb(update,button(actions,"Создать черновик")));}
    void restart(){context.close();reopen();}
    void value(Entry entry,String value){assertEquals(0,new BigDecimal(value).compareTo(new BigDecimal(entry.payload().get("value").toString())));}
    void contract(Entry entry) throws Exception {
        var n=JSON.createObjectNode();n.put("id",entry.id().toString());n.put("user_id",entry.ownerId().toString());
        n.put("type",entry.type().name().toLowerCase(Locale.ROOT));n.put("status",entry.status().name().toLowerCase(Locale.ROOT));
        n.put("source_kind",entry.sourceKind().name().toLowerCase(Locale.ROOT));n.set("source_ref",JSON.valueToTree(entry.sourceRef()));
        n.put("occurred_at",entry.occurredAt().toString());n.put("created_at",entry.createdAt().toString());n.put("updated_at",entry.updatedAt().toString());
        n.put("revision",entry.revision());n.set("payload",JSON.valueToTree(entry.payload()));n.set("field_origins",JSON.valueToTree(entry.fieldOrigins()));n.put("submission_id",entry.submissionId());
        Path schemas=Path.of(System.getProperty("contracts.root"),"schemas");String prefix=schemas.toUri().toString();if(!prefix.endsWith("/"))prefix+="/";
        final String local=prefix;
        var factory=JsonSchemaFactory.getInstance(SpecVersion.VersionFlag.V202012,b->b.schemaMappers(m->m.mapPrefix("https://health-tg.local/schemas/",local)));
        Path file=schemas.resolve("entry.json");var schema=factory.getSchema(file.toUri(),JSON.readTree(Files.readString(file)),SchemaValidatorsConfig.builder().formatAssertionsEnabled(true).build());
        assertTrue(schema.validate(n).isEmpty(),()->schema.validate(n).toString());
        n.withObject("source_ref").put("forbidden",true);assertFalse(schema.validate(n).isEmpty(),"Actual source_ref contract must be active");
    }
    @ParameterizedTest @ValueSource(strings={"health_screenshot","watch_photo"})
    void threeMetricsHaveSeparateProtectedCopiesAndOneRecognition(String kind) throws Exception {
        var actions=start(kind);assertTrue(text(actions).contains("Температура Ёжик 🌡"));
        var ids=new HashSet<UUID>();var fileIds=new HashSet<String>();long update=12;
        for(String expected:List.of("1230","252","48")) {
            restart();var card=create(actions,update++);var e=active();ids.add(e.id());fileIds.add((String)e.sourceRef().get("file_id"));
            value(e,expected);assertEquals(NOW,e.occurredAt());assertEquals("2026-10-05",e.payload().get("local_date"));
            assertNull(e.payload().get("local_time"));assertNull(e.payload().get("qualifier"));
            assertEquals(SourceKind.valueOf(kind.toUpperCase(Locale.ROOT)),e.sourceKind());contract(e);
            UUID file=UUID.fromString((String)e.sourceRef().get("file_id"));
            try(var content=files.open(owner(),file)){assertArrayEquals(png,content.content().readAllBytes());}
            assertThrows(StoredFileNotFoundException.class,()->files.open(new OwnerContext(users.findOrCreate(2002).id()),file));
            actions=flow().handleCallback(cb(update++,button(card,"Сохранить")));contract(entries.requireEntry(owner(),e.id()));
        }
        assertEquals(3,ids.size());assertEquals(3,fileIds.size());assertEquals(3,fileCount());assertEquals(1,calls.get());
        assertTrue(entries.findActiveDraft(owner()).isEmpty());assertEquals("idle",dialogs.find(owner()).orElseThrow().step());
    }
    @ParameterizedTest @ValueSource(strings={"confirm","cancel","delete"})
    void externalTerminalAndRestartAdvanceWithoutApplyingOldButtonToNext(String terminal) {
        var card=create(start("watch_photo"),12);Entry first=active();String old=button(card,"Сохранить");
        if(terminal.equals("confirm"))entries.confirm(new ConfirmEntryCommand(owner(),first.id(),"external",first.revision()));
        else if(terminal.equals("cancel"))entries.cancel(owner(),first.id(),first.revision());
        else {var e=entries.confirm(new ConfirmEntryCommand(owner(),first.id(),"external",first.revision()));entries.delete(owner(),e.id(),e.revision());}
        restart();var next=flow().handleCallback(cb(13,old));assertTrue(entries.findActiveDraft(owner()).isEmpty());
        var secondCard=create(next,14);Entry second=active();assertNotEquals(first.id(),second.id());value(second,"252");
        flow().handleCallback(cb(15,old));assertEquals(second.id(),active().id());assertEquals(EntryStatus.DRAFT,active().status());assertEquals(2,fileCount());assertEquals(1,calls.get());
        var unchanged=dialogs.find(owner()).orElseThrow();flow().handleCallback(cb(14,button(secondCard,"Сохранить")));assertEquals(unchanged,dialogs.find(owner()).orElseThrow());
    }
    @Test void skipAndGlobalCancellationPreserveConfirmedRecordsAndAllowNewText() {
        var first=start("health_screenshot");var next=flow().handleCallback(cb(12,button(first,"Пропустить показатель")));
        var card=create(next,13);Entry sleep=active();var pulse=flow().handleCallback(cb(14,button(card,"Сохранить")));
        card=create(pulse,15);Entry heart=active();flow().handleCallback(cb(16,button(card,"Отменить оставшиеся показатели")));
        assertEquals(EntryStatus.CONFIRMED,entries.requireEntry(owner(),sleep.id()).status());assertEquals(EntryStatus.CANCELLED,entries.requireEntry(owner(),heart.id()).status());
        assertEquals(2,fileCount());assertEquals(1,calls.get());flow().beginCheckin(msg(17,"/state"));assertEquals("checkin_category",dialogs.find(owner()).orElseThrow().step());
    }
    @ParameterizedTest @CsvSource({"reserve,false","reserve,true","store,false","store,true","stored,false","stored,true","create,false","create,true","created,false","created,true","bind,false","bind,true","review,false","review,true"})
    void partialCommitFailureRecoversStableFileAndRetainsQueue(String point,boolean after) {
        var ready=start("health_screenshot");
        var d=mock(DialogStateService.class,delegatesTo(dialogs));var e=mock(EntryCoreService.class,delegatesTo(entries));var f=mock(FileStorageService.class,delegatesTo(files));var once=new AtomicBoolean();
        Map<String,String> phases=Map.of("reserve","main-food-file-reserved","stored","main-food-stored","created","main-food-created","review","main");
        doAnswer(i->{SaveDialogStateCommand c=i.getArgument(0);boolean fail=Objects.equals(phases.get(point),c.updateKey().botKey())&&!once.getAndSet(Objects.equals(phases.get(point),c.updateKey().botKey())||once.get());if(fail&&!after)throw new IllegalStateException("before");var r=dialogs.save(c);if(fail)throw new IllegalStateException("after");return r;}).when(d).save(any());
        doAnswer(i->{boolean fail=point.equals("store")&&!once.getAndSet(true);if(fail&&!after)throw new IllegalStateException("before");var r=files.store(i.getArgument(0),i.getArgument(1),i.getArgument(2),i.getArgument(3));if(fail)throw new IllegalStateException("after");return r;}).when(f).store(any(OwnerContext.class),any(UUID.class),any(java.io.InputStream.class),anyLong());
        doAnswer(i->{boolean fail=point.equals("create")&&!once.getAndSet(true);if(fail&&!after)throw new IllegalStateException("before");var r=entries.createDraft(i.getArgument(0));if(fail)throw new IllegalStateException("after");return r;}).when(e).createDraft(any());
        doAnswer(i->{boolean fail=point.equals("bind")&&!once.getAndSet(true);if(fail&&!after)throw new IllegalStateException("before");var r=files.bindToEntry(i.getArgument(0),i.getArgument(1),i.getArgument(2));if(fail)throw new IllegalStateException("after");return r;}).when(f).bindToEntry(any(),any(),any());
        var result=flow(e,d,f).handleCallback(cb(12,button(ready,"Создать черновик")));assertTrue(once.get(),point+" injection reached");
        restart();var state=dialogs.find(owner()).orElseThrow();
        List<BotAction> card=state.step().equals("draft_review")?flow().handleMessage(msg(13,"продолжить")):flow().handleCallback(cb(13,button(result,"Продолжить")));
        assertEquals(1,fileCount());Entry first=active();value(first,"1230");assertTrue(dialogs.find(owner()).orElseThrow().context().containsKey("photo_queue"));
        entries.confirm(new ConfirmEntryCommand(owner(),first.id(),"external-recover",first.revision()));var next=flow().handleMessage(msg(14,"продолжить"));create(next,15);
        value(active(),"252");assertEquals(2,fileCount());assertEquals(1,calls.get());
    }
    @Test void correctionAndConflictPreserveQueueOriginalTimeAndUnicode() {
        var card=create(start("health_screenshot"),12);var first=active();var choice=flow().handleCallback(cb(13,button(card,"Изменить")));
        flow().handleCallback(cb(14,button(choice,"Значение")));restart();flow().handleMessage(msg(15,"-1"));value(active(),"1230");
        flow().handleMessage(msg(16,"1234,5"));value(active(),"1234.5");assertEquals("reported",active().fieldOrigins().get("value"));
        card=flow().handleMessage(msg(17,"проверить ё 🌡"));choice=flow().handleCallback(cb(18,button(card,"Изменить")));flow().handleCallback(cb(19,button(choice,"Дата")));
        var current=active();var payload=new LinkedHashMap<>(current.payload());payload.put("value",new BigDecimal("1300"));
        entries.patch(new PatchEntryCommand(owner(),current.id(),current.revision(),null,payload,current.fieldOrigins()));
        flow().handleMessage(msg(20,"04.10.2026"));assertEquals("2026-10-05",active().payload().get("local_date"));value(active(),"1300");
        assertEquals(NOW,active().occurredAt());assertEquals(first.sourceRef(),active().sourceRef());assertTrue(dialogs.find(owner()).orElseThrow().context().containsKey("photo_queue"));
        current=active();entries.confirm(new ConfirmEntryCommand(owner(),current.id(),"external-edit",current.revision()));restart();create(flow().handleMessage(msg(21,"продолжить")),22);value(active(),"252");
    }
    @ParameterizedTest @ValueSource(booleans={false,true})
    void pendingPatchRecoversBeforeOrAfterWriteAndKeepsRemainingMetrics(boolean after) {
        var card=create(start("health_screenshot"),12);var choices=flow().handleCallback(cb(13,button(card,"Изменить")));flow().handleCallback(cb(14,button(choices,"Значение")));
        var failing=mock(EntryCoreService.class,delegatesTo(entries));doAnswer(i->{if(after)entries.patch(i.getArgument(0));throw new IllegalStateException("lost patch");}).when(failing).patch(any());
        assertThrows(IllegalStateException.class,()->flow(failing,dialogs,files).handleMessage(msg(15,"847,5")));restart();
        flow().handleMessage(msg(16,"продолжить"));value(active(),"847.5");assertEquals(2,active().revision());assertTrue(dialogs.find(owner()).orElseThrow().context().containsKey("photo_queue"));
        var e=active();entries.confirm(new ConfirmEntryCommand(owner(),e.id(),"external-patch",e.revision()));create(flow().handleMessage(msg(17,"продолжить")),18);value(active(),"252");assertEquals(1,calls.get());
    }
    @ParameterizedTest @ValueSource(booleans={false,true})
    void advanceSaveFailurePreservesCompletedEntryAndDoesNotSkipNext(boolean after) {
        var card=create(start("health_screenshot"),12);var first=active();var failing=mock(DialogStateService.class,delegatesTo(dialogs));
        doAnswer(i->{SaveDialogStateCommand c=i.getArgument(0);if(c.updateKey().botKey().equals("main-food-next")){if(after)dialogs.save(c);throw new IllegalStateException("lost advance");}return dialogs.save(c);}).when(failing).save(any());
        assertThrows(IllegalStateException.class,()->flow(entries,failing,files).handleCallback(cb(13,button(card,"Сохранить"))));
        assertEquals(EntryStatus.CONFIRMED,entries.requireEntry(owner(),first.id()).status());restart();
        var next=flow().handleMessage(msg(14,"продолжить"));create(next,15);value(active(),"252");assertEquals(2,fileCount());assertEquals(1,calls.get());
    }
    @ParameterizedTest @ValueSource(booleans={false,true})
    void lostConfirmResponseKeepsOneConfirmedRecordAndNextCandidate(boolean after) {
        var card=create(start("health_screenshot"),12);var first=active();var failing=mock(EntryCoreService.class,delegatesTo(entries));
        doAnswer(i->{if(after)entries.confirm(i.getArgument(0));throw new IllegalStateException("lost confirm");}).when(failing).confirm(any());
        assertThrows(IllegalStateException.class,()->flow(failing,dialogs,files).handleCallback(cb(13,button(card,"Сохранить"))));restart();
        var next=flow().handleMessage(msg(14,"продолжить"));assertEquals(EntryStatus.CONFIRMED,entries.requireEntry(owner(),first.id()).status());
        create(next,15);value(active(),"252");assertEquals(2,fileCount());assertEquals(1,calls.get());
    }
    @Test void cancelAfterLostCreateResponseCancelsOnlyCandidateAndLeavesEarlierConfirmation() {
        var firstCard=create(start("health_screenshot"),12);var first=active();var secondReady=flow().handleCallback(cb(13,button(firstCard,"Сохранить")));
        var failing=mock(EntryCoreService.class,delegatesTo(entries));doAnswer(i->{entries.createDraft(i.getArgument(0));throw new IllegalStateException("lost create");}).when(failing).createDraft(any());
        var error=flow(failing,dialogs,files).handleCallback(cb(14,button(secondReady,"Создать черновик")));var second=active();restart();
        flow().handleCallback(cb(15,button(error,"Отменить")));assertEquals(EntryStatus.CANCELLED,entries.requireEntry(owner(),second.id()).status());
        assertEquals(EntryStatus.CONFIRMED,entries.requireEntry(owner(),first.id()).status());assertTrue(entries.findActiveDraft(owner()).isEmpty());assertEquals(2,fileCount());assertEquals(1,calls.get());
    }
    @Test void missingOriginalTimestampDoesNotInventReportTimeOrCreateFile() {
        useJson(metricJson("watch_photo"));var p=photo(10);var noTime=new BotUpdate(p.updateId(),p.kind(),p.chatType(),p.chatId(),p.senderId(),false,null,List.of(),null,null,null,p.image());
        var select=flow().handleMessage(noTime);var ready=flow().handleCallback(cb(11,button(select,"Часы")));var result=create(ready,12);
        assertTrue(text(result).contains("заново")||text(result).contains("времен"));assertTrue(entries.findActiveDraft(owner()).isEmpty());assertEquals(0,fileCount());
    }
    @Test void unknownNumberUnitDateAreClarifiedWithoutOverwritingOriginalReport() throws Exception {
        var n=(ObjectNode)JSON.readTree(metricJson("health_screenshot"));var list=(com.fasterxml.jackson.databind.node.ArrayNode)n.get("metrics");list.remove(2);list.remove(1);var m=(ObjectNode)list.get(0);
        for(String key:List.of("value","unit","local_date")){m.putNull(key);m.withObject("field_origins").putNull(key);}useJson(n.toString());
        var select=flow().handleMessage(photo(10));flow().handleCallback(cb(11,button(select,"Экран здоровья")));assertTrue(entries.findActiveDraft(owner()).isEmpty());
        flow().handleMessage(msg(12,"-1"));flow().handleMessage(msg(13,"1230,5"));restart();flow().handleMessage(msg(14,"км"));assertTrue(entries.findActiveDraft(owner()).isEmpty());
        flow().handleMessage(msg(15,"шаги"));flow().handleMessage(msg(16,"31.02.2026"));var ready=flow().handleMessage(msg(17,"05.10.2026"));create(ready,18);
        value(active(),"1230.5");assertEquals(NOW,active().occurredAt());assertEquals("2026-10-05",active().payload().get("local_date"));assertEquals("reported",active().fieldOrigins().get("local_date"));contract(active());
    }
    @Test void pulseDateEditKeepsExplicitMeasurementTimeContextAndOriginalReport() throws Exception {
        var n=(ObjectNode)JSON.readTree(metricJson("watch_photo"));var list=(com.fasterxml.jackson.databind.node.ArrayNode)n.get("metrics");list.remove(0);list.remove(0);var pulse=(ObjectNode)list.get(0);
        pulse.put("local_time","14:30");pulse.put("qualifier","resting");pulse.withObject("field_origins").put("local_time","extracted");pulse.withObject("field_origins").put("qualifier","extracted");useJson(n.toString());
        var select=flow().handleMessage(photo(10));var ready=flow().handleCallback(cb(11,button(select,"Часы")));var card=create(ready,12);
        var choices=flow().handleCallback(cb(13,button(card,"Изменить")));flow().handleCallback(cb(14,button(choices,"Дата")));restart();flow().handleMessage(msg(15,"06.10.2026"));
        assertEquals("2026-10-06",active().payload().get("local_date"));assertEquals("14:30",active().payload().get("local_time"));assertEquals("resting",active().payload().get("qualifier"));
        assertEquals(NOW,active().occurredAt());assertEquals("extracted",active().fieldOrigins().get("qualifier"));contract(active());
    }
    @Test void providerFailureSupportsMetricManualInputWithoutAutomaticRetry() {
        var provider=mock(RecognitionProvider.class);when(provider.mode()).thenReturn(RecognitionProvider.Mode.LIVE);
        try{when(provider.recognize(any(),eq("watch_photo"))).thenThrow(new RecognitionException(RecognitionException.Code.NETWORK));}catch(RecognitionException impossible){throw new AssertionError(impossible);}
        recognition=new FoodRecognitionService(new ImageValidator(),provider,new RecognitionResponseParser());var select=flow().handleMessage(photo(10));var error=flow().handleCallback(cb(11,button(select,"Часы")));
        flow().handleCallback(cb(12,button(error,"Ввести вручную")));flow().handleMessage(msg(13,"сон"));flow().handleMessage(msg(14,"7,5"));flow().handleMessage(msg(15,"ч"));
        var ready=flow().handleMessage(msg(16,"05.10.2026"));create(ready,17);value(active(),"450");assertNull(active().payload().get("qualifier"));assertEquals(SourceKind.WATCH_PHOTO,active().sourceKind());
        try{verify(provider,times(1)).recognize(any(),eq("watch_photo"));}catch(RecognitionException impossible){throw new AssertionError(impossible);}
    }
    @Test void wrongModelImageClassIsErrorRatherThanImportOrFallback() {
        useJson(metricJson("health_screenshot"));var select=flow().handleMessage(photo(10));var error=flow().handleCallback(cb(11,button(select,"Часы")));
        assertEquals("food_error",dialogs.find(owner()).orElseThrow().step());assertNotNull(button(error,"Повторить"));assertNotNull(button(error,"Ввести вручную"));
        assertEquals(1,calls.get());assertTrue(entries.findActiveDraft(owner()).isEmpty());assertEquals(0,fileCount());
        flow().handleCallback(cb(12,button(error,"Отменить")));flow().handleCallback(cb(13,button(error,"Повторить")));assertEquals(1,calls.get());
    }
    @Test void productionSelectionDoesNotReplaceActiveText() {
        useJson(metricJson("health_screenshot"));flow().handleMessage(msg(1,"Пульс 70"));var state=dialogs.find(owner()).orElseThrow();
        flow().handleMessage(photo(10));assertEquals(state,dialogs.find(owner()).orElseThrow());assertEquals(0,calls.get());
    }
    @Test void foreignCardAndQueueCallbacksCannotMutateOwnerOrReadPhoto() {
        var card=create(start("health_screenshot"),12);var state=dialogs.find(owner()).orElseThrow();var entry=active();
        for(String action:List.of(button(card,"Сохранить"),button(card,"Отменить оставшиеся показатели"))){var foreign=new BotUpdate(20,BotUpdate.Kind.CALLBACK,BotUpdate.ChatType.PRIVATE,2002,2002L,false,null,List.of(),"foreign",action);flow().handleCallback(foreign);}
        assertEquals(state,dialogs.find(owner()).orElseThrow());assertEquals(entry,active());assertEquals(1,fileCount());assertEquals(1,calls.get());
    }
    @Test void staleGlobalCancelCannotDiscardNewerMiniAppEdit() {
        var card=create(start("health_screenshot"),12);var current=active();String cancel=button(card,"Отменить оставшиеся показатели");
        var payload=new LinkedHashMap<>(current.payload());payload.put("value",new BigDecimal("2345"));
        entries.patch(new PatchEntryCommand(owner(),current.id(),current.revision(),null,payload,current.fieldOrigins()));
        var edited=active();var result=flow().handleCallback(cb(13,cancel));
        assertEquals(edited,entries.requireEntry(owner(),edited.id()),"Old card must not silently cancel a newer Mini App revision");
        assertTrue(dialogs.find(owner()).orElseThrow().context().containsKey("photo_queue"));assertFalse(result.isEmpty());
    }
    @ParameterizedTest
    @CsvSource({"queue,finish","queue,cancel_ack","partial_commit,finish","partial_commit,cancel_ack"})
    void cancelRemainingSurvivesLostFinishOrCancelResponse(String route,String failurePoint) {
        var ready=start("health_screenshot");
        List<BotAction> card;
        if(route.equals("partial_commit")) {
            var failedBind=mock(FileStorageService.class,delegatesTo(files));
            doThrow(new IllegalStateException("bind unavailable")).when(failedBind).bindToEntry(any(),any(),any());
            card=flow(entries,dialogs,failedBind).handleCallback(cb(12,button(ready,"Создать черновик")));
            assertEquals("food_commit",dialogs.find(owner()).orElseThrow().step());
        } else card=create(ready,12);
        Entry cancelled=active();String cancel=button(card,route.equals("queue")?"Отменить оставшиеся показатели":"Отменить");
        var failingDialogs=mock(DialogStateService.class,delegatesTo(dialogs));
        var failingEntries=mock(EntryCoreService.class,delegatesTo(entries));var injected=new AtomicBoolean();
        doAnswer(i->{
            SaveDialogStateCommand command=i.getArgument(0);
            if(failurePoint.equals("finish")&&command.updateKey().botKey().equals("main-food-finish")&&!injected.getAndSet(true))
                throw new IllegalStateException("injected failure before idle was saved");
            return dialogs.save(command);
        }).when(failingDialogs).save(any());
        doAnswer(i->{
            var result=entries.cancel(i.getArgument(0),i.getArgument(1),i.getArgument(2));
            if(failurePoint.equals("cancel_ack")&&!injected.getAndSet(true))
                throw new IllegalStateException("injected lost cancel acknowledgement");
            return result;
        }).when(failingEntries).cancel(any(),any(),anyLong());
        try {flow(failingEntries,failingDialogs,files).handleCallback(cb(13,cancel));}
        catch(IllegalStateException expectedTransientFailure) {
            assertTrue(expectedTransientFailure.getMessage().startsWith("injected"));
        }
        assertTrue(injected.get(),"Test must reach the chosen failure after accepting cancellation");
        assertEquals(EntryStatus.CANCELLED,entries.requireEntry(owner(),cancelled.id()).status());
        restart();var recovered=flow().handleMessage(msg(14,"продолжить"));
        assertTrue(entries.findActiveDraft(owner()).isEmpty());assertEquals(1,fileCount());assertEquals(1,calls.get());
        assertEquals("idle",dialogs.find(owner()).orElseThrow().step(),"Cancellation of remaining metrics must survive the failed response; it must not resume the queue or partial commit");
        assertFalse(text(recovered).contains("Показатель 2"));
        assertEquals(1,context.getBean(MongoTemplate.class).getCollection("entries").countDocuments(),"Cancelled entry is retained and no other candidate was created");
        flow().handleCallback(cb(15,cancel));assertEquals(1,fileCount());assertEquals(1,calls.get());
        flow().beginCheckin(msg(16,"/state"));assertEquals("checkin_category",dialogs.find(owner()).orElseThrow().step());
    }
    @ParameterizedTest @ValueSource(booleans={false,true})
    void cancellationIntentSaveIsOrderedBeforeEntryMutationAndRecoversAfterLostAcknowledgement(boolean after) {
        var card=create(start("health_screenshot"),12);Entry original=active();String cancel=button(card,"Отменить оставшиеся показатели");
        var failing=mock(DialogStateService.class,delegatesTo(dialogs));var injected=new AtomicBoolean();
        doAnswer(i->{
            SaveDialogStateCommand command=i.getArgument(0);
            if(command.step().equals("food_cancelling")&&!injected.getAndSet(true)) {
                if(after)dialogs.save(command);
                throw new IllegalStateException("injected cancellation intent save failure");
            }
            return dialogs.save(command);
        }).when(failing).save(any());
        try{flow(entries,failing,files).handleCallback(cb(13,cancel));}
        catch(IllegalStateException failure){assertTrue(failure.getMessage().startsWith("injected"));}
        assertTrue(injected.get());
        if(!after)assertEquals(original,entries.requireEntry(owner(),original.id()),"No Entry mutation before a durable cancellation intent");
        restart();
        if(after)flow().handleMessage(msg(14,"продолжить"));
        else {
            assertEquals(original,active());
            flow().handleCallback(cb(14,cancel));
        }
        assertEquals(EntryStatus.CANCELLED,entries.requireEntry(owner(),original.id()).status());
        assertEquals("idle",dialogs.find(owner()).orElseThrow().step());assertEquals(1,fileCount());assertEquals(1,calls.get());
    }
    @Test void recoveryOfCancellationIntentDoesNotCancelNewerExternalRevision() {
        var card=create(start("health_screenshot"),12);Entry original=active();String cancel=button(card,"Отменить оставшиеся показатели");
        var failing=mock(EntryCoreService.class,delegatesTo(entries));var injected=new AtomicBoolean();
        doAnswer(i->{injected.set(true);throw new IllegalStateException("injected cancellation before entry mutation");}).when(failing).cancel(any(),any(),anyLong());
        try{flow(failing,dialogs,files).handleCallback(cb(13,cancel));}
        catch(IllegalStateException failure){assertTrue(failure.getMessage().startsWith("injected"));}
        assertTrue(injected.get());assertEquals("food_cancelling",dialogs.find(owner()).orElseThrow().step());
        var payload=new LinkedHashMap<>(original.payload());payload.put("value",new BigDecimal("4567"));
        var edited=entries.patch(new PatchEntryCommand(owner(),original.id(),original.revision(),null,payload,original.fieldOrigins()));
        restart();var result=flow().handleMessage(msg(14,"продолжить"));
        assertEquals(edited,entries.requireEntry(owner(),original.id()),"Pending cancel must not silently apply to a later Mini App revision");
        assertEquals(EntryStatus.DRAFT,active().status());assertEquals(1,fileCount());assertEquals(1,calls.get());
        assertFalse(result.isEmpty());assertTrue(dialogs.find(owner()).orElseThrow().context().containsKey("photo_queue"),"Conflict must retain the user's remaining candidates");
    }
    @Test void corruptCancellationIntentFailsClosedWithoutCancellingOrAdvancing() {
        var card=create(start("health_screenshot"),12);Entry original=active();String cancel=button(card,"Отменить оставшиеся показатели");
        var failing=mock(EntryCoreService.class,delegatesTo(entries));
        doThrow(new IllegalStateException("injected cancellation before mutation")).when(failing).cancel(any(),any(),anyLong());
        try{flow(failing,dialogs,files).handleCallback(cb(13,cancel));}
        catch(IllegalStateException failure){assertTrue(failure.getMessage().startsWith("injected"));}
        var intent=dialogs.find(owner()).orElseThrow();assertEquals("food_cancelling",intent.step());
        var damaged=new LinkedHashMap<>(intent.context());damaged.put("cancel_expected_revision","not-a-revision");
        dialogs.save(new SaveDialogStateCommand(owner(),intent.activeEntryId(),intent.step(),damaged,new TelegramUpdateKey("main-food-cancel-intent",14)));
        restart();var result=assertDoesNotThrow(()->flow().handleMessage(msg(15,"продолжить")));
        assertEquals(original,entries.requireEntry(owner(),original.id()));assertEquals(1,fileCount());assertEquals(1,calls.get());
        assertTrue(text(result).contains("поврежд")||text(result).contains("восстанов"));assertFalse(text(result).contains("Показатель 2"));
    }
    @ParameterizedTest @ValueSource(strings={"missing_index","wrong_index","missing_candidates","wrong_active_id"})
    void corruptedQueueFailsClosedWithoutThrowingOrMutatingEntry(String corruption) {
        create(start("health_screenshot"),12);var entry=active();entries.confirm(new ConfirmEntryCommand(owner(),entry.id(),"external",entry.revision()));
        var state=dialogs.find(owner()).orElseThrow();var data=new LinkedHashMap<>(state.context());var queue=new LinkedHashMap<>((Map<String,Object>)data.get("photo_queue"));
        switch(corruption){case "missing_index"->queue.remove("candidate_index");case "wrong_index"->queue.put("candidate_index","NaN");case "missing_candidates"->queue.remove("candidates");case "wrong_active_id"->queue.put("entry_id",UUID.randomUUID().toString());}
        data.put("photo_queue",queue);dialogs.save(new SaveDialogStateCommand(owner(),entry.id(),state.step(),data,new TelegramUpdateKey("main",13)));restart();
        var actions=assertDoesNotThrow(()->flow().handleMessage(msg(14,"продолжить")));assertFalse(actions.isEmpty());
        assertTrue(entries.findActiveDraft(owner()).isEmpty());assertEquals(1,fileCount());assertEquals(EntryStatus.CONFIRMED,entries.requireEntry(owner(),entry.id()).status());
        assertTrue(text(actions).contains("поврежд")||text(actions).contains("восстанов"),"Explain damaged queue rather than silently discard it");
    }
}
