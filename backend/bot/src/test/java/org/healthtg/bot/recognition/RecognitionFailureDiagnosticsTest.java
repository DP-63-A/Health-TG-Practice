package org.healthtg.bot.recognition;

import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.*;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.*;
import org.slf4j.LoggerFactory;
import java.net.*;
import java.net.http.HttpClient;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.concurrent.atomic.AtomicInteger;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;
import static org.healthtg.bot.recognition.RecognitionException.Code.*;

/** Independent AC5 regressions: real loopback HTTP and captured production diagnostics. */
class RecognitionFailureDiagnosticsTest {
    final Logger logger=(Logger)LoggerFactory.getLogger(FoodRecognitionService.class);
    final ListAppender<ILoggingEvent> logs=new ListAppender<>();
    HttpServer server;
    @BeforeEach void capture(){logs.start();logger.addAppender(logs);}
    @AfterEach void close(){logger.detachAppender(logs);logs.stop();if(server!=null)server.stop(0);}
    byte[] png() throws Exception{return ImageValidatorTest.image("png",2,2);}
    String log(RecognitionException.Code code){
        assertEquals(1,logs.list.size());var event=logs.list.getFirst();assertNull(event.getThrowableProxy());
        String value=event.getFormattedMessage();assertTrue(value.matches(".*operation=[a-f0-9-]{36} .*"));
        assertTrue(value.contains("status="+code));assertTrue(value.matches(".*duration_ms=[0-9]+ .*"));
        assertTrue(value.contains("cost=unknown"));assertFalse(value.contains("cost=0"));
        for(String secret:new String[]{"private-content", "fake-secret", "987654321", "Ёжик🍎"}) assertFalse(value.contains(secret),value);
        return value;
    }
    @ParameterizedTest @EnumSource(value=RecognitionException.Code.class,names={"NETWORK","TIMEOUT","IMAGE_TOO_LARGE","INVALID_IMAGE"})
    void failedDownloadHasOneSafeOperationAndNeverCallsProvider(RecognitionException.Code code){
        var provider=mock(RecognitionProvider.class);when(provider.mode()).thenReturn(RecognitionProvider.Mode.LIVE);
        var service=new FoodRecognitionService(new ImageValidator(),provider,new RecognitionResponseParser());
        var error=assertThrows(RecognitionException.class,()->service.recognizeLoaded(()->{throw new RecognitionException(code);},"watch_photo"));
        assertEquals(code,error.code());assertTrue(log(code).contains("stage=input"));
        try{verify(provider,never()).recognize(any());verify(provider,never()).recognize(any(),any());}catch(RecognitionException impossible){fail(impossible);}
    }
    @Test void corruptImageHasSafeOperationBeforeProvider(){
        var provider=mock(RecognitionProvider.class);when(provider.mode()).thenReturn(RecognitionProvider.Mode.FIXTURE);
        var service=new FoodRecognitionService(new ImageValidator(),provider,new RecognitionResponseParser());
        assertEquals(INVALID_IMAGE,assertThrows(RecognitionException.class,()->service.recognize("private-content Ёжик🍎 fake-secret 987654321".getBytes(StandardCharsets.UTF_8))).code());
        assertTrue(log(INVALID_IMAGE).contains("stage=input"));
        try{verify(provider,never()).recognize(any());}catch(RecognitionException impossible){fail(impossible);}
    }
    @ParameterizedTest @CsvSource({"200,REFUSED,prompt", "200,REFUSED,safety", "200,EMPTY_RESPONSE,empty", "200,INVALID_RESPONSE,length", "429,HTTP_ERROR,empty", "503,HTTP_ERROR,prompt"})
    void refusedEnvelopeRetainsAvailableUsageWithoutBodyOrFallback(int http,RecognitionException.Code code,String variant)throws Exception{
        String detail=switch(variant){case "prompt"->"\"promptFeedback\":{\"blockReason\":\"SAFETY\",\"blockReasonMessage\":\"private-content Ёжик🍎\"}";
            case "safety"->"\"candidates\":[{\"finishReason\":\"SAFETY\"}]";
            case "length"->"\"candidates\":[{\"finishReason\":\"MAX_TOKENS\"}]";default->"\"candidates\":[]";};
        var requests=new AtomicInteger();var provider=serve(http,"{\"responseId\":\"safe-request27\",\"usageMetadata\":{\"promptTokenCount\":12,\"candidatesTokenCount\":0,\"totalTokenCount\":12},"+detail+"}",requests);
        var service=new FoodRecognitionService(new ImageValidator(),provider,new RecognitionResponseParser());
        var error=assertThrows(RecognitionException.class,()->service.recognize(png(),"food_photo"));
        assertEquals(code,error.code());assertEquals(code.name(),error.getMessage());assertNull(error.getCause());
        assertEquals("safe-request27",error.requestId());assertEquals(new RecognitionProvider.Usage(12L,0L,12L),error.usage());
        String value=log(code);assertTrue(value.contains("inputTokens=12"));assertTrue(value.contains("request_id=safe-request27"));assertEquals(1,requests.get());
    }
    @ParameterizedTest @ValueSource(strings={"not JSON private-content Ёжик🍎", "{\"responseId\":\"bad\\r\\nprivate-content\",\"usageMetadata\":{\"promptTokenCount\":-1,\"candidatesTokenCount\":1.5,\"totalTokenCount\":\"12\"}}"})
    void untrustedErrorMetadataCannotForgeLogsOrInventUsage(String body)throws Exception{
        var requests=new AtomicInteger();var provider=serve(429,body,requests);
        var error=assertThrows(RecognitionException.class,()->new FoodRecognitionService(new ImageValidator(),provider,new RecognitionResponseParser()).recognize(png()));
        assertEquals(HTTP_ERROR,error.code());assertNull(error.requestId());
        if(error.usage()!=null){assertNull(error.usage().inputTokens());assertNull(error.usage().outputTokens());assertNull(error.usage().totalTokens());}
        String value=log(HTTP_ERROR);assertFalse(value.contains("\r"));assertFalse(value.contains("\n"));assertEquals(1,requests.get());
    }
    GeminiRecognitionProvider serve(int status,String body,AtomicInteger requests)throws Exception{
        server=HttpServer.create(new InetSocketAddress("127.0.0.1",0),0);byte[] bytes=body.getBytes(StandardCharsets.UTF_8);
        server.createContext("/",e->{requests.incrementAndGet();e.getRequestBody().readAllBytes();e.sendResponseHeaders(status,bytes.length);e.getResponseBody().write(bytes);e.close();});server.start();
        return new GeminiRecognitionProvider(HttpClient.newHttpClient(),URI.create("http://127.0.0.1:"+server.getAddress().getPort()),"fake-secret",Duration.ofSeconds(2));
    }
}
