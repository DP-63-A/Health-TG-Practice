package org.healthtg.bot;

import org.healthtg.bot.visual.FoodPhotoFlow;
import org.healthtg.bot.recognition.*;
import org.healthtg.bot.draft.DraftReviewFlow;
import org.healthtg.core.dialog.*;
import org.healthtg.core.entry.*;
import org.healthtg.core.file.*;
import org.healthtg.user.UserService;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.boot.test.util.TestPropertyValues;
import org.springframework.context.annotation.AnnotationConfigApplicationContext;
import org.springframework.data.mongodb.core.MongoTemplate;
import org.springframework.data.mongodb.repository.support.MongoRepositoryFactory;
import org.testcontainers.containers.MongoDBContainer;
import org.testcontainers.junit.jupiter.*;
import org.testcontainers.utility.DockerImageName;
import javax.imageio.ImageIO;
import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;
import java.nio.file.Path;
import java.time.*;
import java.util.*;
import java.util.concurrent.atomic.AtomicInteger;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;
import static org.mockito.AdditionalAnswers.delegatesTo;

/** Independent acceptance tests: real Mongo state, Entry service and disk-backed files. */
@Testcontainers
class FoodPhotoStorageTest {
    @Container static final MongoDBContainer MONGO = new MongoDBContainer(DockerImageName.parse(
        "mongodb/mongodb-community-server:8.0-ubi9-slim").asCompatibleSubstituteFor("mongo"));
    @TempDir Path root;
    static final Instant NOW=Instant.parse("2026-10-07T08:00:00Z");
    AnnotationConfigApplicationContext context;
    EntryCoreService entries; DialogStateService dialogs; UserService users;
    FileStorageService files;
    String database; byte[] png; AtomicInteger calls; FoodPhotoFlow.ImageLoader loader;
    FoodRecognitionService recognition;

    @BeforeEach void open() throws Exception {
        database="food9_"+UUID.randomUUID().toString().replace("-",""); reopen();
        var bytes=new ByteArrayOutputStream(); ImageIO.write(new BufferedImage(2,2,BufferedImage.TYPE_INT_RGB),"png",bytes);
        png=bytes.toByteArray(); calls=new AtomicInteger(); loader=id->png;
        recognition=new FoodRecognitionService(new ImageValidator(),new RecognitionProvider(){
            public Mode mode(){return Mode.FIXTURE;}
            public Response recognize(ImageValidator.ValidatedImage image){calls.incrementAndGet(); return FixtureRecognitionProvider.bundled().recognize(image);}
        },new RecognitionResponseParser());
    }
    void reopen(){
        context=new AnnotationConfigApplicationContext();
        TestPropertyValues.of("test.mongo.uri="+MONGO.getReplicaSetUrl(),"test.mongo.database="+database).applyTo(context);
        context.register(BotCoreStorageTestConfiguration.class); context.refresh();
        entries=context.getBean(EntryCoreService.class); dialogs=context.getBean(DialogStateService.class); users=context.getBean(UserService.class);
        files=FoodPhotoFileTestSupport.create(context.getBean(MongoTemplate.class),entries,root.toString());
    }
    @AfterEach void close(){context.getBean(MongoTemplate.class).getDb().drop(); context.close();}
    OwnerContext owner(){return new OwnerContext(users.findOrCreate(1001).id());}
    CoreBotFlow flow(){return flow(entries,dialogs,files);}
    CoreBotFlow flow(EntryCoreService e,DialogStateService d,FileStorageService f){
        return new CoreBotFlow(users,e,d,Clock.fixed(NOW,ZoneOffset.UTC)).withPhotos(new FoodPhotoFlow(e,d,f,recognition,loader,new DraftReviewFlow(e,d,null),"FIXTURE"));
    }
    BotUpdate msg(long id,String text){return new BotUpdate(id,BotUpdate.Kind.MESSAGE,BotUpdate.ChatType.PRIVATE,1001,1001L,false,text,List.of(),null,null,NOW);}
    BotUpdate photo(long id){return new BotUpdate(id,BotUpdate.Kind.MESSAGE,BotUpdate.ChatType.PRIVATE,1001,1001L,false,null,List.of(),null,null,NOW,new BotUpdate.Image("synthetic",null,"image/png",false));}
    BotUpdate cb(long id,String data){return new BotUpdate(id,BotUpdate.Kind.CALLBACK,BotUpdate.ChatType.PRIVATE,1001,1001L,false,null,List.of(),"cb"+id,data);}
    String button(List<BotAction> a,String title){return a.stream().filter(BotAction.SendInlineMessage.class::isInstance).map(BotAction.SendInlineMessage.class::cast).flatMap(m->m.rows().stream()).flatMap(List::stream).filter(b->b.text().equals(title)).map(BotAction.InlineButton::callbackData).findFirst().orElseThrow();}
    String text(List<BotAction> a){return a.stream().filter(BotAction.SendInlineMessage.class::isInstance).map(BotAction.SendInlineMessage.class::cast).map(BotAction.SendInlineMessage::text).reduce("",String::concat);}
    List<BotAction> ready(){flow().handleMessage(photo(10)); flow().handleMessage(msg(11,"06.10.2026")); flow().handleMessage(msg(12,"14:30")); return flow().handleMessage(msg(13,"не знаю"));}
    Entry active(){return entries.findActiveDraft(owner()).orElseThrow();}
    long fileCount(){return FoodPhotoFileTestSupport.count(context.getBean(MongoTemplate.class));}

    @Test void unknownCalendarAndOptionalMassSurviveRestartAndCreateOneProtectedDraft() throws Exception {
        var first=flow().handleMessage(photo(10)); assertTrue(text(first).contains("FIXTURE"));
        assertTrue(entries.findActiveDraft(owner()).isEmpty());
        flow().handleMessage(msg(11,"31.02.2026")); assertEquals("food_clarify",dialogs.find(owner()).orElseThrow().step());
        flow().handleMessage(msg(12,"06.10.2026")); context.close(); reopen();
        flow().handleMessage(msg(13,"14:30")); var ready=flow().handleMessage(msg(14,"не знаю"));
        assertTrue(entries.findActiveDraft(owner()).isEmpty());
        flow().handleCallback(cb(15,button(ready,"Создать черновик")));
        Entry entry=active(); assertEquals(SourceKind.FOOD_PHOTO,entry.sourceKind()); assertEquals(EntryStatus.DRAFT,entry.status());
        assertNull(entry.payload().get("mass_g")); assertEquals("estimated",entry.fieldOrigins().get("description"));
        assertEquals(LocalDateTime.of(2026,10,6,14,30),entry.occurredAt().atZone(users.findOrCreate(1001).timezone()).toLocalDateTime());
        UUID file=UUID.fromString((String)entry.sourceRef().get("file_id"));
        var separate=FoodPhotoFileTestSupport.create(context.getBean(MongoTemplate.class),entries,root.toString());
        try(var content=separate.open(owner(),file)){assertArrayEquals(png,content.content().readAllBytes());}
        assertThrows(StoredFileNotFoundException.class,()->separate.open(new OwnerContext(users.findOrCreate(2002).id()),file));
        assertTrue(entries.listEntries(new ListEntriesQuery(owner(),EntryStatus.CONFIRMED,null,null,null,ZoneOffset.UTC)).isEmpty());
        flow().handleCallback(cb(15,button(ready,"Создать черновик"))); assertEquals(entry.id(),active().id()); assertEquals(1,calls.get());
    }
    @Test void inputReplayAndNewPhotoStateAndStaleCallbacksDoNotDestroyCandidate(){
        flow().handleMessage(photo(10)); var original=dialogs.find(owner()).orElseThrow();
        flow().handleMessage(photo(10)); flow().handleMessage(photo(11)); flow().beginCheckin(msg(12,"/state"));
        flow().handleCallback(cb(13,"dr:x:"+UUID.randomUUID()+":1")); flow().handleCallback(cb(14,"tx:cancel:old"));
        assertEquals(original,dialogs.find(owner()).orElseThrow()); assertEquals(1,calls.get());
        flow().handleMessage(msg(15,"не дата")); assertEquals(original,dialogs.find(owner()).orElseThrow());
    }
    @Test void textAndCheckinDialogsGuardAgainstPhotoBeforeProvider(){
        flow().handleMessage(msg(1,"Сегодня устал")); var original=dialogs.find(owner()).orElseThrow();
        flow().handleMessage(photo(2)); assertEquals(original,dialogs.find(owner()).orElseThrow()); assertEquals(0,calls.get());
    }
    @Test void cancelledCandidateOldButtonsCannotAffectNextCandidate(){
        var ready=ready(); String stale=button(ready,"Создать черновик");
        flow().handleCallback(cb(14,button(ready,"Отменить"))); flow().handleMessage(photo(15));
        var current=dialogs.find(owner()).orElseThrow(); flow().handleCallback(cb(16,stale));
        assertEquals(current,dialogs.find(owner()).orElseThrow()); assertTrue(entries.findActiveDraft(owner()).isEmpty());
    }
    @Test void bindFailureNeverClaimsSuccessAndContinueReusesEntryAndFile(){
        var ready=ready(); FileStorageService failing=mock(FileStorageService.class,delegatesTo(files));
        doThrow(new IllegalStateException("private disk location")).when(failing).bindToEntry(any(),any(),any());
        var error=flow(entries,dialogs,failing).handleCallback(cb(14,button(ready,"Создать черновик")));
        Entry first=active(); UUID file=UUID.fromString((String)first.sourceRef().get("file_id"));
        assertFalse(text(error).contains("private disk")); assertFalse(text(error).contains("Фото связано"));
        assertThrows(StoredFileNotFoundException.class,()->files.open(owner(),file));
        var recovered=flow().handleCallback(cb(15,button(error,"Продолжить")));
        assertTrue(text(recovered).contains("Фото связано")); assertEquals(first.id(),active().id()); assertEquals(1,fileCount());
    }
    @Test void lostCreateResponseIsRecoverableWithoutDuplicateEntry(){
        var ready=ready(); EntryCoreService failing=mock(EntryCoreService.class,delegatesTo(entries));
        doAnswer(i->{entries.createDraft(i.getArgument(0)); throw new IllegalStateException("lost create response");}).when(failing).createDraft(any());
        var error=flow(failing,dialogs,files).handleCallback(cb(14,button(ready,"Создать черновик")));
        UUID id=active().id(); context.close(); reopen();
        flow().handleCallback(cb(15,button(error,"Продолжить"))); assertEquals(id,active().id()); assertEquals(1,fileCount());
    }
    @Test void cancelAfterLostCreateResponseCancelsCreatedDraft(){
        var ready=ready(); EntryCoreService failing=mock(EntryCoreService.class,delegatesTo(entries));
        doAnswer(i->{entries.createDraft(i.getArgument(0)); throw new IllegalStateException("lost");}).when(failing).createDraft(any());
        var error=flow(failing,dialogs,files).handleCallback(cb(14,button(ready,"Создать черновик"))); UUID id=active().id();
        flow().handleCallback(cb(15,button(error,"Отменить"))); assertTrue(entries.findActiveDraft(owner()).isEmpty());
        assertEquals(EntryStatus.CANCELLED,entries.requireEntry(owner(),id).status());
    }
    @Test void lostBoundStateResponseCanRecoverOriginalCard(){
        var ready=ready(); DialogStateService failing=mock(DialogStateService.class,delegatesTo(dialogs));
        doAnswer(i->{SaveDialogStateCommand c=i.getArgument(0); if(c.step().equals("draft_review"))throw new IllegalStateException("lost"); return dialogs.save(c);}).when(failing).save(any());
        var error=flow(entries,failing,files).handleCallback(cb(14,button(ready,"Создать черновик"))); UUID id=active().id();
        flow().handleCallback(cb(15,button(error,"Продолжить"))); assertEquals(id,active().id()); assertEquals("draft_review",dialogs.find(owner()).orElseThrow().step());
    }
    @Test void modelErrorHasExplicitManualRetryCancelAndNoAutomaticFallback(){
        var failing=mock(RecognitionProvider.class); when(failing.mode()).thenReturn(RecognitionProvider.Mode.LIVE);
        try{when(failing.recognize(any())).thenThrow(new RecognitionException(RecognitionException.Code.TIMEOUT));}catch(RecognitionException e){throw new AssertionError(e);}
        recognition=new FoodRecognitionService(new ImageValidator(),failing,new RecognitionResponseParser());
        var error=flow().handleMessage(photo(10)); assertEquals("food_error",dialogs.find(owner()).orElseThrow().step());
        assertNotNull(button(error,"Повторить")); assertNotNull(button(error,"Отменить"));
        flow().handleCallback(cb(11,button(error,"Ввести вручную"))); flow().handleMessage(msg(12,"Печёное яблоко 🍎"));
        flow().handleMessage(msg(13,"06.10.2026")); flow().handleMessage(msg(14,"14:30"));
        var ready=flow().handleMessage(msg(15,"не знаю")); flow().handleCallback(cb(16,button(ready,"Создать черновик")));
        assertEquals("reported",active().fieldOrigins().get("description"));
        try{verify(failing,times(1)).recognize(any());}catch(RecognitionException e){throw new AssertionError(e);}
    }
    @Test void malformedPersistedCandidateResetsWithoutEntry(){
        flow().handleMessage(photo(10)); var state=dialogs.find(owner()).orElseThrow(); var data=new LinkedHashMap<>(state.context());
        data.put("file_id","not-uuid"); dialogs.save(new SaveDialogStateCommand(owner(),null,state.step(),data,new TelegramUpdateKey("test",11)));
        assertTrue(text(flow().handleMessage(msg(12,"06.10.2026"))).contains("Не удалось восстановить"));
        assertEquals("idle",dialogs.find(owner()).orElseThrow().step()); assertTrue(entries.findActiveDraft(owner()).isEmpty());
    }
    @Test void nonNullNutrientsAndOriginsRoundTripMongoWithoutRecalculation() throws Exception {
        var mapper=new com.fasterxml.jackson.databind.ObjectMapper();
        var json=(com.fasterxml.jackson.databind.node.ObjectNode)mapper.readTree(getClass().getResourceAsStream("/recognition/fixture.json"));
        json.put("mass_g",250);json.put("nutrients_basis","per_100g");json.put("occurred_at","2026-10-06T14:30:00+03:00");
        ((com.fasterxml.jackson.databind.node.ObjectNode)json.get("nutrients")).put("energy_kcal",100).put("protein_g",0);
        var origins=(com.fasterxml.jackson.databind.node.ObjectNode)json.get("field_origins");
        origins.put("mass_g","estimated").put("nutrients.energy_kcal","estimated").put("nutrients.protein_g","extracted").put("occurred_at","extracted");
        recognition=new FoodRecognitionService(new ImageValidator(),new FixtureRecognitionProvider(json.toString()),new RecognitionResponseParser());
        var ready=flow().handleMessage(photo(10));context.close();reopen();
        var card=flow().handleCallback(cb(11,button(ready,"Создать черновик")));var entry=active();
        var nutrients=(Map<?,?>)entry.payload().get("nutrients");
        assertEquals(100,((Number)nutrients.get("energy_kcal")).intValue());assertEquals(0,((Number)nutrients.get("protein_g")).intValue());
        assertNull(nutrients.get("fat_g"));assertEquals("per_100g",entry.payload().get("nutrients_basis"));
        assertEquals("estimated",entry.fieldOrigins().get("nutrients.energy_kcal"));assertEquals("extracted",entry.fieldOrigins().get("nutrients.protein_g"));
        flow().handleCallback(cb(12,button(card,"Сохранить")));
        var confirmed=entries.requireEntry(owner(),entry.id());assertEquals(EntryStatus.CONFIRMED,confirmed.status());
        assertEquals("estimated",confirmed.fieldOrigins().get("mass_g"));assertEquals(entry.sourceRef(),confirmed.sourceRef());
    }
    @Test void declaredUnknownMassRejectsNegativeAndAcceptsCommaDecimal(){
        flow().handleMessage(photo(10));flow().handleMessage(msg(11,"06.10.2026"));flow().handleMessage(msg(12,"14:30"));
        var state=dialogs.find(owner()).orElseThrow();flow().handleMessage(msg(13,"-1"));assertEquals(state,dialogs.find(owner()).orElseThrow());
        var ready=flow().handleMessage(msg(14,"250,5"));flow().handleCallback(cb(15,button(ready,"Создать черновик")));
        assertEquals(0,new java.math.BigDecimal("250.5").compareTo(new java.math.BigDecimal(active().payload().get("mass_g").toString())));
        assertEquals("reported",active().fieldOrigins().get("mass_g"));
    }
    @Test void storeThenLostStateMayLeaveOnlyInaccessibleOrphanAndOneDraft(){
        var ready=ready();DialogStateService failing=mock(DialogStateService.class,delegatesTo(dialogs));
        doAnswer(i->{SaveDialogStateCommand c=i.getArgument(0);if(c.updateKey().botKey().equals("main-food-stored"))throw new IllegalStateException("lost");return dialogs.save(c);}).when(failing).save(any());
        var error=flow(entries,failing,files).handleCallback(cb(14,button(ready,"Создать черновик")));
        assertEquals(1,fileCount());assertTrue(entries.findActiveDraft(owner()).isEmpty());
        flow().handleCallback(cb(15,button(error,"Продолжить")));assertEquals(2,fileCount());assertEquals(EntryStatus.DRAFT,active().status());
        assertEquals(1,entries.listEntries(new ListEntriesQuery(owner(),EntryStatus.DRAFT,null,null,null,ZoneOffset.UTC)).size());
    }
    @Test void persistedFileIdWithLostSaveResponseDoesNotStoreAgain(){
        var ready=ready();DialogStateService failing=mock(DialogStateService.class,delegatesTo(dialogs));
        doAnswer(i->{SaveDialogStateCommand c=i.getArgument(0);var saved=dialogs.save(c);if(c.updateKey().botKey().equals("main-food-stored"))throw new IllegalStateException("lost");return saved;}).when(failing).save(any());
        var error=flow(entries,failing,files).handleCallback(cb(14,button(ready,"Создать черновик")));
        flow().handleCallback(cb(15,button(error,"Продолжить")));assertEquals(1,fileCount());assertEquals(EntryStatus.DRAFT,active().status());
    }
    @Test void priorUnrelatedDraftIsNotReboundToCandidatePhoto(){
        var ready=ready();var unrelated=entries.createDraft(new CreateDraftCommand(owner(),EntryType.NOTE,SourceKind.TEXT,
            Map.of(),NOW,Map.of("text","other"),Map.of("text","reported"),new TelegramUpdateKey("other",1))).entry();
        flow().handleCallback(cb(14,button(ready,"Создать черновик")));
        assertEquals(unrelated.id(),active().id());assertFalse(active().sourceRef().containsKey("file_id"));assertEquals(0,fileCount());
        assertEquals("food_clarify",dialogs.find(owner()).orElseThrow().step());
    }
    @Test void retryAfterProviderFailureIsExplicitAndOnlyOnce() throws Exception {
        var provider=mock(RecognitionProvider.class);when(provider.mode()).thenReturn(RecognitionProvider.Mode.LIVE);
        when(provider.recognize(any())).thenThrow(new RecognitionException(RecognitionException.Code.NETWORK))
            .thenReturn(FixtureRecognitionProvider.bundled().recognize(null));
        recognition=new FoodRecognitionService(new ImageValidator(),provider,new RecognitionResponseParser());
        var error=flow().handleMessage(photo(10));verify(provider,times(1)).recognize(any());
        var retry=cb(11,button(error,"Повторить"));flow().handleCallback(retry);flow().handleCallback(retry);
        verify(provider,times(2)).recognize(any());assertEquals("food_clarify",dialogs.find(owner()).orElseThrow().step());
    }
    @Test void sharedCardCanCancelPhotoDraft(){
        var ready=ready();var card=flow().handleCallback(cb(14,button(ready,"Создать черновик")));UUID id=active().id();
        flow().handleCallback(cb(15,button(card,"Не сохранять")));
        assertEquals(EntryStatus.CANCELLED,entries.requireEntry(owner(),id).status());assertTrue(entries.findActiveDraft(owner()).isEmpty());
    }
    @Test void sharedCardCanEditPhotoMassWithoutChangingOtherOrigins(){
        var ready=ready();var card=flow().handleCallback(cb(14,button(ready,"Создать черновик")));UUID id=active().id();
        var choices=flow().handleCallback(cb(15,button(card,"Изменить")));
        flow().handleCallback(cb(16,button(choices,"Масса, г")));flow().handleMessage(msg(17,"123,5"));
        assertEquals(id,active().id());assertEquals(0,new java.math.BigDecimal("123.5").compareTo(new java.math.BigDecimal(active().payload().get("mass_g").toString())));
        assertEquals("reported",active().fieldOrigins().get("mass_g"));assertEquals("estimated",active().fieldOrigins().get("description"));
    }
    @org.junit.jupiter.params.ParameterizedTest
    @org.junit.jupiter.params.provider.ValueSource(strings={"both","date","time"})
    void extractedCalendarComponentsRetainEvidenceAndCanonicalOrigin(String extracted) throws Exception {
        var mapper=new com.fasterxml.jackson.databind.ObjectMapper();
        var json=(com.fasterxml.jackson.databind.node.ObjectNode)mapper.readTree(getClass().getResourceAsStream("/recognition/fixture.json"));
        var origins=(com.fasterxml.jackson.databind.node.ObjectNode)json.get("field_origins");
        if(!extracted.equals("time")){json.put("local_date","2026-10-06");origins.put("local_date","extracted");}
        if(!extracted.equals("date")){json.put("local_time","14:30");origins.put("local_time","extracted");}
        recognition=new FoodRecognitionService(new ImageValidator(),new FixtureRecognitionProvider(json.toString()),new RecognitionResponseParser());
        flow().handleMessage(photo(10));long id=11;
        if(extracted.equals("time"))flow().handleMessage(msg(id++,"06.10.2026"));
        if(extracted.equals("date"))flow().handleMessage(msg(id++,"14:30"));
        var ready=flow().handleMessage(msg(id++,"не знаю"));context.close();reopen();
        var card=flow().handleCallback(cb(id++,button(ready,"Создать черновик")));var entry=active();
        assertEquals(extracted.equals("both")?"extracted":"computed",entry.fieldOrigins().get("occurred_at"),"Canonical date must have provenance, without promoting a mixed source to wholly reported");
        assertEquals(extracted.equals("time")?"reported":"extracted",entry.fieldOrigins().get("local_date"));
        assertEquals(extracted.equals("date")?"reported":"extracted",entry.fieldOrigins().get("local_time"));
        assertEquals(LocalDateTime.of(2026,10,6,14,30),entry.occurredAt().atZone(users.findOrCreate(1001).timezone()).toLocalDateTime());
        flow().handleCallback(cb(id,button(card,"Сохранить")));
        assertEquals(entry.fieldOrigins(),entries.requireEntry(owner(),entry.id()).fieldOrigins(),"Confirmation must preserve extracted/mixed evidence");
    }
    @Test void staleAndEqualPhotoAfterNewerMainIdleDoNotRunModelOrChangeState(){
        dialogs.save(new SaveDialogStateCommand(owner(),null,"idle",Map.of("schema_version",1),new TelegramUpdateKey("main",200)));
        var state=dialogs.find(owner()).orElseThrow();
        flow().handleMessage(photo(100));flow().handleMessage(photo(200));
        assertEquals(state,dialogs.find(owner()).orElseThrow(),"New food phase must not bypass ordinary update ordering");assertEquals(0,calls.get());
        flow().handleMessage(photo(201));assertEquals("food_clarify",dialogs.find(owner()).orElseThrow().step());assertEquals(1,calls.get());
    }
    @Test void staleOrdinaryMessageAndStateAfterFoodCancelCannotRestartDialog(){
        var first=flow().handleMessage(photo(100));flow().handleCallback(cb(200,button(first,"Отменить")));
        var state=dialogs.find(owner()).orElseThrow();
        for(long id:List.of(150L,200L)){
            flow().handleMessage(msg(id,"06.10.2026 пульс 70 ударов в минуту"));
            flow().beginCheckin(msg(id,"/state"));flow().handleCallback(cb(id,"dr:s:"+UUID.randomUUID()+":1"));
        }
        assertEquals(state,dialogs.find(owner()).orElseThrow(),"Cancelled photo publishes a watermark shared by ordinary flows");
        assertTrue(entries.findActiveDraft(owner()).isEmpty());assertEquals(1,calls.get());
        flow().beginCheckin(msg(201,"/state"));assertEquals("checkin_category",dialogs.find(owner()).orElseThrow().step());
    }
    @Test void cancelledPhotoEqualReplayIsHarmlessAndNewPhotoCanStart(){
        var first=flow().handleMessage(photo(100));String cancel=button(first,"Отменить");flow().handleCallback(cb(200,cancel));
        var state=dialogs.find(owner()).orElseThrow();flow().handleCallback(cb(200,cancel));flow().handleMessage(photo(200));flow().handleMessage(photo(100));
        assertEquals(state,dialogs.find(owner()).orElseThrow());assertEquals(1,calls.get());
        flow().handleMessage(photo(201));assertEquals(2,calls.get());assertEquals("food_clarify",dialogs.find(owner()).orElseThrow().step());
    }
}
