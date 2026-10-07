package org.healthtg.acceptance;

import org.bson.Document;
import org.healthtg.core.entry.ConfirmEntryCommand;
import org.healthtg.core.entry.CreateDraftCommand;
import org.healthtg.core.entry.Entry;
import org.healthtg.core.entry.EntryCoreService;
import org.healthtg.core.entry.EntryStatus;
import org.healthtg.core.entry.EntryType;
import org.healthtg.core.entry.OwnerContext;
import org.healthtg.core.entry.SourceKind;
import org.healthtg.core.entry.TelegramUpdateKey;
import org.healthtg.session.SessionService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.data.mongodb.core.MongoTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.testcontainers.containers.MongoDBContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.utility.DockerImageName;

import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest
@AutoConfigureMockMvc
@Testcontainers
class Be1AcceptanceIntegrationTest {
    private static final UUID OWNER = UUID.fromString("11111111-1111-4111-8111-111111111101");
    private static final UUID OTHER = UUID.fromString("22222222-2222-4222-8222-222222222202");
    private static final Instant OCCURRED_AT = Instant.parse("2026-09-23T08:00:00Z");

    @Container
    static final MongoDBContainer MONGO = new MongoDBContainer(
            DockerImageName.parse("mongodb/mongodb-community-server:8.0-ubi9-slim")
                    .asCompatibleSubstituteFor("mongo"));

    @DynamicPropertySource
    static void mongoProperties(DynamicPropertyRegistry registry) {
        registry.add("spring.data.mongodb.uri", MONGO::getReplicaSetUrl);
        registry.add("spring.data.mongodb.auto-index-creation", () -> "true");
    }

    @Autowired MockMvc mockMvc;
    @Autowired EntryCoreService entries;
    @Autowired MongoTemplate mongo;
    @MockitoBean SessionService sessions;

    @BeforeEach
    void resetDatabase() {
        mongo.getCollectionNames()
                .forEach(collection -> mongo.getCollection(collection).deleteMany(new Document()));
        when(sessions.authenticate("owner-session")).thenReturn(OWNER);
        when(sessions.authenticate("other-session")).thenReturn(OTHER);
    }

    @Test
    void realRoutesDoNotRevealAnotherOwnersEntry() throws Exception {
        Entry own = draft(new OwnerContext(OWNER), 1001, 7000);
        Entry foreign = draft(new OwnerContext(OTHER), 2001, 9000);

        mockMvc.perform(get("/api/v1/entries/{id}", own.id())
                        .header("Authorization", "Bearer owner-session"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.id").value(own.id().toString()))
                .andExpect(jsonPath("$.user_id").value(OWNER.toString()));
        mockMvc.perform(get("/api/v1/entries/{id}", foreign.id())
                        .header("Authorization", "Bearer owner-session"))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("RESOURCE_NOT_FOUND"));
        mockMvc.perform(patch("/api/v1/entries/{id}", foreign.id())
                        .header("Authorization", "Bearer owner-session")
                        .contentType("application/json")
                        .content("{\"expected_revision\":1,\"payload\":{\"value\":1}}"))
                .andExpect(status().isNotFound());

        assertEquals(foreign, entries.requireEntry(new OwnerContext(OTHER), foreign.id()));
    }

    @Test
    void analyticsContainsOnlyAuthenticatedOwnersEntries() throws Exception {
        LocalDate referenceDate = LocalDate.now(ZoneOffset.UTC);
        Entry own = confirmedMetric(new OwnerContext(OWNER), 2501, 1200,
                referenceDate.minusDays(1).atTime(12, 0).toInstant(ZoneOffset.UTC));
        confirmedMetric(new OwnerContext(OTHER), 2502, 9000,
                referenceDate.minusDays(2).atTime(12, 0).toInstant(ZoneOffset.UTC));

        mockMvc.perform(get("/api/v1/analytics")
                        .queryParam("period", "days_7")
                        .queryParam("timezone", "UTC")
                        .header("Authorization", "Bearer owner-session"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.cards.steps.total").value(1200))
                .andExpect(jsonPath("$.cards.steps.days_with_data").value(1))
                .andExpect(jsonPath("$.series.steps.length()").value(1))
                .andExpect(jsonPath("$.series.steps[0].source.entry_id").value(own.id().toString()))
                .andExpect(jsonPath("$.sources.length()").value(1))
                .andExpect(jsonPath("$.sources[0].entry_id").value(own.id().toString()));
    }

    @Test
    void concurrentPatchOfOneRevisionHasOneSuccessOneConflictAndOneResult() throws Exception {
        Entry original = draft(new OwnerContext(OWNER), 3001, 7000);
        CountDownLatch ready = new CountDownLatch(2);
        CountDownLatch start = new CountDownLatch(1);

        try (var executor = Executors.newFixedThreadPool(2)) {
            var calls = List.of(8000, 9000).stream().map(value -> executor.submit(() -> {
                ready.countDown();
                start.await();
                return mockMvc.perform(patch("/api/v1/entries/{id}", original.id())
                                .header("Authorization", "Bearer owner-session")
                                .contentType("application/json")
                                .content("{\"expected_revision\":1,\"payload\":{\"value\":" + value + "}}"))
                        .andReturn().getResponse().getStatus();
            })).toList();
            assertTrue(ready.await(10, TimeUnit.SECONDS));
            start.countDown();
            List<Integer> statuses = calls.stream().map(future -> {
                try {
                    return future.get(10, TimeUnit.SECONDS);
                } catch (Exception failure) {
                    throw new AssertionError(failure);
                }
            }).sorted().toList();
            assertEquals(List.of(200, 409), statuses);
        }

        Entry current = entries.requireEntry(new OwnerContext(OWNER), original.id());
        assertEquals(2, current.revision());
        assertTrue(List.of(8000, 9000).contains(((Number) current.payload().get("value")).intValue()));
        assertEquals(1, mongo.getCollection("entries").countDocuments());
    }

    @Test
    void repeatedConfirmReturnsOneConfirmedDocument() throws Exception {
        Entry original = draft(new OwnerContext(OWNER), 4001, 7000);
        String body = "{\"expected_revision\":1,\"submission_id\":\"be1-06-confirm\"}";

        for (int attempt = 0; attempt < 2; attempt++) {
            mockMvc.perform(post("/api/v1/entries/{id}/confirm", original.id())
                            .header("Authorization", "Bearer owner-session")
                            .contentType("application/json").content(body))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.status").value("confirmed"))
                    .andExpect(jsonPath("$.revision").value(2));
        }

        assertEquals(EntryStatus.CONFIRMED,
                entries.requireEntry(new OwnerContext(OWNER), original.id()).status());
        assertEquals(1, mongo.getCollection("entries").countDocuments());
    }

    private Entry draft(OwnerContext owner, long updateId, int value) {
        return entries.createDraft(new CreateDraftCommand(owner, EntryType.METRICS, SourceKind.TEXT,
                Map.of(), OCCURRED_AT,
                Map.of("code", "steps", "value", value, "unit", "count", "local_date", "2026-09-23"),
                Map.of("code", "reported", "value", "reported", "unit", "reported",
                        "local_date", "reported"),
                new TelegramUpdateKey("be1-06", updateId))).entry();
    }

    private Entry confirmedMetric(OwnerContext owner, long updateId, int value, Instant measuredAt) {
        String localDate = measuredAt.atZone(ZoneOffset.UTC).toLocalDate().toString();
        Entry draft = entries.createDraft(new CreateDraftCommand(owner, EntryType.METRICS, SourceKind.TEXT,
                Map.of(), measuredAt,
                Map.of("code", "steps", "value", value, "unit", "count", "local_date", localDate),
                Map.of("code", "reported", "value", "reported", "unit", "reported",
                        "local_date", "reported"),
                new TelegramUpdateKey("be1-06-analytics", updateId))).entry();
        return entries.confirm(new ConfirmEntryCommand(owner, draft.id(),
                "be1-06-analytics-confirm-" + updateId, draft.revision()));
    }
}
