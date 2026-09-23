package org.healthtg.bot;

import java.util.*;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.telegram.telegrambots.meta.api.methods.AnswerCallbackQuery;
import org.telegram.telegrambots.meta.api.methods.send.SendMessage;
import org.telegram.telegrambots.meta.api.objects.*;
import org.telegram.telegrambots.meta.api.objects.replykeyboard.InlineKeyboardMarkup;
import org.telegram.telegrambots.meta.exceptions.*;
import org.telegram.telegrambots.meta.generics.TelegramClient;
import static org.healthtg.bot.CheckinDialogueTest.*;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class CheckinTransportTest {
    @ParameterizedTest @ValueSource(booleans={false,true})
    void restartWithoutFixtureExplainsOldScoreAndCancellationEvenWhenAcknowledgementExpired(boolean expired) throws Exception {
        var previousStore = new RecordingStore(); var previous = new CheckinDialogue(previousStore,CLOCK);
        var categories = output(previous.begin(start(1)));
        var scores = output(previous.callback(callback(2,buttons(categories).getFirst().data())));
        String scoreData = buttons(scores).getFirst().data();
        var saved = output(previous.callback(callback(3,scoreData)));
        String cancelData = buttons(saved).getFirst().data();
        var client = mock(TelegramClient.class);
        // A new default-mode bot has neither the previous dialogue nor any CheckinStore.
        var restarted = new TelegramAdapter(client,new BotHandler(new BotSettings(Set.of(USER),"test_bot",null),QuickCheckin.unavailable()));
        if (expired) when(client.execute(any(AnswerCallbackQuery.class))).thenThrow(new TelegramApiRequestException("Synthetic expired",
                new ApiResponse<Object>(false,400,"query is too old",null,null)));
        assertDoesNotThrow(() -> restarted.accept(callbackUpdate(4,scoreData)));
        assertDoesNotThrow(() -> restarted.accept(callbackUpdate(5,cancelData)));
        var messages = org.mockito.ArgumentCaptor.forClass(SendMessage.class);
        verify(client,times(2)).execute(messages.capture()); verify(client,times(2)).execute(any(AnswerCallbackQuery.class));
        for (var message : messages.getAllValues()) {
            assertTrue(message.getText().contains("сейчас недоступны"));
            assertTrue(message.getText().contains("после перезапуска недоступны"));
            assertTrue(message.getText().contains("не сохраняет и не отменяет"));
            assertFalse(message.getText().contains("/5")); assertFalse(message.getText().contains("отменена"));
            assertFalse(message.getReplyMarkup() instanceof InlineKeyboardMarkup);
        }
        assertEquals(1,previousStore.calls.size()); assertEquals(0,previousStore.cancels);
        assertFalse(previousStore.fixture.isCancelled(previousStore.calls.getFirst().key()));
        verifyNoMoreInteractions(client);
    }
    @Test void incompleteCallbackChatIdentifierIsIgnoredLikeIncompleteMessage() {
        var update = callbackUpdate(1,"data");
        var message = mock(org.telegram.telegrambots.meta.api.objects.message.Message.class);
        var chat = mock(org.telegram.telegrambots.meta.api.objects.chat.Chat.class);
        when(chat.getType()).thenReturn("private"); when(message.getChat()).thenReturn(chat);
        when(message.getChatId()).thenReturn(null);
        update.getCallbackQuery().setMessage(message);
        assertNull(assertDoesNotThrow(() -> TelegramAdapter.project(update)));
    }
    static Update callbackUpdate(int id, String data) {
        var callback = new CallbackQuery(); callback.setId("query-"+id); callback.setData(data);
        callback.setFrom(new User(USER,"Synthetic",false));
        callback.setMessage(TelegramAdapterTest.message("irrelevant").getMessage());
        var update = new Update(); update.setUpdateId(id); update.setCallbackQuery(callback); return update;
    }
    static String button(SendMessage sent, int index) {
        return ((InlineKeyboardMarkup)sent.getReplyMarkup()).getKeyboard().stream().flatMap(List::stream).toList().get(index).getCallbackData();
    }
    @Test void sdkProjectionPreservesCallbackAndUpdateIdentifiers() {
        var projected = TelegramAdapter.project(callbackUpdate(17,"data"));
        assertEquals(BotUpdate.Kind.CALLBACK,projected.kind()); assertEquals(17,projected.updateId());
        assertEquals("query-17",projected.callbackId()); assertEquals("data",projected.callbackData()); assertEquals(USER,projected.senderId());
    }
    @Test void failedDeliveryAtEveryStepReplaysWithoutNewSessionSaveOrCancellation() throws Exception {
        var client = mock(TelegramClient.class); var store = new RecordingStore();
        var adapter = new TelegramAdapter(client,new BotHandler(new BotSettings(Set.of(USER),"test_bot",null),new CheckinDialogue(store,CLOCK)));
        var sent = new ArrayList<SendMessage>();
        when(client.execute(any(SendMessage.class))).thenAnswer(inv -> {
            SendMessage request = inv.getArgument(0); sent.add(request);
            if (sent.size()%2==1) throw new TelegramApiException("Synthetic delivery failure");
            return null;
        });
        var start = TelegramAdapterTest.message("/state");
        assertThrows(TelegramApiException.class,()->adapter.accept(start)); adapter.accept(start);
        assertEquals(button(sent.get(0),0),button(sent.get(1),0));
        var category = callbackUpdate(11,button(sent.getLast(),0));
        assertThrows(TelegramApiException.class,()->adapter.accept(category)); adapter.accept(category);
        assertEquals(button(sent.get(2),0),button(sent.get(3),0));
        var score = callbackUpdate(12,button(sent.getLast(),2));
        assertThrows(TelegramApiException.class,()->adapter.accept(score)); adapter.accept(score);
        assertEquals(1,store.calls.size()); assertEquals(1,store.fixture.size());
        var cancel = callbackUpdate(13,button(sent.getLast(),0));
        assertThrows(TelegramApiException.class,()->adapter.accept(cancel)); adapter.accept(cancel);
        assertEquals(1,store.cancels); assertTrue(store.fixture.isCancelled(store.calls.getFirst().key()));
        verify(client,times(6)).execute(any(AnswerCallbackQuery.class));
    }
    @Test void expiredCallbackAcknowledgementStillDeliversResultAndNextUpdate() throws Exception {
        var client = mock(TelegramClient.class); var store = new RecordingStore();
        var d = new CheckinDialogue(store,CLOCK);
        var categories = output(d.begin(start(1)));
        var scores = output(d.callback(callback(2,buttons(categories).getFirst().data())));
        var adapter = new TelegramAdapter(client,new BotHandler(new BotSettings(Set.of(USER),"test_bot",null),d));
        when(client.execute(any(AnswerCallbackQuery.class))).thenThrow(new TelegramApiRequestException("Synthetic expired",
                new ApiResponse<Object>(false,400,"query is too old",null,null)));
        var update = callbackUpdate(3,buttons(scores).getFirst().data());
        assertDoesNotThrow(()->adapter.accept(update)); assertDoesNotThrow(()->adapter.accept(update));
        var next = TelegramAdapterTest.message("/state"); next.setUpdateId(4); adapter.accept(next);
        assertEquals(1,store.calls.size()); verify(client,times(3)).execute(any(SendMessage.class));
    }
    @Test void forgedSdkCallbacksCannotReachStorageOrSendMessages() throws Exception {
        var client=mock(TelegramClient.class); var store=new RecordingStore();
        var d=new CheckinDialogue(store,CLOCK);
        String data=buttons(output(d.begin(start(1)))).getFirst().data();
        var adapter=new TelegramAdapter(client,new BotHandler(new BotSettings(Set.of(USER),"test_bot",null),d));
        var foreign=callbackUpdate(2,data); foreign.getCallbackQuery().setFrom(new User(2002L,"Other",false));
        var noSender=callbackUpdate(3,data); noSender.getCallbackQuery().setFrom(null);
        var noMessage=callbackUpdate(4,data); noMessage.getCallbackQuery().setMessage(null);
        var noId=callbackUpdate(5,data); noId.getCallbackQuery().setId(null);
        var bot=callbackUpdate(6,data); bot.getCallbackQuery().getFrom().setIsBot(true);
        for(var update:List.of(foreign,noSender,noMessage,noId,bot)) adapter.accept(update);
        assertTrue(store.calls.isEmpty()); verifyNoInteractions(client);
    }
}
