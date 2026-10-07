package org.healthtg.bot;

import java.net.URI;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Set;
import org.junit.jupiter.api.Test;
import org.telegram.telegrambots.meta.api.methods.menubutton.SetChatMenuButton;
import org.telegram.telegrambots.meta.api.methods.send.SendMessage;
import org.telegram.telegrambots.meta.api.objects.Update;
import org.telegram.telegrambots.meta.api.objects.User;
import org.telegram.telegrambots.meta.api.objects.chat.Chat;
import org.telegram.telegrambots.meta.api.objects.message.Message;
import org.telegram.telegrambots.meta.api.objects.MessageEntity;
import org.telegram.telegrambots.meta.api.objects.menubutton.MenuButtonCommands;
import org.telegram.telegrambots.meta.api.objects.menubutton.MenuButtonWebApp;
import org.telegram.telegrambots.meta.api.objects.replykeyboard.ReplyKeyboardMarkup;
import org.telegram.telegrambots.meta.generics.TelegramClient;
import org.mockito.ArgumentCaptor;
import static org.mockito.Mockito.*;
import static org.junit.jupiter.api.Assertions.*;

class TelegramAdapterTest {
    @Test void draftButtonOpensWebAppInsteadOfCallback() throws Exception {
        BotFlow flow = mock(BotFlow.class);
        when(flow.handleMessage(any())).thenReturn(List.of(new BotAction.SendInlineMessage(1001L,
                "Печёное яблоко 🍎", List.of(List.of(new BotAction.InlineButton("Открыть в Mini App", null,
                URI.create("https://example.test/diary/123")))))));
        new TelegramAdapter(client, new BotHandler(new BotSettings(Set.of(1001L), "test_bot", null), id -> "", flow))
                .accept(message("яблоко"));
        var sent = ArgumentCaptor.forClass(SendMessage.class);
        verify(client).execute(sent.capture());
        var keyboard = (org.telegram.telegrambots.meta.api.objects.replykeyboard.InlineKeyboardMarkup) sent.getValue().getReplyMarkup();
        var button = keyboard.getKeyboard().getFirst().getFirst();
        assertNull(button.getCallbackData());
        assertEquals("https://example.test/diary/123", button.getWebApp().getUrl());
        assertEquals("Печёное яблоко 🍎", sent.getValue().getText());
    }
    @Test void forwardsOriginalMessageTimestampWithoutInventingMissingDate() throws Exception {
        BotFlow flow = mock(BotFlow.class);
        when(flow.handleMessage(any())).thenReturn(List.of());
        var subject = new TelegramAdapter(client, new BotHandler(
                new BotSettings(Set.of(1001L), "test_bot", null), id -> "", flow));
        var dated = message("04.10.2026 за день прошёл 9000 шагов");
        dated.getMessage().setDate(1791183600);
        subject.accept(dated);
        subject.accept(message("04.10.2026 за день прошёл 8000 шагов"));
        var captured = ArgumentCaptor.forClass(BotUpdate.class);
        verify(flow, times(2)).handleMessage(captured.capture());
        assertEquals(java.time.Instant.ofEpochSecond(1791183600), captured.getAllValues().get(0).messageSentAt());
        assertNull(captured.getAllValues().get(1).messageSentAt());
    }
    private static final String EXPIRED_QUERY =
            "Bad Request: query is too old and response timeout expired or query ID is invalid";
    private final TelegramClient client = mock(TelegramClient.class);
    private final List<Long> checkins = new ArrayList<>();

    private TelegramAdapter callbackAdapter() {
        BotFlow flow = mock(BotFlow.class);
        when(flow.handleCallback(any())).thenReturn(List.of(
                new BotAction.AnswerCallback("expired", "Сохранено"),
                new BotAction.SendInlineMessage(1001L, "Отметка сохранена.", List.of())));
        return new TelegramAdapter(client, new BotHandler(
                new BotSettings(Set.of(1001L), "test_bot", null), id -> "", flow));
    }

    private static Update callback() {
        var query = new org.telegram.telegrambots.meta.api.objects.CallbackQuery();
        query.setId("expired");
        query.setData("test");
        query.setFrom(message("text").getMessage().getFrom());
        query.setMessage(message("text").getMessage());
        var update = new Update();
        update.setUpdateId(20);
        update.setCallbackQuery(query);
        return update;
    }

    private static org.telegram.telegrambots.meta.exceptions.TelegramApiRequestException rejection(
            Integer code, String description) {
        var error = mock(org.telegram.telegrambots.meta.exceptions.TelegramApiRequestException.class);
        when(error.getErrorCode()).thenReturn(code);
        when(error.getApiResponse()).thenReturn(description);
        return error;
    }
    private TelegramAdapter adapter(URI url) {
        return new TelegramAdapter(client, new BotHandler(new BotSettings(Set.of(1001L), "test_bot", url), id -> {
            checkins.add(id); return "CHECKIN";
        }));
    }

    static Update message(String text) {
        var user = new User(1001L, "Synthetic", false);
        var chat = Chat.builder().id(1001L).type("private").build();
        var message = new Message(); message.setFrom(user); message.setChat(chat); message.setText(text);
        if (text.startsWith("/")) {
            var entity = new MessageEntity("bot_command", 0, 1);
            entity.setLength(text.indexOf(' ') < 0 ? text.length() : text.indexOf(' '));
            message.setEntities(List.of(entity));
        }
        var update = new Update(); update.setUpdateId(10); update.setMessage(message); return update;
    }

    @Test void expiredCallbackStillDeliversResultAndAcceptsNextMessage() throws Exception {
        var error = rejection(400, EXPIRED_QUERY);
        when(client.execute(any(org.telegram.telegrambots.meta.api.methods.AnswerCallbackQuery.class)))
                .thenThrow(error);
        var subject = callbackAdapter();

        subject.accept(callback());
        subject.accept(message("/start"));

        var sent = ArgumentCaptor.forClass(SendMessage.class);
        verify(client, times(2)).execute(sent.capture());
        assertEquals("Отметка сохранена.", sent.getAllValues().getFirst().getText());
        assertTrue(sent.getAllValues().getLast().getText().contains("учебный"));
    }

    @org.junit.jupiter.params.ParameterizedTest
    @org.junit.jupiter.params.provider.ValueSource(ints = {401, 409, 429, 500, 502})
    void otherStatusWithSameDescriptionStillPropagates(int code) throws Exception {
        var error = rejection(code, EXPIRED_QUERY);
        when(client.execute(any(org.telegram.telegrambots.meta.api.methods.AnswerCallbackQuery.class)))
                .thenThrow(error);
        assertSame(error, assertThrows(org.telegram.telegrambots.meta.exceptions.TelegramApiException.class,
                () -> callbackAdapter().accept(callback())));
        verify(client, never()).execute(any(SendMessage.class));
    }

    @Test void unrelatedBadRequestAndTransportErrorStillPropagate() throws Exception {
        for (var error : List.of(rejection(400, "Bad Request: message text is empty"),
                rejection(null, EXPIRED_QUERY),
                new org.telegram.telegrambots.meta.exceptions.TelegramApiException("connection failed"))) {
            when(client.execute(any(org.telegram.telegrambots.meta.api.methods.AnswerCallbackQuery.class)))
                    .thenThrow(error);
            assertSame(error, assertThrows(org.telegram.telegrambots.meta.exceptions.TelegramApiException.class,
                    () -> callbackAdapter().accept(callback())));
        }
        verify(client, never()).execute(any(SendMessage.class));
    }

    @Test void expiredDescriptionFromOrdinaryMessageStillPropagates() throws Exception {
        var error = rejection(400, EXPIRED_QUERY);
        when(client.execute(any(SendMessage.class))).thenThrow(error);
        assertSame(error, assertThrows(org.telegram.telegrambots.meta.exceptions.TelegramApiException.class,
                () -> callbackAdapter().accept(callback())));
    }

    @Test void startProducesPrivateWebAppMenuAndPersistentKeyboard() throws Exception {
        adapter(URI.create("https://example.com/app")).accept(message("/start@test_bot payload"));
        var menus = ArgumentCaptor.forClass(SetChatMenuButton.class);
        var messages = ArgumentCaptor.forClass(SendMessage.class);
        verify(client).execute(menus.capture()); verify(client).execute(messages.capture());
        assertEquals("1001", menus.getValue().getChatId());
        var menu = assertInstanceOf(MenuButtonWebApp.class, menus.getValue().getMenuButton());
        assertEquals("https://example.com/app", menu.getWebAppInfo().getUrl());
        var sent = messages.getValue(); assertEquals("1001", sent.getChatId());
        assertTrue(sent.getText().contains("учебный"));
        var keyboard = assertInstanceOf(ReplyKeyboardMarkup.class, sent.getReplyMarkup());
        assertEquals(Boolean.TRUE, keyboard.getIsPersistent()); assertEquals(Boolean.TRUE, keyboard.getResizeKeyboard());
        assertEquals("Отметить состояние", keyboard.getKeyboard().getFirst().getFirst().getText());
        assertTrue(checkins.isEmpty()); verifyNoMoreInteractions(client);
    }

    @Test void missingMiniAppClearsPreviouslyConfiguredMenuAndDoesNotInventUrl() throws Exception {
        adapter(null).accept(message("/start"));
        var menus = ArgumentCaptor.forClass(SetChatMenuButton.class);
        var messages = ArgumentCaptor.forClass(SendMessage.class);
        verify(client).execute(menus.capture()); verify(client).execute(messages.capture());
        assertInstanceOf(MenuButtonCommands.class, menus.getValue().getMenuButton());
        assertEquals("1001", menus.getValue().getChatId());
        assertTrue(messages.getValue().getText().contains("Кабинет пока не подключён"));
    }

    @Test void commandAndButtonCallSameAuthorizedExtension() throws Exception {
        var adapter = adapter(null);
        adapter.accept(message("/state")); adapter.accept(message("Отметить состояние"));
        assertEquals(List.of(1001L, 1001L), checkins);
        verify(client, times(2)).execute(any(SendMessage.class)); verifyNoMoreInteractions(client);
    }

    @Test void deniedAndIncompleteSdkUpdatesCauseNoOutboundRequestOrCheckin() throws Exception {
        List<Update> denied = new ArrayList<>();
        denied.add(null); denied.add(new Update());
        for (String type : List.of("group", "supergroup", "channel")) {
            var update = message("/state"); update.getMessage().getChat().setType(type); denied.add(update);
        }
        var noType = message("/state"); var unknownChat = mock(Chat.class);
        when(unknownChat.getId()).thenReturn(1001L); noType.getMessage().setChat(unknownChat); denied.add(noType);
        var foreign = message("/state"); foreign.getMessage().getFrom().setId(2002L); foreign.getMessage().getChat().setId(2002L); denied.add(foreign);
        var mismatch = message("/state"); mismatch.getMessage().getChat().setId(2002L); denied.add(mismatch);
        var noSender = message("/state"); noSender.getMessage().setFrom(null); denied.add(noSender);
        var noId = message("/state"); var missingId = mock(User.class); when(missingId.getId()).thenReturn(null);
        noId.getMessage().setFrom(missingId); denied.add(noId);
        var noBotFlag = message("/state"); var incompleteUser = mock(User.class);
        when(incompleteUser.getId()).thenReturn(1001L); when(incompleteUser.getIsBot()).thenReturn(null);
        noBotFlag.getMessage().setFrom(incompleteUser); denied.add(noBotFlag);
        var bot = message("/state"); bot.getMessage().getFrom().setIsBot(true); denied.add(bot);
        var noChat = message("/state"); noChat.getMessage().setChat(null); denied.add(noChat);
        var noChatId = message("/state"); var missingChatId = mock(Chat.class); when(missingChatId.getId()).thenReturn(null);
        noChatId.getMessage().setChat(missingChatId); denied.add(noChatId);
        var edited = new Update(); edited.setEditedMessage(message("/state").getMessage()); denied.add(edited);
        for (var update : denied) adapter(null).accept(update);
        verifyNoInteractions(client); assertTrue(checkins.isEmpty());
    }

    @Test void sdkEntityErrorsAndOtherBotAddressNeverDispatch() throws Exception {
        var adapter = adapter(null);
        adapter.accept(message("/state@other_bot"));
        var absent = message("/state"); absent.getMessage().setEntities(null); adapter.accept(absent);
        var malformed = message("/state"); var original = malformed.getMessage();
        // SDK's real getEntities() dereferences every element first; this mock isolates
        // the adapter's projection boundary, not the SDK's malformed-JSON behavior.
        var boundaryMessage = mock(Message.class);
        when(boundaryMessage.getChat()).thenReturn(original.getChat());
        when(boundaryMessage.getChatId()).thenReturn(1001L);
        when(boundaryMessage.getFrom()).thenReturn(original.getFrom());
        when(boundaryMessage.getText()).thenReturn("/state");
        var entity = mock(MessageEntity.class);
        when(entity.getOffset()).thenReturn(null); when(entity.getLength()).thenReturn(6); when(entity.getType()).thenReturn("bot_command");
        when(boundaryMessage.getEntities()).thenReturn(Arrays.asList(null, entity));
        malformed.setMessage(boundaryMessage); adapter.accept(malformed);
        verifyNoInteractions(client); assertTrue(checkins.isEmpty());
    }
}
