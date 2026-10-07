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

/** Independent acceptance tests: real Mongo state, Entry service and disk-backed files. */
@Testcontainers
abstract class HealthWatchTestSupport {
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
        database="metrics23_"+UUID.randomUUID().toString().replace("-",""); reopen();
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
        return new CoreBotFlow(users,e,d,Clock.fixed(NOW,ZoneOffset.UTC)).withPhotos(new FoodPhotoFlow(e,d,f,recognition,loader,new DraftReviewFlow(e,d,null),"FIXTURE").withImageClassSelection());
    }
    BotUpdate msg(long id,String text){return new BotUpdate(id,BotUpdate.Kind.MESSAGE,BotUpdate.ChatType.PRIVATE,1001,1001L,false,text,List.of(),null,null,NOW);}
    BotUpdate photo(long id){return new BotUpdate(id,BotUpdate.Kind.MESSAGE,BotUpdate.ChatType.PRIVATE,1001,1001L,false,null,List.of(),null,null,NOW,new BotUpdate.Image("synthetic",null,"image/png",false));}
    BotUpdate cb(long id,String data){return new BotUpdate(id,BotUpdate.Kind.CALLBACK,BotUpdate.ChatType.PRIVATE,1001,1001L,false,null,List.of(),"cb"+id,data);}
    String button(List<BotAction> a,String title){return a.stream().filter(BotAction.SendInlineMessage.class::isInstance).map(BotAction.SendInlineMessage.class::cast).flatMap(m->m.rows().stream()).flatMap(List::stream).filter(b->b.text().equals(title)).map(BotAction.InlineButton::callbackData).findFirst().orElseThrow();}
    String text(List<BotAction> a){return a.stream().filter(BotAction.SendInlineMessage.class::isInstance).map(BotAction.SendInlineMessage.class::cast).map(BotAction.SendInlineMessage::text).reduce("",String::concat);}
    List<BotAction> ready(){flow().handleMessage(photo(10)); flow().handleMessage(msg(11,"06.10.2026")); flow().handleMessage(msg(12,"14:30")); return flow().handleMessage(msg(13,"не знаю"));}
    Entry active(){return entries.findActiveDraft(owner()).orElseThrow();}
    long fileCount(){return FoodPhotoFileTestSupport.count(context.getBean(MongoTemplate.class));}


}
