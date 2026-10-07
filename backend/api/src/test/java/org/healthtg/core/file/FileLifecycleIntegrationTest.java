package org.healthtg.core.file;

import org.healthtg.core.entry.*;
import org.junit.jupiter.api.*;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.data.mongodb.core.MongoTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;
import org.testcontainers.containers.MongoDBContainer;
import org.testcontainers.junit.jupiter.*;
import org.testcontainers.utility.DockerImageName;
import org.bson.Document;
import javax.imageio.ImageIO;
import java.awt.image.BufferedImage;
import java.io.*;
import java.nio.file.*;
import java.time.Instant;
import java.util.*;
import java.util.concurrent.atomic.AtomicReference;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

/** Real Mongo and disk tests exercise public Entry/storage operations, not lifecycle helper results. */
@SpringBootTest
@org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc
@Testcontainers
class FileLifecycleIntegrationTest {
    static final Path ROOT=root();
    @Container static final MongoDBContainer MONGO=new MongoDBContainer(DockerImageName.parse(
            "mongodb/mongodb-community-server:8.0-ubi9-slim").asCompatibleSubstituteFor("mongo"));
    @DynamicPropertySource static void properties(DynamicPropertyRegistry registry) {
        registry.add("spring.data.mongodb.uri",MONGO::getReplicaSetUrl);
        registry.add("health-tg.files.root",ROOT::toString);
    }
    @Autowired FileStorageService files;
    @Autowired EntryCoreService entries;
    @MockitoSpyBean MongoTemplate mongo;
    @MockitoSpyBean MongoStoredFileRepository repository;
    @MockitoSpyBean EntryStore store;
    @org.springframework.test.context.bean.override.mockito.MockitoBean org.healthtg.session.SessionService sessions;
    @Autowired org.springframework.test.web.servlet.MockMvc http;
    OwnerContext owner;byte[] bytes;
    static Path root(){try{return Files.createTempDirectory("lifecycle114-");}catch(IOException e){throw new UncheckedIOException(e);}}
    @BeforeEach void setup() throws Exception {
        mongo.getDb().drop();owner=new OwnerContext(UUID.randomUUID());
        var out=new ByteArrayOutputStream();ImageIO.write(new BufferedImage(2,2,BufferedImage.TYPE_INT_RGB),"png",out);bytes=out.toByteArray();
    }
    StoredFile upload(UUID id){return files.store(owner,id,new ByteArrayInputStream(bytes),bytes.length);}
    Path path(UUID id){return ROOT.resolve(id.toString().substring(0,2)).resolve(id+".bin");}
    Document metadata(UUID id){return mongo.getCollection("stored_files").find(new Document("_id",id.toString())).first();}
    CreateDraftCommand command(Object id,long update){return new CreateDraftCommand(owner,EntryType.MEAL,SourceKind.FOOD_PHOTO,
            Map.of("file_id",id),Instant.parse("2026-10-07T12:00:00Z"),Map.of("description","meal"),Map.of(),new TelegramUpdateKey("lifecycle",update));}

    @Test void integerStepsAreEnforcedByRealCoreAndHttpWithoutChangingFractionalSleep() throws Exception {
        when(sessions.authenticate("numeric-test")).thenReturn(owner.userId());
        for(String invalid:List.of("-1","0.1","1230.5","1000000001")) {
            var payload=Map.<String,Object>of("code","steps","value",new java.math.BigDecimal(invalid),"unit","count","local_date","2026-10-06");
            assertThrows(EntryValidationException.class,()->entries.createDraft(new CreateDraftCommand(owner,EntryType.METRICS,SourceKind.TEXT,
                    Map.of(),Instant.parse("2026-10-06T12:00:00Z"),payload,Map.of(),new TelegramUpdateKey("numeric",1))));
        }
        var draft=entries.createDraft(new CreateDraftCommand(owner,EntryType.METRICS,SourceKind.TEXT,Map.of(),Instant.parse("2026-10-06T12:00:00Z"),
                Map.of("code","steps","value",new java.math.BigDecimal("1230.0"),"unit","count","local_date","2026-10-06"),Map.of(),new TelegramUpdateKey("numeric",2))).entry();
        for(String invalid:List.of("0.1","1230.5","1000000001")) {
            http.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch("/api/v1/entries/{id}",draft.id())
                    .header("Authorization","Bearer numeric-test").contentType("application/json")
                    .content("{\"expected_revision\":1,\"payload\":{\"value\":"+invalid+"}}"))
                    .andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers.status().isUnprocessableEntity());
            assertEquals(draft,entries.requireEntry(owner,draft.id()));
        }
        for(String valid:List.of("0","1000000000")) {
            var current=entries.requireEntry(owner,draft.id());
            http.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch("/api/v1/entries/{id}",draft.id())
                    .header("Authorization","Bearer numeric-test").contentType("application/json")
                    .content("{\"expected_revision\":"+current.revision()+",\"payload\":{\"value\":"+valid+"}}"))
                    .andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers.status().isOk());
        }
        var current=entries.requireEntry(owner,draft.id());
        mongo.getCollection("entries").updateOne(new Document("_id",draft.id().toString()),new Document("$set",new Document("payload.value","7.5")));
        assertThrows(EntryValidationException.class,()->entries.confirm(new ConfirmEntryCommand(owner,draft.id(),"legacy",current.revision())));
        assertEquals(0,new java.math.BigDecimal("7.5").compareTo((java.math.BigDecimal)entries.requireEntry(owner,draft.id()).payload().get("value")));
        var repaired=entries.patch(new PatchEntryCommand(owner,draft.id(),current.revision(),null,Map.of("value",new java.math.BigDecimal("8")),Map.of()));
        entries.confirm(new ConfirmEntryCommand(owner,draft.id(),"whole",repaired.revision()));
        for(String code:List.of("sleep_duration_min","heart_rate")) {
            var fractional=entries.createDraft(new CreateDraftCommand(owner,EntryType.METRICS,SourceKind.TEXT,Map.of(),Instant.parse("2026-10-06T12:00:00Z"),
                    Map.of("code",code,"value",new java.math.BigDecimal("7.5"),"unit",code.equals("heart_rate")?"bpm":"min","local_date","2026-10-06"),
                    Map.of(),new TelegramUpdateKey(code,3))).entry();
            entries.confirm(new ConfirmEntryCommand(owner,fractional.id(),code,fractional.revision()));
            assertEquals(0,new java.math.BigDecimal("7.5").compareTo((java.math.BigDecimal)entries.requireEntry(owner,fractional.id()).payload().get("value")));
        }
    }

    @Test void deletionIsDurableMinimalAndRejectsAllLateWriters() {
        UUID id=UUID.randomUUID();upload(id);
        assertThrows(StoredFileNotFoundException.class,()->files.discardUnreferenced(new OwnerContext(UUID.randomUUID()),id));
        assertTrue(Files.exists(path(id)));
        assertEquals(FileStorageService.CleanupResult.DELETED,files.discardUnreferenced(owner,id));
        assertFalse(Files.exists(path(id)));assertEquals("DELETED",metadata(id).getString("lifecycle"));
        for(String field:List.of("relativePath","sha256","mediaType","extension","size","width","height","createdAt","entryId"))assertFalse(metadata(id).containsKey(field),field);
        assertEquals(FileStorageService.CleanupResult.DELETED,files.discardUnreferenced(owner,id));
        assertThrows(StoredFileUnavailableException.class,()->upload(id));
        assertThrows(StoredFileNotFoundException.class,()->entries.createDraft(command(id.toString(),1)));
        assertThrows(StoredFileUnavailableException.class,()->files.bindToEntry(owner,id,UUID.randomUUID()));
        assertTrue(entries.findActiveDraft(owner).isEmpty());assertFalse(Files.exists(path(id)));
    }
    @Test void missingReservationCancellationBlocksDelayedInsert() {
        UUID id=UUID.randomUUID();assertEquals(FileStorageService.CleanupResult.DELETED,files.discardUnreferenced(owner,id));
        assertThrows(StoredFileUnavailableException.class,()->upload(id));assertEquals("DELETED",metadata(id).getString("lifecycle"));assertFalse(Files.exists(path(id)));
    }
    @ParameterizedTest @ValueSource(strings={"store","create"})
    void cleanupWaitsForInFlightPublicOperation(String operation) throws Exception {
        UUID id=UUID.randomUUID();
        var entered=new java.util.concurrent.CountDownLatch(1);var release=new java.util.concurrent.CountDownLatch(1);
        if(operation.equals("create")) {
            upload(id);
            doAnswer(i->{entered.countDown();assertTrue(release.await(10,java.util.concurrent.TimeUnit.SECONDS));return i.callRealMethod();}).when(store).save(any());
        } else {
            doAnswer(i->{var saved=mongo.insert((MongoStoredFileDocument)i.getArgument(0));entered.countDown();assertTrue(release.await(10,java.util.concurrent.TimeUnit.SECONDS));return saved;})
                    .when(repository).insert(any(MongoStoredFileDocument.class));
        }
        try(var executor=java.util.concurrent.Executors.newFixedThreadPool(2)) {
            var writing=executor.submit(()->operation.equals("create")?entries.createDraft(command(id.toString(),1)):upload(id));
            assertTrue(entered.await(10,java.util.concurrent.TimeUnit.SECONDS));
            var cleaning=executor.submit(()->files.discardUnreferenced(owner,id));
            try {assertThrows(java.util.concurrent.TimeoutException.class,()->cleaning.get(200,java.util.concurrent.TimeUnit.MILLISECONDS));}
            finally {release.countDown();}
            writing.get(10,java.util.concurrent.TimeUnit.SECONDS);
            assertEquals(operation.equals("create")?FileStorageService.CleanupResult.PROTECTED:FileStorageService.CleanupResult.DELETED,
                    cleaning.get(10,java.util.concurrent.TimeUnit.SECONDS));
            assertEquals(operation.equals("create"),Files.exists(path(id)));
        } finally {release.countDown();}
    }
    @Test void reservationInsertCompletingAfterCancellationCannotReplaceTombstone() {
        UUID id=UUID.randomUUID();AtomicReference<MongoStoredFileDocument> pending=new AtomicReference<>();
        doAnswer(i->{if(pending.get()==null){pending.set(i.getArgument(0));throw new IllegalStateException("insert response unknown");}return mongo.insert((MongoStoredFileDocument)i.getArgument(0));})
                .when(repository).insert(any(MongoStoredFileDocument.class));
        assertThrows(IllegalStateException.class,()->upload(id));assertNull(metadata(id));
        assertEquals(FileStorageService.CleanupResult.DELETED,files.discardUnreferenced(owner,id));
        assertThrows(org.springframework.dao.DuplicateKeyException.class,()->repository.insert(pending.get()));
        assertEquals("DELETED",metadata(id).getString("lifecycle"));assertFalse(Files.exists(path(id)));
    }
    @Test void lostPinAcknowledgementProtectsFileBeforeAnyEntryWrite() {
        UUID id=UUID.randomUUID();upload(id);
        doAnswer(i->{Object result=i.callRealMethod();throw new IllegalStateException("pin response lost");})
                .when(mongo).findAndModify(any(org.springframework.data.mongodb.core.query.Query.class),
                        any(org.springframework.data.mongodb.core.query.Update.class),any(org.springframework.data.mongodb.core.FindAndModifyOptions.class),eq(MongoStoredFileDocument.class));
        assertThrows(IllegalStateException.class,()->entries.createDraft(command(id.toString(),1)));
        assertEquals("PINNED",metadata(id).getString("lifecycle"));assertTrue(entries.findActiveDraft(owner).isEmpty());
        assertEquals(FileStorageService.CleanupResult.PROTECTED,files.discardUnreferenced(owner,id));assertTrue(Files.exists(path(id)));
    }
    @Test void lostFinishAcknowledgementIsSafeToRepeat() {
        UUID id=UUID.randomUUID();upload(id);
        doAnswer(i->{i.callRealMethod();throw new IllegalStateException("delete response lost");})
                .when(mongo).updateFirst(any(org.springframework.data.mongodb.core.query.Query.class),any(org.springframework.data.mongodb.core.query.Update.class),eq(MongoStoredFileDocument.class));
        assertThrows(IllegalStateException.class,()->files.discardUnreferenced(owner,id));
        assertEquals("DELETED",metadata(id).getString("lifecycle"));assertFalse(Files.exists(path(id)));
        assertEquals(FileStorageService.CleanupResult.DELETED,files.discardUnreferenced(owner,id));
    }
    @ParameterizedTest @ValueSource(strings={"DRAFT","CONFIRMED","CANCELLED","DELETED"})
    void everyLegacyReferenceProtectsBytesEvenForeignOwnerUppercaseAndNoBinding(String status) {
        UUID id=UUID.randomUUID();upload(id);
        mongo.getCollection("entries").insertOne(new Document("_id",UUID.randomUUID().toString()).append("ownerId",UUID.randomUUID().toString())
                .append("status",status).append("sourceRef",new Document("file_id",id.toString().toUpperCase(Locale.ROOT))));
        assertNull(metadata(id).get("entryId"));assertEquals(FileStorageService.CleanupResult.PROTECTED,files.discardUnreferenced(owner,id));assertTrue(Files.exists(path(id)));
    }
    @Test void uncertainEntryWriteRemainsPinnedBeforeLateCompletion() throws Exception {
        UUID id=UUID.randomUUID();upload(id);AtomicReference<Entry> pending=new AtomicReference<>();
        doAnswer(i->{pending.set(i.getArgument(0));assertEquals("PINNED",metadata(id).getString("lifecycle"));throw new IllegalStateException("write ack unknown");}).when(store).save(any());
        assertThrows(IllegalStateException.class,()->entries.createDraft(command(id.toString(),1)));
        assertTrue(entries.findActiveDraft(owner).isEmpty());
        assertEquals(FileStorageService.CleanupResult.PROTECTED,files.discardUnreferenced(owner,id));assertTrue(Files.exists(path(id)));
        doCallRealMethod().when(store).save(any());store.save(pending.get());
        var replay=entries.createDraft(command(id.toString(),1));assertEquals(pending.get().id(),replay.entry().id());
        files.bindToEntry(owner,id,replay.entry().id());try(var opened=files.open(owner,id)){assertArrayEquals(bytes,opened.content().readAllBytes());}catch(IOException e){throw new UncheckedIOException(e);}
    }
    @Test void diskDeleteFailureLeavesRetryableIntentAndRestartedServiceCompletes() throws Exception {
        UUID id=UUID.randomUUID();upload(id);Files.delete(path(id));Files.createDirectory(path(id));Files.writeString(path(id).resolve("blocker"),"x");
        assertThrows(StoredFileUnavailableException.class,()->files.discardUnreferenced(owner,id));assertEquals("DELETING",metadata(id).getString("lifecycle"));
        assertThrows(StoredFileUnavailableException.class,()->upload(id));
        Files.delete(path(id).resolve("blocker"));Files.delete(path(id));
        var restarted=new DefaultFileStorageService(new org.springframework.data.mongodb.repository.support.MongoRepositoryFactory(mongo).getRepository(MongoStoredFileRepository.class),
                entries,java.time.Clock.systemUTC(),ROOT.toString(),new FileOperationGuard(ROOT.toString()),new StoredFileLifecycle(mongo),store);
        assertEquals(FileStorageService.CleanupResult.DELETED,restarted.discardUnreferenced(owner,id));assertEquals("DELETED",metadata(id).getString("lifecycle"));
    }
    @Test void invalidNewFileReferencesRejectButLegacyIdempotentEntryReturns() {
        for(Object bad:List.of("1-1-1-1-1","not-uuid",17))assertThrows(EntryValidationException.class,()->entries.createDraft(command(bad,1)));
        assertThrows(StoredFileNotFoundException.class,()->entries.createDraft(command(UUID.randomUUID().toString(),2)));
        UUID id=UUID.randomUUID();files.store(new OwnerContext(UUID.randomUUID()),id,new ByteArrayInputStream(bytes),bytes.length);
        assertThrows(StoredFileNotFoundException.class,()->entries.createDraft(command(id.toString(),3)));
        UUID owned=UUID.randomUUID();upload(owned);var saved=entries.createDraft(command(owned.toString().toUpperCase(Locale.ROOT),4)).entry();
        assertEquals(owned.toString(),saved.sourceRef().get("file_id"));
        mongo.getCollection("stored_files").deleteOne(new Document("_id",owned.toString()));
        assertEquals(saved,entries.createDraft(command(owned.toString(),4)).entry());
    }
}
