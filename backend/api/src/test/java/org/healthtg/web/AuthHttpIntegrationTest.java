package org.healthtg.web;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.healthtg.session.SessionRecord;
import org.healthtg.session.SessionStore;
import org.healthtg.user.UserAccount;
import org.healthtg.user.UserStore;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Primary;
import org.springframework.http.MediaType;
import org.springframework.dao.DataAccessResourceFailureException;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.context.bean.override.mockito.MockitoBean;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.HashMap;
import java.util.HexFormat;
import java.util.Map;
import java.util.Optional;
import java.util.TreeMap;
import java.util.UUID;
import java.util.stream.Collectors;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT, properties = {
        "health-tg.auth.telegram-bot-token=test-bot-token",
        "health-tg.auth.allowed-telegram-ids=10001",
        "health-tg.auth.init-data-ttl=15m",
        "health-tg.auth.session-ttl=60m",
        "spring.data.mongodb.auto-index-creation=false"
})
@AutoConfigureMockMvc
class AuthHttpIntegrationTest {
    private static final String BOT_TOKEN = "test-bot-token";
    private static final Instant START = Instant.parse("2026-09-22T12:00:00Z");

    @Autowired MockMvc mockMvc;
    @Autowired ObjectMapper objectMapper;
    @Autowired MutableClock clock;
    @Autowired TestRestTemplate http;

    @MockitoBean UserStore userStore;
    @MockitoBean SessionStore sessionStore;

    private final Map<Long, UserAccount> usersByTelegramId = new HashMap<>();
    private final Map<UUID, UserAccount> usersById = new HashMap<>();
    private final Map<String, SessionRecord> sessions = new HashMap<>();

    @BeforeEach
    void setUpStores() {
        clock.set(START);
        usersByTelegramId.clear();
        usersById.clear();
        sessions.clear();

        when(userStore.findByTelegramId(anyLong()))
                .thenAnswer(invocation -> Optional.ofNullable(usersByTelegramId.get(invocation.getArgument(0))));
        when(userStore.findById(any(UUID.class)))
                .thenAnswer(invocation -> Optional.ofNullable(usersById.get(invocation.getArgument(0))));
        when(userStore.save(any(UserAccount.class))).thenAnswer(invocation -> {
            UserAccount user = invocation.getArgument(0);
            usersByTelegramId.put(user.telegramId(), user);
            usersById.put(user.id(), user);
            return user;
        });
        when(sessionStore.findByTokenHash(any(String.class)))
                .thenAnswer(invocation -> Optional.ofNullable(sessions.get(invocation.getArgument(0))));
        when(sessionStore.save(any(SessionRecord.class))).thenAnswer(invocation -> {
            SessionRecord session = invocation.getArgument(0);
            sessions.put(session.tokenHash(), session);
            return session;
        });
    }

    @Test
    void validTelegramLoginCreatesSessionAndMeReturnsTheSameUser() throws Exception {
        String response = mockMvc.perform(post("/api/v1/auth/telegram")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(authBody(signedInitData(10001, START))))
                .andExpect(status().isOk())
                .andExpect(header().exists(RequestIdFilter.HEADER))
                .andExpect(jsonPath("$.token_type").value("Bearer"))
                .andExpect(jsonPath("$.expires_in").value(3600))
                .andExpect(jsonPath("$.user.telegram_id").value(10001))
                .andExpect(jsonPath("$.user.timezone").value("Europe/Warsaw"))
                .andExpect(jsonPath("$.user.stand_access").value(true))
                .andReturn().getResponse().getContentAsString();

        JsonNode auth = objectMapper.readTree(response);
        String token = auth.path("session_token").asText();
        String userId = auth.path("user").path("id").asText();

        mockMvc.perform(get("/api/v1/me").header("Authorization", "Bearer " + token))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.id").value(userId))
                .andExpect(jsonPath("$.telegram_id").value(10001))
                .andExpect(jsonPath("$.timezone").value("Europe/Warsaw"));
    }

    @Test
    void rejectsTamperedOrExpiredInitDataWithoutCreatingSession() throws Exception {
        String valid = signedInitData(10001, START);
        String tampered = valid.replace("10001", "10002");

        expectError(post("/api/v1/auth/telegram")
                .contentType(MediaType.APPLICATION_JSON).content(authBody(tampered)), 401, "UNAUTHORIZED");
        expectError(post("/api/v1/auth/telegram")
                .contentType(MediaType.APPLICATION_JSON)
                .content(authBody(signedInitData(10001, START.minusSeconds(901)))), 401, "UNAUTHORIZED");
    }

    @Test
    void rejectsAccountOutsideAllowlist() throws Exception {
        expectError(post("/api/v1/auth/telegram")
                .contentType(MediaType.APPLICATION_JSON)
                .content(authBody(signedInitData(20002, START))), 403, "FORBIDDEN");
    }

    @Test
    void rejectsMissingAndUnknownRequestFields() throws Exception {
        expectError(post("/api/v1/auth/telegram")
                .contentType(MediaType.APPLICATION_JSON).content("{}"), 422, "VALIDATION_ERROR");
        String bodyWithUnknownField = "{\"init_data\":"
                + objectMapper.writeValueAsString(signedInitData(10001, START)) + ",\"user_id\":10001}";
        expectError(post("/api/v1/auth/telegram")
                .contentType(MediaType.APPLICATION_JSON).content(bodyWithUnknownField), 422, "VALIDATION_ERROR");
    }

    @Test
    void protectedRouteRejectsMissingChangedAndExpiredSession() throws Exception {
        expectError(get("/api/v1/me"), 401, "UNAUTHORIZED");

        String token = loginAndReadToken();
        expectError(get("/api/v1/me").header("Authorization", "Bearer " + token + "changed"),
                401, "UNAUTHORIZED");

        clock.set(START.plusSeconds(3600));
        expectError(get("/api/v1/me").header("Authorization", "Bearer " + token),
                401, "UNAUTHORIZED");
    }

    @Test
    void returnsServiceUnavailableWhenSessionStoreIsUnavailable() throws Exception {
        when(sessionStore.findByTokenHash(any(String.class)))
                .thenThrow(new DataAccessResourceFailureException("MongoDB unavailable"));

        expectError(get("/api/v1/me").header("Authorization", "Bearer opaque-token"),
                503, "SERVICE_UNAVAILABLE");
    }

    @Test
    void missingProtectedRouteReturns404WithoutInvalidatingAnAuthenticatedSession() throws Exception {
        String token = loginAndReadToken();
        HttpHeaders headers = new HttpHeaders();
        headers.setBearerAuth(token);
        HttpEntity<Void> request = new HttpEntity<>(headers);

        var missing = http.exchange("/api/v1/not-a-route", HttpMethod.GET, request, String.class);
        org.junit.jupiter.api.Assertions.assertEquals(404, missing.getStatusCode().value());
        var me = http.exchange("/api/v1/me", HttpMethod.GET, request, String.class);
        org.junit.jupiter.api.Assertions.assertEquals(200, me.getStatusCode().value());
        var anonymous = http.getForEntity("/api/v1/me", String.class);
        org.junit.jupiter.api.Assertions.assertEquals(401, anonymous.getStatusCode().value());
    }

    private String loginAndReadToken() throws Exception {
        String response = mockMvc.perform(post("/api/v1/auth/telegram")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(authBody(signedInitData(10001, START))))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();
        return objectMapper.readTree(response).path("session_token").asText();
    }

    private void expectError(org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder request,
                             int statusCode, String code) throws Exception {
        mockMvc.perform(request)
                .andExpect(status().is(statusCode))
                .andExpect(header().exists(RequestIdFilter.HEADER))
                .andExpect(jsonPath("$.code").value(code))
                .andExpect(jsonPath("$.message").isNotEmpty())
                .andExpect(jsonPath("$.request_id").isNotEmpty());
    }

    private String authBody(String initData) throws Exception {
        return objectMapper.writeValueAsString(Map.of("init_data", initData));
    }

    private static String signedInitData(long telegramId, Instant authDate) {
        TreeMap<String, String> fields = new TreeMap<>();
        fields.put("auth_date", Long.toString(authDate.getEpochSecond()));
        fields.put("query_id", "query-1");
        fields.put("user", "{\"id\":" + telegramId + ",\"first_name\":\"Not stored\"}");
        String check = fields.entrySet().stream()
                .map(entry -> entry.getKey() + "=" + entry.getValue())
                .collect(Collectors.joining("\n"));
        byte[] secret = hmac("WebAppData".getBytes(StandardCharsets.UTF_8),
                BOT_TOKEN.getBytes(StandardCharsets.UTF_8));
        fields.put("hash", HexFormat.of().formatHex(hmac(secret, check.getBytes(StandardCharsets.UTF_8))));
        return fields.entrySet().stream()
                .map(entry -> encode(entry.getKey()) + "=" + encode(entry.getValue()))
                .collect(Collectors.joining("&"));
    }

    private static byte[] hmac(byte[] key, byte[] value) {
        try {
            Mac mac = Mac.getInstance("HmacSHA256");
            mac.init(new SecretKeySpec(key, "HmacSHA256"));
            return mac.doFinal(value);
        } catch (Exception exception) {
            throw new AssertionError(exception);
        }
    }

    private static String encode(String value) {
        return URLEncoder.encode(value, StandardCharsets.UTF_8);
    }

    @TestConfiguration
    static class ClockConfiguration {
        @Bean
        @Primary
        MutableClock mutableClock() {
            return new MutableClock(START);
        }
    }

    static final class MutableClock extends Clock {
        private Instant instant;

        MutableClock(Instant instant) {
            this.instant = instant;
        }

        void set(Instant instant) {
            this.instant = instant;
        }

        @Override
        public ZoneId getZone() {
            return ZoneOffset.UTC;
        }

        @Override
        public Clock withZone(ZoneId zone) {
            return this;
        }

        @Override
        public Instant instant() {
            return instant;
        }
    }
}
