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
    private final TelegramClient client = mock(TelegramClient.class);
    private final List<Long> checkins = new ArrayList<>();
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
