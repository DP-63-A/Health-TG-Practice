package org.healthtg.bot.recognition;

import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import java.net.InetSocketAddress;
import java.net.URI;
import java.net.http.HttpClient;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import static org.junit.jupiter.api.Assertions.*;
import static org.healthtg.bot.recognition.RecognitionException.Code.*;

/** Real HTTP transport against loopback only: no Google key, account or external traffic. */
class GeminiRecognitionProviderTest {
    private HttpServer server;
    private final AtomicInteger requests=new AtomicInteger();
    private static final String KEY="fake-test-secret";
    @AfterEach void stop(){if(server!=null)server.stop(0);}
    private GeminiRecognitionProvider serve(int status,byte[] body,Duration timeout) throws Exception {
        server=HttpServer.create(new InetSocketAddress("127.0.0.1",0),0);
        server.createContext("/",exchange->{requests.incrementAndGet();exchange.getRequestBody().readAllBytes();exchange.sendResponseHeaders(status,body.length==0?-1:body.length);exchange.getResponseBody().write(body);exchange.close();});server.start();
        return provider(timeout);
    }
    private GeminiRecognitionProvider provider(Duration timeout) throws Exception {
        return new GeminiRecognitionProvider(HttpClient.newBuilder().followRedirects(HttpClient.Redirect.NEVER).build(),URI.create("http://127.0.0.1:"+server.getAddress().getPort()+"/generate"),KEY,timeout);
    }
    private static ImageValidator.ValidatedImage image(){return new ImageValidator.ValidatedImage(new byte[]{1,2,3},"image/png");}
    private static String envelope(String json) throws Exception {
        return RecognitionJson.MAPPER.writeValueAsString(Map.of("candidates",List.of(Map.of("finishReason","STOP","content",Map.of("parts",List.of(Map.of("text",json)))))));
    }
    private static byte[] utf8(String value){return value.getBytes(StandardCharsets.UTF_8);}

    @Test void requestContainsImageSchemaAndInjectionInstructionsButNoToolsFixtureOrSecretInBody() throws Exception {
        var body=new AtomicReference<String>();var key=new AtomicReference<String>();var contentType=new AtomicReference<String>();var method=new AtomicReference<String>();
        String response=envelope(RecognitionJson.resource("fixture.json"));
        server=HttpServer.create(new InetSocketAddress("127.0.0.1",0),0);
        server.createContext("/",e->{requests.incrementAndGet();body.set(new String(e.getRequestBody().readAllBytes(),StandardCharsets.UTF_8));key.set(e.getRequestHeaders().getFirst("x-goog-api-key"));contentType.set(e.getRequestHeaders().getFirst("Content-Type"));method.set(e.getRequestMethod());e.sendResponseHeaders(200,utf8(response).length);e.getResponseBody().write(utf8(response));e.close();});server.start();
        var result=provider(Duration.ofSeconds(2)).recognize(image());
        assertEquals(KEY,key.get());assertEquals("application/json",contentType.get());assertEquals("POST",method.get());assertEquals(1,requests.get());
        var request=RecognitionJson.MAPPER.readTree(body.get());
        assertEquals("image/png",request.at("/contents/0/parts/0/inlineData/mimeType").asText());assertEquals("AQID",request.at("/contents/0/parts/0/inlineData/data").asText());
        assertEquals("application/json",request.at("/generationConfig/responseMimeType").asText());
        assertEquals(RecognitionJson.MAPPER.readTree(RecognitionJson.resource("response-schema.json")),request.at("/generationConfig/responseJsonSchema"));
        assertFalse(request.has("tools"));assertFalse(body.get().contains(KEY));assertFalse(body.get().contains("яблоко"));
        assertTrue(request.at("/systemInstruction/parts/0/text").asText().contains("untrusted data"));
        assertNull(result.usage());assertNull(result.requestId());assertEquals(RecognitionJson.resource("fixture.json"),result.json());
    }
    @ParameterizedTest @ValueSource(ints={400,401,403,429,500,503,302})
    void httpErrorsAreNotRetriedOrReturnedAsFood(int status) throws Exception {
        var p=serve(status,utf8("private rejected content"),Duration.ofSeconds(2));
        var ex=assertThrows(RecognitionException.class,()->p.recognize(image()));assertEquals(HTTP_ERROR,ex.code());assertEquals("HTTP_ERROR",ex.getMessage());assertEquals(1,requests.get());
    }
    @ParameterizedTest @ValueSource(strings={"SAFETY","RECITATION","BLOCKLIST","PROHIBITED_CONTENT","SPII","IMAGE_SAFETY"})
    void blockedCandidateIsRefusal(String reason) throws Exception {
        String body="{\"candidates\":[{\"finishReason\":\""+reason+"\"}]}";
        var p=serve(200,utf8(body),Duration.ofSeconds(2));assertEquals(REFUSED,assertThrows(RecognitionException.class,()->p.recognize(image())).code());
    }
    @Test void promptBlockIsRefusalEvenWithoutCandidates() throws Exception {
        var p=serve(200,utf8("{\"promptFeedback\":{\"blockReason\":\"SAFETY\"}}"),Duration.ofSeconds(2));assertEquals(REFUSED,assertThrows(RecognitionException.class,()->p.recognize(image())).code());
    }
    @ParameterizedTest @ValueSource(strings={"", "{}", "{\"candidates\":[]}", "{\"candidates\":[{\"finishReason\":\"STOP\",\"content\":{\"parts\":[{\"text\":\"  \"}]}}]}"})
    void emptyCandidateIsExplicitError(String response) throws Exception {
        var p=serve(200,utf8(response),Duration.ofSeconds(2));assertEquals(EMPTY_RESPONSE,assertThrows(RecognitionException.class,()->p.recognize(image())).code());
    }
    @ParameterizedTest @ValueSource(strings={"null","[]","broken","{} {}","{\"candidates\":[],\"candidates\":[]}","{\"candidates\":[{\"finishReason\":\"MAX_TOKENS\"}]}"})
    void invalidEnvelopeIsNotSuccess(String response) throws Exception {
        var p=serve(200,utf8(response),Duration.ofSeconds(2));assertEquals(INVALID_RESPONSE,assertThrows(RecognitionException.class,()->p.recognize(image())).code());
    }
    @Test void usageOnlyIncludesAvailableValidCountsAndThoughtIsExcluded() throws Exception {
        String response="{\"responseId\":\"request-123\",\"usageMetadata\":{\"promptTokenCount\":0,\"candidatesTokenCount\":-1},\"candidates\":[{\"finishReason\":\"STOP\",\"content\":{\"parts\":[{\"thought\":true,\"text\":\"hidden reasoning\"},{\"text\":\"first\"},{\"text\":\"second\"}]}}]}";
        var result=serve(200,utf8(response),Duration.ofSeconds(2)).recognize(image());assertEquals("firstsecond",result.json());assertEquals("request-123",result.requestId());
        assertEquals(0L,result.usage().inputTokens());assertNull(result.usage().outputTokens());assertNull(result.usage().totalTokens());
    }
    @Test void networkErrorAndNoRetry() throws Exception {
        var p=serve(200,utf8("{}"),Duration.ofSeconds(2));server.stop(0);server=null;
        assertEquals(NETWORK,assertThrows(RecognitionException.class,()->p.recognize(image())).code());assertEquals(0,requests.get());
    }
    @Test void timeoutIncludesStalledBodyAndDoesNotRetry() throws Exception {
        var release=new CountDownLatch(1);var entered=new CountDownLatch(1);
        server=HttpServer.create(new InetSocketAddress("127.0.0.1",0),0);
        server.createContext("/",e->{requests.incrementAndGet();e.getRequestBody().readAllBytes();e.sendResponseHeaders(200,0);e.getResponseBody().write('{');e.getResponseBody().flush();entered.countDown();try{release.await(3,TimeUnit.SECONDS);}catch(InterruptedException ignored){Thread.currentThread().interrupt();}finally{e.close();}});server.start();
        var p=provider(Duration.ofMillis(300));long start=System.nanoTime();
        try{assertEquals(TIMEOUT,assertThrows(RecognitionException.class,()->p.recognize(image())).code());assertTrue(entered.await(1,TimeUnit.SECONDS));assertTrue(Duration.ofNanos(System.nanoTime()-start).toMillis()<2000);assertEquals(1,requests.get());}finally{release.countDown();}
    }
    @Test void oversizedResponseStopsDownloadWithSpecificError() throws Exception {
        var p=serve(200,new byte[RecognitionJson.MAX_RESPONSE_BYTES+1],Duration.ofSeconds(2));assertEquals(RESPONSE_TOO_LARGE,assertThrows(RecognitionException.class,()->p.recognize(image())).code());assertEquals(1,requests.get());
    }
    @Test void configurationRejectsMissingKeyModelInjectionAndUnboundedTimeout() {
        assertEquals(CONFIGURATION,assertThrows(RecognitionException.class,()->new GeminiRecognitionProvider(null,GeminiRecognitionProvider.DEFAULT_MODEL)).code());
        assertEquals(CONFIGURATION,assertThrows(RecognitionException.class,()->new GeminiRecognitionProvider(KEY,"../private")).code());
        assertEquals(CONFIGURATION,assertThrows(RecognitionException.class,()->new GeminiRecognitionProvider(HttpClient.newHttpClient(),URI.create("http://127.0.0.1"),KEY,Duration.ofSeconds(31))).code());
    }

    @ParameterizedTest @ValueSource(strings={"\u0000", "\t", "\n", "\r", "\u001f", "\u007f", "ё", "🍎", " "})
    void malformedKeyIsSafeConfigurationErrorBeforeAnyNetworkCall(String illegalCharacter) throws Exception {
        serve(200,utf8(envelope(RecognitionJson.resource("fixture.json"))),Duration.ofSeconds(2));
        String fakeKey=KEY+illegalCharacter+"suffix";
        var failure=assertThrows(RecognitionException.class,()->{
            var provider=new GeminiRecognitionProvider(HttpClient.newHttpClient(),
                    URI.create("http://127.0.0.1:"+server.getAddress().getPort()+"/generate"),fakeKey,Duration.ofSeconds(2));
            provider.recognize(image());
        });
        assertEquals(CONFIGURATION,failure.code());assertEquals(0,requests.get());
        // The full causal chain is safe to display; neither message nor nested cause leaks the fake secret.
        for(Throwable cause=failure;cause!=null;cause=cause.getCause()){
            assertFalse(String.valueOf(cause.getMessage()).contains(KEY));
            assertFalse(cause.toString().contains(fakeKey));
        }
    }
}
