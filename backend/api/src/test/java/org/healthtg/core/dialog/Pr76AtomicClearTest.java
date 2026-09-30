package org.healthtg.core.dialog;

import com.mongodb.client.MongoClient;
import com.mongodb.client.MongoClients;
import org.healthtg.core.entry.*;
import org.junit.jupiter.api.*;
import org.springframework.context.annotation.*;
import org.springframework.data.mongodb.core.MongoTemplate;
import org.springframework.data.mongodb.repository.config.EnableMongoRepositories;
import org.testcontainers.containers.MongoDBContainer;
import org.testcontainers.junit.jupiter.*;
import org.testcontainers.utility.DockerImageName;
import java.time.*;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;
import static org.mockito.AdditionalAnswers.delegatesTo;

/** Exercises real Spring Data optimistic locking, with deterministic competing write. */
@Testcontainers
class Pr76AtomicClearTest {
    @Container static final MongoDBContainer MONGO = new MongoDBContainer(
            DockerImageName.parse("mongodb/mongodb-community-server:8.0-ubi9-slim").asCompatibleSubstituteFor("mongo"));
    @Configuration @EnableMongoRepositories(basePackageClasses = MongoDialogStateRepository.class)
    static class Config {
        @Bean MongoClient mongoClient() { return MongoClients.create(MONGO.getReplicaSetUrl()); }
        @Bean MongoTemplate mongoTemplate(MongoClient client) { return new MongoTemplate(client, "pr76_atomic_" + UUID.randomUUID().toString().replace("-", "")); }
    }
    AnnotationConfigApplicationContext context;
    MongoDialogStateRepository repository;
    DefaultDialogStateService service;
    OwnerContext owner;
    UUID entry;
    @BeforeEach void open() {
        context = new AnnotationConfigApplicationContext(Config.class);
        repository = context.getBean(MongoDialogStateRepository.class);
        service = new DefaultDialogStateService(repository, mock(EntryStore.class), Clock.systemUTC());
        owner = new OwnerContext(UUID.randomUUID()); entry = UUID.randomUUID();
        repository.save(new MongoDialogStateDocument(owner.userId().toString(), entry.toString(), "draft_review",
                Map.of("entry_revision", 3), 7, Instant.now(), "main:20", Map.of("main", 20L), null));
    }
    @AfterEach void close() { context.getBean(MongoTemplate.class).getDb().drop(); context.close(); }
    @Test void matchingDialogRevisionClearsEvenWhenEntryRevisionDiffers() {
        assertTrue(service.clearIfCurrent(owner, entry, 7, new TelegramUpdateKey("main", 21)));
        DialogState after = service.find(owner).orElseThrow();
        assertEquals("idle", after.step()); assertNull(after.activeEntryId()); assertEquals(8, after.revision());
    }
    @Test void mismatchedOwnerEntryAndRevisionDoNotClear() {
        DialogState before = service.find(owner).orElseThrow();
        assertFalse(service.clearIfCurrent(new OwnerContext(UUID.randomUUID()), entry, 7, new TelegramUpdateKey("main", 21)));
        assertFalse(service.clearIfCurrent(owner, UUID.randomUUID(), 7, new TelegramUpdateKey("main", 21)));
        assertFalse(service.clearIfCurrent(owner, entry, 3, new TelegramUpdateKey("main", 21)));
        assertEquals(before, service.find(owner).orElseThrow());
    }
    @Test void duplicateAndOlderUpdateDoNotAdvanceRevision() {
        DialogState before = service.find(owner).orElseThrow();
        assertFalse(service.clearIfCurrent(owner, entry, 7, new TelegramUpdateKey("main", 20)));
        assertFalse(service.clearIfCurrent(owner, entry, 7, new TelegramUpdateKey("main", 19)));
        assertEquals(before, service.find(owner).orElseThrow());
    }
    @Test void competingWriteBetweenReadAndSaveWinsWithoutBlindRetry() {
        MongoDialogStateRepository raced = mock(MongoDialogStateRepository.class, delegatesTo(repository));
        doAnswer(invocation -> {
            MongoDialogStateDocument observed = repository.findById(owner.userId().toString()).orElseThrow();
            repository.save(new MongoDialogStateDocument(observed.ownerId(), null, "text_clarification",
                    Map.of("new", "dialog"), 8, Instant.now(), "main:21", Map.of("main", 21L), observed.mongoVersion()));
            return repository.save(invocation.getArgument(0)); // genuine Mongo stale @Version exception
        }).when(raced).save(any(MongoDialogStateDocument.class));
        var subject = new DefaultDialogStateService(raced, mock(EntryStore.class), Clock.systemUTC());
        assertFalse(subject.clearIfCurrent(owner, entry, 7, new TelegramUpdateKey("main", 22)));
        verify(raced, times(1)).save(any(MongoDialogStateDocument.class));
        DialogState after = service.find(owner).orElseThrow();
        assertEquals("text_clarification", after.step()); assertEquals(Map.of("new", "dialog"), after.context());
        assertEquals(8, after.revision()); assertEquals("main:21", after.telegramUpdateKey());
    }
}

