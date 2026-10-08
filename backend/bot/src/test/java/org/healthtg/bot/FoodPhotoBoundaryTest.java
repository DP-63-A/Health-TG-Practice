package org.healthtg.bot;

import org.healthtg.bot.recognition.*;
import org.healthtg.bot.visual.*;
import org.healthtg.bot.draft.DraftReviewFlow;
import org.healthtg.core.entry.*;
import org.healthtg.core.dialog.*;
import org.healthtg.core.file.*;
import org.junit.jupiter.api.Test;
import org.telegram.telegrambots.meta.api.methods.GetFile;
import org.telegram.telegrambots.meta.generics.TelegramClient;
import java.time.*;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class FoodPhotoBoundaryTest {
    BotUpdate image(long sender,long chat,BotUpdate.ChatType type,boolean bot){return new BotUpdate(10,BotUpdate.Kind.MESSAGE,type,chat,sender,bot,null,List.of(),null,null,Instant.EPOCH,new BotUpdate.Image("file",10L,"image/png",false));}
    @Test void imageAccessRejectedBeforeFlowForAllUntrustedIdentities(){
        BotFlow flow=mock(BotFlow.class); var h=new BotHandler(new BotSettings(Set.of(1001L),"demo_bot",null),id->"",flow);
        for(var u:List.of(image(2002,2002,BotUpdate.ChatType.PRIVATE,false),image(1001,1001,BotUpdate.ChatType.GROUP,false),
                image(1001,2002,BotUpdate.ChatType.PRIVATE,false),image(1001,1001,BotUpdate.ChatType.PRIVATE,true)))assertTrue(h.handle(u).isEmpty());
        verifyNoInteractions(flow);
        h.handle(image(1001,1001,BotUpdate.ChatType.PRIVATE,false)); verify(flow).handleMessage(any());
    }
    @Test void albumsWrongMimeAndDeclaredOversizeNeverLoadOrRecognize() throws Exception {
        var e=mock(EntryCoreService.class);var d=mock(DialogStateService.class);var f=mock(FileStorageService.class);
        var provider=mock(RecognitionProvider.class);when(provider.mode()).thenReturn(RecognitionProvider.Mode.FIXTURE);
        var r=new FoodRecognitionService(new ImageValidator(),provider,new RecognitionResponseParser());var l=mock(FoodPhotoFlow.ImageLoader.class);
        var flow=new FoodPhotoFlow(e,d,f,r,l,new DraftReviewFlow(e,d,null),"FIXTURE");
        var owner=new OwnerContext(UUID.randomUUID()); when(d.find(owner)).thenReturn(Optional.empty());
        for(var i:List.of(new BotUpdate.Image("f",1L,"image/png",true),new BotUpdate.Image("f",1L,"application/pdf",false),
                new BotUpdate.Image("f",(long)ImageValidator.MAX_BYTES+1,"image/jpeg",false))) {
            var u=image(1001,1001,BotUpdate.ChatType.PRIVATE,false);
            assertFalse(flow.message(new BotUpdate(u.updateId(),u.kind(),u.chatType(),u.chatId(),u.senderId(),false,null,List.of(),null,null,Instant.EPOCH,i),owner,ZoneOffset.UTC).isEmpty());
        }
        verifyNoInteractions(e,f,l);verify(provider,never()).recognize(any());verify(provider,never()).recognize(any(),anyString());verify(d,never()).save(any());
    }
    @Test void loaderRejectsUntrustedPathsWithoutLeakingTokenOrRequestingNetwork() throws Exception {
        var telegram=mock(TelegramClient.class); var loader=new TelegramImageLoader(telegram,"synthetic-secret");
        for(String path:List.of("https://evil.test/x","../secret","/absolute","photos/../../x","photos/x?secret")) {
            var file=new org.telegram.telegrambots.meta.api.objects.File();file.setFilePath(path);
            when(telegram.execute(any(GetFile.class))).thenReturn(file);
            var error=assertThrows(RecognitionException.class,()->loader.load("synthetic"));
            assertEquals(RecognitionException.Code.INVALID_IMAGE,error.code());assertFalse(error.toString().contains("synthetic-secret"));
        }
    }
    @Test void loaderRejectsTelegramMetadataOversizeBeforeNetwork() throws Exception {
        var telegram=mock(TelegramClient.class);var file=new org.telegram.telegrambots.meta.api.objects.File();
        file.setFileSize((long)ImageValidator.MAX_BYTES+1);file.setFilePath("photos/file.png");when(telegram.execute(any(GetFile.class))).thenReturn(file);
        assertEquals(RecognitionException.Code.IMAGE_TOO_LARGE,assertThrows(RecognitionException.class,()->new TelegramImageLoader(telegram,"synthetic").load("f")).code());
    }
}
