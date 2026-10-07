package org.healthtg.bot.recognition;

import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.slf4j.LoggerFactory;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.concurrent.atomic.AtomicInteger;
import static org.junit.jupiter.api.Assertions.*;
import static org.healthtg.bot.recognition.RecognitionException.Code.*;

class FoodRecognitionServiceTest {
    @TempDir Path temp;
    private Path image() throws Exception {var p=temp.resolve("private-photo-Ёжик🍎.png");Files.write(p,ImageValidatorTest.image("png",2,2));return p;}
    @Test void fixtureWorksWithoutKeyAndLogsOnlyMetadata() throws Exception {
        Logger logger=(Logger)LoggerFactory.getLogger(FoodRecognitionService.class);var appender=new ListAppender<ILoggingEvent>();appender.start();logger.addAppender(appender);
        try {
            var result=new FoodRecognitionService(new ImageValidator(),FixtureRecognitionProvider.bundled(),new RecognitionResponseParser()).recognize(image());
            assertEquals(RecognitionProvider.Mode.FIXTURE,result.mode());assertTrue(result.result().needsClarification());assertNull(result.usage());assertNull(result.requestId());assertEquals("unknown",result.cost());
            assertNotNull(java.util.UUID.fromString(result.operationId()));assertEquals(1,appender.list.size());String log=appender.list.getFirst().getFormattedMessage();
            assertTrue(log.contains("operation="+result.operationId()));assertTrue(log.contains("mode=FIXTURE"));assertTrue(log.contains("status=NEEDS_CLARIFICATION"));assertTrue(log.contains("duration_ms="));assertTrue(log.contains("cost=unknown"));
            assertFalse(log.contains("яблоко"));assertFalse(log.contains("private-photo"));assertFalse(log.contains("is_food"));
        } finally{logger.detachAppender(appender);appender.stop();}
    }
    @Test void liveFailureNeverReturnsFixtureOrRetriesAndLogsCategory() throws Exception {
        var calls=new AtomicInteger();RecognitionProvider broken=new RecognitionProvider(){
            public Mode mode(){return Mode.LIVE;}public Response recognize(ImageValidator.ValidatedImage input)throws RecognitionException{calls.incrementAndGet();throw new RecognitionException(TIMEOUT);}
        };
        Logger logger=(Logger)LoggerFactory.getLogger(FoodRecognitionService.class);var appender=new ListAppender<ILoggingEvent>();appender.start();logger.addAppender(appender);
        try {
            Path p=image();var service=new FoodRecognitionService(new ImageValidator(),broken,new RecognitionResponseParser());
            assertEquals(TIMEOUT,assertThrows(RecognitionException.class,()->service.recognize(p)).code());assertEquals(1,calls.get());
            assertEquals(1,appender.list.size());String log=appender.list.getFirst().getFormattedMessage();assertTrue(log.contains("mode=LIVE"));assertTrue(log.contains("status=TIMEOUT"));assertFalse(log.contains("private-photo"));
        } finally{logger.detachAppender(appender);appender.stop();}
    }
    @Test void invalidModelResultIsNotSuccessfulAndUsageIsRetainedOnlyAsMetadata() throws Exception {
        RecognitionProvider invalid=new RecognitionProvider(){
            public Mode mode(){return Mode.LIVE;}public Response recognize(ImageValidator.ValidatedImage input){return new Response("private invalid answer", "safe-request-id", new Usage(3L,null,null));}
        };
        Logger logger=(Logger)LoggerFactory.getLogger(FoodRecognitionService.class);var appender=new ListAppender<ILoggingEvent>();appender.start();logger.addAppender(appender);
        try {
            Path p=image();var service=new FoodRecognitionService(new ImageValidator(),invalid,new RecognitionResponseParser());
            assertEquals(INVALID_RESPONSE,assertThrows(RecognitionException.class,()->service.recognize(p)).code());
            String log=appender.list.getFirst().getFormattedMessage();assertTrue(log.contains("status=INVALID_RESPONSE"));assertTrue(log.contains("inputTokens=3"));assertFalse(log.contains("private invalid answer"));
        } finally{logger.detachAppender(appender);appender.stop();}
    }
}
