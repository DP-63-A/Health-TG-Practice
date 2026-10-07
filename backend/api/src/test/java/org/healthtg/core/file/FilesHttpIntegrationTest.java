package org.healthtg.core.file;

import org.healthtg.auth.AuthFailureException;
import org.healthtg.core.entry.CreateDraftCommand;
import org.healthtg.core.entry.EntryCoreService;
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

import javax.imageio.ImageIO;
import java.awt.image.BufferedImage;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.Comparator;
import java.util.Map;
import java.util.UUID;

import static org.hamcrest.Matchers.blankOrNullString;
import static org.hamcrest.Matchers.not;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest
@AutoConfigureMockMvc
@Testcontainers(disabledWithoutDocker = true)
class FilesHttpIntegrationTest {
    private static final Path ROOT = createRoot();
    private static final Instant NOW = Instant.parse("2026-10-07T10:00:00Z");

    @Container
    static final MongoDBContainer MONGO = new MongoDBContainer(
            DockerImageName.parse("mongodb/mongodb-community-server:8.0-ubi9-slim")
                    .asCompatibleSubstituteFor("mongo"));

    @DynamicPropertySource
    static void properties(DynamicPropertyRegistry registry) {
        registry.add("spring.data.mongodb.uri", MONGO::getReplicaSetUrl);
        registry.add("spring.data.mongodb.auto-index-creation", () -> "true");
        registry.add("health-tg.files.root", ROOT::toString);
    }

    @Autowired MockMvc mockMvc;
    @Autowired FileStorageService files;
    @Autowired EntryCoreService entries;
    @Autowired MongoTemplate mongo;
    @Autowired MongoStoredFileRepository repository;
    @MockitoBean SessionService sessions;

    @BeforeEach
    void clean() throws Exception {
        mongo.getDb().drop();
        if (Files.exists(ROOT)) {
            try (var paths = Files.walk(ROOT)) {
                paths.sorted(Comparator.reverseOrder()).filter(path -> !path.equals(ROOT))
                        .forEach(path -> path.toFile().delete());
            }
        }
        Files.createDirectories(ROOT);
    }

    @Test
    void downloadRequiresValidSessionAndDoesNotRevealForeignFiles() throws Exception {
        UUID ownerId = UUID.randomUUID();
        StoredFile stored = storeBound(ownerId);
        when(sessions.authenticate("owner-session")).thenReturn(ownerId);
        when(sessions.authenticate("foreign-session")).thenReturn(UUID.randomUUID());
        when(sessions.authenticate("expired-session"))
                .thenThrow(new AuthFailureException("Session missing or expired"));

        mockMvc.perform(get("/api/v1/files/{id}", stored.id()))
                .andExpect(status().isUnauthorized());
        mockMvc.perform(get("/api/v1/files/{id}", stored.id())
                        .header("Authorization", "Bearer expired-session"))
                .andExpect(status().isUnauthorized());
        mockMvc.perform(get("/api/v1/files/{id}", stored.id())
                        .header("Authorization", "Bearer foreign-session"))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("RESOURCE_NOT_FOUND"));
        mockMvc.perform(get("/api/v1/files/not-a-uuid")
                        .header("Authorization", "Bearer owner-session"))
                .andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.code").value("VALIDATION_ERROR"));
    }

    @Test
    void downloadUsesMongoMetadataAndReportsMissingBytesAsControlled503() throws Exception {
        UUID ownerId = UUID.randomUUID();
        StoredFile stored = storeBound(ownerId);
        when(sessions.authenticate("owner-session")).thenReturn(ownerId);

        mockMvc.perform(get("/api/v1/files/{id}", stored.id())
                        .header("Authorization", "Bearer owner-session"))
                .andExpect(status().isOk())
                .andExpect(content().contentType("image/png"))
                .andExpect(header().string("Cache-Control", "no-store"));

        var document = mongo.getCollection("stored_files")
                .find(new org.bson.Document("_id", stored.id().toString())).first();
        org.junit.jupiter.api.Assertions.assertNotNull(document);
        org.junit.jupiter.api.Assertions.assertEquals(stored.entryId().toString(), document.getString("entryId"));
        org.junit.jupiter.api.Assertions.assertNotNull(document.get("version"));

        try (var paths = Files.walk(ROOT)) {
            Path physical = paths.filter(Files::isRegularFile).findFirst().orElseThrow();
            Files.delete(physical);
        }
        mockMvc.perform(get("/api/v1/files/{id}", stored.id())
                        .header("Authorization", "Bearer owner-session"))
                .andExpect(status().isServiceUnavailable())
                .andExpect(jsonPath("$.code").value("FILE_UNAVAILABLE"))
                .andExpect(jsonPath("$.message").value("Stored file is unavailable"))
                .andExpect(jsonPath("$.request_id", not(blankOrNullString())));
    }

    @Test
    void mongoVersionRejectsStaleMetadataUpdate() throws Exception {
        StoredFile stored = storeBound(UUID.randomUUID());
        MongoStoredFileDocument current = repository.findById(stored.id().toString()).orElseThrow();
        MongoStoredFileDocument first = copy(current);
        MongoStoredFileDocument stale = copy(current);

        repository.save(first);

        assertThrows(org.springframework.dao.OptimisticLockingFailureException.class,
                () -> repository.save(stale));
    }

    private StoredFile storeBound(UUID ownerId) throws Exception {
        OwnerContext owner = new OwnerContext(ownerId);
        byte[] image = png();
        StoredFile stored = files.store(owner, new ByteArrayInputStream(image), image.length);
        var entry = entries.createDraft(new CreateDraftCommand(owner, EntryType.MEAL, SourceKind.FOOD_PHOTO,
                Map.of("file_id", stored.id().toString()), NOW, Map.of("description", "meal"),
                Map.of("description", "reported"), new TelegramUpdateKey("file-http-test", System.nanoTime()))).entry();
        return files.bindToEntry(owner, stored.id(), entry.id());
    }

    private static byte[] png() throws Exception {
        var output = new ByteArrayOutputStream();
        ImageIO.write(new BufferedImage(2, 2, BufferedImage.TYPE_INT_RGB), "png", output);
        return output.toByteArray();
    }

    private static MongoStoredFileDocument copy(MongoStoredFileDocument file) {
        return new MongoStoredFileDocument(file.id(), file.ownerId(), file.entryId(), file.relativePath(),
                file.mediaType(), file.extension(), file.size(), file.width(), file.height(), file.sha256(),
                file.createdAt(), file.version());
    }

    private static Path createRoot() {
        try {
            return Files.createTempDirectory("health-tg-files-http-");
        } catch (java.io.IOException failure) {
            throw new ExceptionInInitializerError(failure);
        }
    }
}
