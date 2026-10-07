package org.healthtg.bot;

import org.junit.jupiter.api.Test;
import org.telegram.telegrambots.meta.api.objects.webapp.WebAppData;
import org.telegram.telegrambots.meta.api.methods.send.SendMessage;
import org.telegram.telegrambots.meta.api.objects.replykeyboard.ReplyKeyboardMarkup;
import org.telegram.telegrambots.meta.generics.TelegramClient;
import org.mockito.ArgumentCaptor;
import java.net.URI;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class DateTimePickerTransportTest {
    @Test void realAdapterMapsServiceMessageAndUsesReplyWebAppButton() throws Exception {
        var flow=mock(BotFlow.class);var client=mock(TelegramClient.class);var url=URI.create("https://example.test/datetime-picker.html#token=example");
        when(flow.handleMessage(any())).thenReturn(List.of(new BotAction.SendMessage(1001,"Дата ё 🙂",new BotAction.ReplyKeyboard(List.of(BotHandler.CHECKIN_BUTTON),false,true,url))));
        var adapter=new TelegramAdapter(client,new BotHandler(new BotSettings(Set.of(1001L),"test_bot",null),id->"",flow));
        var update=TelegramAdapterTest.message("");update.getMessage().setText(null);var data=new WebAppData("{\"date\":\"2026-10-06\"}","forged label");update.getMessage().setWebAppData(data);
        adapter.accept(update);var captured=ArgumentCaptor.forClass(BotUpdate.class);verify(flow).handleMessage(captured.capture());assertEquals(data.getData(),captured.getValue().webAppData());assertNull(captured.getValue().text());
        var sent=ArgumentCaptor.forClass(SendMessage.class);verify(client).execute(sent.capture());assertEquals("Дата ё 🙂",sent.getValue().getText());
        var keyboard=(ReplyKeyboardMarkup)sent.getValue().getReplyMarkup();var button=keyboard.getKeyboard().stream().flatMap(List::stream).filter(b->b.getWebApp()!=null).findFirst().orElseThrow();assertEquals(url.toString(),button.getWebApp().getUrl());
    }
    @Test void webAppPayloadDoesNotBypassSenderChatTypeAllowlistOrEditedMessageGuards(){
        var flow=mock(BotFlow.class);var handler=new BotHandler(new BotSettings(Set.of(1001L),"test_bot",null),id->"",flow);
        for(var u:List.of(update(BotUpdate.Kind.MESSAGE,BotUpdate.ChatType.GROUP,1001,1001L,false),update(BotUpdate.Kind.EDITED_MESSAGE,BotUpdate.ChatType.PRIVATE,1001,1001L,false),update(BotUpdate.Kind.MESSAGE,BotUpdate.ChatType.PRIVATE,1001,2002L,false),update(BotUpdate.Kind.MESSAGE,BotUpdate.ChatType.PRIVATE,2002,2002L,false),update(BotUpdate.Kind.MESSAGE,BotUpdate.ChatType.PRIVATE,1001,null,false),update(BotUpdate.Kind.MESSAGE,BotUpdate.ChatType.PRIVATE,1001,1001L,true))) assertTrue(handler.handle(u).isEmpty());
        verifyNoInteractions(flow);
    }
    BotUpdate update(BotUpdate.Kind k,BotUpdate.ChatType t,long chat,Long sender,boolean bot){return new BotUpdate(10,k,t,chat,sender,bot,null,List.of(),null,null,null,null,"{}");}
}

