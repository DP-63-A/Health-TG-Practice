package org.healthtg.core.file;

import org.healthtg.core.entry.*;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.dao.DuplicateKeyException;
import java.io.*;
import java.nio.file.*;
import java.time.*;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicBoolean;
import java.awt.image.BufferedImage;
import javax.imageio.ImageIO;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

/** Repository failure windows are controlled; file writes use a real temporary directory. */
class ReservedFileStorageTest {
    @TempDir Path root;
    final OwnerContext owner=new OwnerContext(UUID.randomUUID());
    final UUID id=UUID.randomUUID();
    final Map<String,MongoStoredFileDocument> database=new ConcurrentHashMap<>();
    final MongoStoredFileRepository repository=mock(MongoStoredFileRepository.class);
    final EntryCoreService entries=mock(EntryCoreService.class);
    final AtomicBoolean failBefore=new AtomicBoolean(),failAfter=new AtomicBoolean();
    byte[] image;
    @BeforeEach void setup() throws Exception {
        image=image(0);
        when(repository.insert(any(MongoStoredFileDocument.class))).thenAnswer(i->{
            if(failBefore.getAndSet(false))throw new IllegalStateException("database unavailable before insert");
            MongoStoredFileDocument d=i.getArgument(0);
            if(database.putIfAbsent(d.id(),d)!=null)throw new DuplicateKeyException("duplicate");
            if(failAfter.getAndSet(false))throw new IllegalStateException("database acknowledgement lost");
            return d;
        });
        when(repository.findById(anyString())).thenAnswer(i->Optional.ofNullable(database.get(i.getArgument(0))));
        when(repository.findByIdAndOwnerId(anyString(),anyString())).thenAnswer(i->Optional.ofNullable(database.get(i.getArgument(0))).filter(d->d.ownerId().equals(i.getArgument(1))));
        when(repository.save(any(MongoStoredFileDocument.class))).thenAnswer(i->{MongoStoredFileDocument d=i.getArgument(0);database.put(d.id(),d);return d;});
    }
    DefaultFileStorageService service(){return new DefaultFileStorageService(repository,entries,Clock.systemUTC(),root.toString(),new FileOperationGuard(root.toString()),FileLifecycleUnitSupport.lifecycle(database),mock(EntryStore.class));}
    StoredFile store(){return service().store(owner,id,new ByteArrayInputStream(image),image.length);}
    Path target(){return root.resolve(id.toString().substring(0,2)).resolve(id+".bin");}
    long fileCount() throws Exception {try(var paths=Files.walk(root)){return paths.filter(Files::isRegularFile).filter(p->p.toString().endsWith(".bin")).count();}}
    static byte[] image(int rgb) throws Exception {
        var b=new BufferedImage(2,2,BufferedImage.TYPE_INT_RGB);b.setRGB(0,0,rgb);var out=new ByteArrayOutputStream();ImageIO.write(b,"png",out);return out.toByteArray();
    }
    Entry entry(UUID entryId){return new Entry(entryId,owner.userId(),EntryType.MEAL,EntryStatus.DRAFT,SourceKind.FOOD_PHOTO,Map.of("file_id",id.toString()),Instant.EPOCH,Instant.EPOCH,Instant.EPOCH,1,Map.of("description","meal"),Map.of(),null,"test:1");}

    @Test void databaseFailureBeforeInsertDoesNotWriteFileAndRetryCreatesOne() throws Exception {
        failBefore.set(true);assertThrows(IllegalStateException.class,this::store);assertTrue(database.isEmpty());assertEquals(0,fileCount());
        assertEquals(id,store().id());assertEquals(1,database.size());assertEquals(1,fileCount());assertArrayEquals(image,Files.readAllBytes(target()));
    }
    @Test void lostInsertAcknowledgementLeavesReservationWithoutBytesAndRetryCompletesIt() throws Exception {
        failAfter.set(true);assertThrows(IllegalStateException.class,this::store);assertEquals(1,database.size());assertEquals(0,fileCount());
        var metadata=database.get(id.toString());assertEquals(id,store().id());assertEquals(metadata,database.get(id.toString()));assertEquals(1,fileCount());
    }
    @Test void repeatedStoreKeepsSameMetadataAndOnlyOnePhysicalFile() throws Exception {
        var first=store();var again=store();assertEquals(first,again);assertEquals(1,fileCount());assertEquals(1,database.size());
        verify(repository,never()).save(any(MongoStoredFileDocument.class));
    }
    @Test void differentOwnerAndContentsCannotOverwriteReservedId() throws Exception {
        var first=store();var otherOwner=new OwnerContext(UUID.randomUUID());byte[] changed=image(0x123456);
        assertThrows(StoredFileNotFoundException.class,()->service().store(otherOwner,id,new ByteArrayInputStream(image),image.length));
        assertThrows(FileValidationException.class,()->service().store(owner,id,new ByteArrayInputStream(changed),changed.length));
        assertEquals(first,store());assertArrayEquals(image,Files.readAllBytes(target()));assertEquals(1,fileCount());
    }
    @Test void diskFailureCanRetrySameReservationAfterDiskBecomesAvailable() throws Exception {
        Path parent=target().getParent();Files.writeString(parent,"blocking file");
        assertThrows(StoredFileUnavailableException.class,this::store);assertEquals(1,database.size());
        Files.delete(parent);assertEquals(id,store().id());assertEquals(1,fileCount());assertArrayEquals(image,Files.readAllBytes(target()));
    }
    @Test void boundMetadataAndOwnerAccessSurviveStoreRetry() throws Exception {
        store();UUID entryId=UUID.randomUUID();when(entries.requireEntry(owner,entryId)).thenReturn(entry(entryId));
        var bound=service().bindToEntry(owner,id,entryId);var stored=store();assertEquals(bound,stored);assertEquals(entryId,stored.entryId());
        try(var opened=service().open(owner,id)){assertArrayEquals(image,opened.content().readAllBytes());}
        assertThrows(StoredFileNotFoundException.class,()->service().open(new OwnerContext(UUID.randomUUID()),id));
    }
    @Test void existingMismatchedDiskBytesAreNotSilentlyReplaced() throws Exception {
        store();byte[] corrupted={9,8,7};Files.write(target(),corrupted);
        assertThrows(StoredFileUnavailableException.class,this::store);assertArrayEquals(corrupted,Files.readAllBytes(target()));assertEquals(1,database.size());
    }
    @Test void twoServiceInstancesConcurrentlyStoreSameIdAndBytesOnce() throws Exception {
        var gate=new CyclicBarrier(2);
        try(var executor=Executors.newFixedThreadPool(2)){
            var one=executor.submit(()->{gate.await(5,TimeUnit.SECONDS);return store();});
            var two=executor.submit(()->{gate.await(5,TimeUnit.SECONDS);return store();});
            assertEquals(one.get(10,TimeUnit.SECONDS),two.get(10,TimeUnit.SECONDS));
        }
        assertEquals(1,database.size());assertEquals(1,fileCount());assertArrayEquals(image,Files.readAllBytes(target()));
    }
    @Test void invalidImageDoesNotReserveAnIdOrTouchDisk() throws Exception {
        assertThrows(FileValidationException.class,()->service().store(owner,id,new ByteArrayInputStream(new byte[]{1,2,3}),3));
        verify(repository,never()).insert(any(MongoStoredFileDocument.class));assertTrue(database.isEmpty());assertEquals(0,fileCount());
    }
    @Test void concurrentDifferentContentsCannotReplaceWinner() throws Exception {
        byte[] other=image(0x00ff00);var gate=new CyclicBarrier(2);
        try(var executor=Executors.newFixedThreadPool(2)){
            var tasks=new ArrayList<Future<byte[]>>();
            for(byte[] bytes:List.of(image,other))tasks.add(executor.submit(()->{
                gate.await(5,TimeUnit.SECONDS);
                try{service().store(owner,id,new ByteArrayInputStream(bytes),bytes.length);return bytes;}
                catch(FileValidationException expected){return null;}
            }));
            byte[] first=tasks.get(0).get(10,TimeUnit.SECONDS),second=tasks.get(1).get(10,TimeUnit.SECONDS);
            assertTrue((first==null)!=(second==null),"Exactly one content hash may reserve the ID");
            assertArrayEquals(first!=null?first:second,Files.readAllBytes(target()));
        }
        assertEquals(1,database.size());assertEquals(1,fileCount());
    }
}
