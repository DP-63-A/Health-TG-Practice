package org.healthtg.seed;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.healthtg.auth.AuthProperties;
import org.healthtg.core.entry.CheckinCategory;
import org.healthtg.core.entry.CreateCheckinCommand;
import org.healthtg.core.entry.EntryCoreService;
import org.healthtg.core.entry.OwnerContext;
import org.healthtg.core.entry.TelegramUpdateKey;
import org.healthtg.session.SessionService;
import org.healthtg.user.UserService;
import org.healthtg.user.UserStore;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.data.mongodb.core.MongoTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.testcontainers.containers.MongoDBContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.utility.DockerImageName;

import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest(properties = {
        "health-tg.seed.demo-environment=true",
        "health-tg.seed.allowed-database=health_tg_seed_test",
        "health-tg.seed.accounts.regular-telegram-id=1001",
        "health-tg.seed.accounts.irregular-telegram-id=1002",
        "health-tg.seed.accounts.incomplete-telegram-id=1003",
        "health-tg.auth.allowed-telegram-ids=1001,1002,1003,1004"
})
@AutoConfigureMockMvc
@Testcontainers(disabledWithoutDocker = true)
class SeedServiceIntegrationTest {
    private static final long SEED = 20260916L;
    private static final LocalDate START = LocalDate.of(2026, 9, 14);

    @Container
    static final MongoDBContainer MONGO = new MongoDBContainer(
            DockerImageName.parse("mongodb/mongodb-community-server:8.0-ubi9-slim")
                    .asCompatibleSubstituteFor("mongo"));

    @DynamicPropertySource
    static void mongoProperties(DynamicPropertyRegistry registry) {
        registry.add("spring.data.mongodb.uri", () -> MONGO.getReplicaSetUrl("health_tg_seed_test"));
        registry.add("spring.data.mongodb.auto-index-creation", () -> "true");
    }

    @Autowired SeedService seedService;
    @Autowired SeedProperties properties;
    @Autowired AuthProperties auth;
    @Autowired UserService users;
    @Autowired UserStore userStore;
    @Autowired EntryCoreService entries;
    @Autowired MongoTemplate mongo;
    @Autowired SessionService sessions;
    @Autowired MockMvc mockMvc;
    private final ObjectMapper json = new ObjectMapper();

    @BeforeEach
    void clean() {
        mongo.getDb().getCollection("entries").deleteMany(new org.bson.Document());
    }

    @Test
    void seedCreatesThreeProfilesAndRerunDoesNotChangeLogicalRecords() {
        SeedReport first = seedService.seed(SEED, START);
        Map<String, String> before = snapshot();
        SeedReport second = seedService.seed(SEED, START);

        assertEquals(first, second);
        assertEquals(before, snapshot());
        assertEquals(3, first.profiles().size());
        long expected = 0;
        for (SeedReport.ProfileReport profile : first.profiles()) {
            assertEquals(21, profile.days());
            expected += profile.logicalRecords();
        }
        assertEquals(expected, mongo.getCollection("entries").countDocuments());
        assertEquals(2, first.profiles().stream().mapToInt(SeedReport.ProfileReport::cancelledDrafts).sum());
        assertTrue(first.profiles().stream().anyMatch(p -> !p.emptyDays().isEmpty()
                && p.profile() == SeedProfile.INCOMPLETE));
    }

    @Test
    void apiExposesChangedRecordsAndExcludesCancelledDraftsAndEmptyDays() throws Exception {
        seedService.seed(SEED, START);
        for (SeedProfile profile : SeedProfile.values()) {
            UUID userId = users.findOrCreate(telegramId(profile)).id();
            String token = sessions.issue(userId).token();
            ZoneId zone = users.requireById(userId).timezone();
            assertEquals(ZoneId.of("Europe/Warsaw"), zone);

            List<JsonNode> confirmed = fetchAll(token, "confirmed");
            List<SeedRecord> expected = SyntheticProfileGenerator.generate(profile, SEED, START);
            assertEquals(expected.stream().filter(r -> !r.cancelledDraft()).count(), confirmed.size());
            assertTrue(fetchAll(token, "draft").isEmpty());

            Set<LocalDate> localDates = new HashSet<>();
            int revisionThree = 0;
            for (JsonNode entry : confirmed) {
                assertEquals("confirmed", entry.get("status").asText());
                assertEquals(userId.toString(), entry.get("user_id").asText());
                localDates.add(Instant.parse(entry.get("occurred_at").asText()).atZone(zone).toLocalDate());
                if (entry.get("revision").asInt() == 3) revisionThree++;
            }
            assertEquals(expected.stream().filter(r -> r.changed() && !r.cancelledDraft()).count(), revisionThree);
            for (int day = 0; day < 21; day++) {
                LocalDate date = START.plusDays(day);
                boolean expectedEmpty = expected.stream().noneMatch(r -> r.date().equals(date));
                assertEquals(!expectedEmpty, localDates.contains(date), profile + " " + date);
            }
        }
    }

    @Test
    void resetRemovesOnlySeedEntriesOfConfiguredDemoAccounts() {
        seedService.seed(SEED, START);
        UUID demoUser = users.findOrCreate(1001).id();
        UUID otherUser = users.findOrCreate(1004).id();
        entries.createCheckin(new CreateCheckinCommand(new OwnerContext(otherUser), CheckinCategory.MOOD, 3,
                Instant.parse("2026-09-20T10:00:00Z"), new TelegramUpdateKey("main", 7)));
        entries.createCheckin(new CreateCheckinCommand(new OwnerContext(demoUser), CheckinCategory.MOOD, 3,
                Instant.parse("2026-09-20T10:00:00Z"), new TelegramUpdateKey("main", 8)));

        long removed = seedService.reset();

        assertTrue(removed > 0);
        assertEquals(2, mongo.getCollection("entries").countDocuments());
        assertEquals(0, mongo.getCollection("entries").countDocuments(
                new org.bson.Document("telegramUpdateKey", new org.bson.Document("$regex", "^seed:"))));
        assertEquals(0, seedService.reset());
        seedService.seed(SEED, START);
        assertEquals(removed + 2, mongo.getCollection("entries").countDocuments());
    }

    @Test
    void resetAndSeedAreRefusedOutsideDemoEnvironmentWithoutChangingData() {
        seedService.seed(SEED, START);
        long before = mongo.getCollection("entries").countDocuments();

        SeedProperties notDemo = new SeedProperties(false, properties.allowedDatabase(), SEED, START,
                properties.accounts());
        SeedProperties wrongDatabase = new SeedProperties(true, "some_other_database", SEED, START,
                properties.accounts());
        SeedProperties noDatabase = new SeedProperties(true, "", SEED, START, properties.accounts());
        for (SeedProperties refused : List.of(notDemo, wrongDatabase, noDatabase)) {
            SeedService guarded = new SeedService(refused, auth, users, userStore, entries, mongo);
            assertThrows(SeedRefusedException.class, guarded::reset);
            assertThrows(SeedRefusedException.class, guarded::seed);
            assertEquals(before, mongo.getCollection("entries").countDocuments());
        }
    }

    @Test
    void accountsMustBeConfiguredAllowlistedAndDistinct() {
        SeedProperties.Accounts missing = new SeedProperties.Accounts("1001", "", "1003");
        SeedProperties.Accounts notAllowed = new SeedProperties.Accounts("1001", "1002", "9999");
        SeedProperties.Accounts duplicate = new SeedProperties.Accounts("1001", "1001", "1003");
        for (SeedProperties.Accounts accounts : List.of(missing, notAllowed, duplicate)) {
            SeedService service = new SeedService(new SeedProperties(true, properties.allowedDatabase(), SEED, START,
                    accounts), auth, users, userStore, entries, mongo);
            assertThrows(SeedRefusedException.class, () -> service.seed());
            assertEquals(0, mongo.getCollection("entries").countDocuments());
        }
    }

    @Test
    void seedDoesNotTouchAnUnrelatedActiveDraft() {
        UUID owner = users.findOrCreate(1001).id();
        entries.createDraft(new org.healthtg.core.entry.CreateDraftCommand(new OwnerContext(owner),
                org.healthtg.core.entry.EntryType.NOTE, org.healthtg.core.entry.SourceKind.TEXT, Map.of(),
                Instant.parse("2026-09-14T06:00:00Z"), Map.of("text", "real draft"), Map.of("text", "reported"),
                new TelegramUpdateKey("main", 1)));
        assertThrows(IllegalStateException.class, () -> seedService.seed(SEED, START));
        assertTrue(entries.findActiveDraft(new OwnerContext(owner)).isPresent());
    }

    private long telegramId(SeedProfile profile) {
        return switch (profile) {
            case REGULAR -> 1001;
            case IRREGULAR -> 1002;
            case INCOMPLETE -> 1003;
        };
    }

    private Map<String, String> snapshot() {
        Map<String, String> result = new HashMap<>();
        mongo.getCollection("entries").find().forEach(document -> result.put(document.getString("telegramUpdateKey"),
                document.getString("_id") + "/" + document.get("revision") + "/" + document.getString("status")));
        return result;
    }

    private List<JsonNode> fetchAll(String token, String status) throws Exception {
        List<JsonNode> all = new ArrayList<>();
        String cursor = null;
        do {
            var request = get("/api/v1/entries").queryParam("status", status).queryParam("limit", "100")
                    .header("Authorization", "Bearer " + token);
            if (cursor != null) request.queryParam("cursor", cursor);
            JsonNode body = json.readTree(mockMvc.perform(request).andExpect(status().isOk()).andReturn().getResponse().getContentAsString());
            body.get("items").forEach(all::add);
            cursor = body.get("next_cursor").isNull() ? null : body.get("next_cursor").asText();
        } while (cursor != null);
        assertFalse(status.equals("confirmed") && all.isEmpty());
        return all;
    }
}
