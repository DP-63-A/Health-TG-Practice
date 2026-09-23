package org.healthtg.bot;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import java.net.URI;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import static org.junit.jupiter.api.Assertions.*;

class BotHandlerTest {
    private static final long USER = 1001L; // Synthetic identity only.
    private static final URI URL = URI.create("https://example.com/mini-app");
    private final List<Long> called = new ArrayList<>();
    private final BotHandler handler = new BotHandler(new BotSettings(Set.of(USER), "demo_bot", URL), id -> {
        called.add(id);
        return "CHECKIN ENTRY";
    });

    private BotUpdate update(String text, List<BotUpdate.Entity> entities) {
        return new BotUpdate(BotUpdate.Kind.MESSAGE, BotUpdate.ChatType.PRIVATE, USER, USER, false, text, entities);
    }
    private BotUpdate command(String text) {
        int space = text.indexOf(' ');
        return update(text, List.of(new BotUpdate.Entity("bot_command", 0, space < 0 ? text.length() : space)));
    }

    @Test void startDisclosesDemoAndPreparesSeparateMenuAndPersistentKeyboard() {
        var actions = handler.handle(command("/start"));
        assertEquals(2, actions.size());
        var menu = actions.stream().filter(BotAction.SetMenuButton.class::isInstance)
                .map(BotAction.SetMenuButton.class::cast).findFirst().orElseThrow();
        var message = actions.stream().filter(BotAction.SendMessage.class::isInstance)
                .map(BotAction.SendMessage.class::cast).findFirst().orElseThrow();
        assertEquals(USER, menu.chatId()); assertEquals(URL, menu.url());
        assertFalse(menu.label().isBlank()); assertEquals(USER, message.chatId());
        assertTrue(message.text().contains("учебный"));
        assertTrue(message.text().contains("синтетические данные"));
        assertTrue(message.text().contains("не медицинский сервис"));
        assertTrue(message.text().contains("Не отправляйте реальные сведения"));
        assertEquals(List.of("Отметить состояние"), message.keyboard().buttons());
        assertTrue(message.keyboard().persistent()); assertTrue(message.keyboard().resize());
        assertTrue(called.isEmpty());
    }

    @Test void stateAndReplyButtonDelegateOnceToSameAuthorizedEntryPoint() {
        var fromCommand = handler.handle(command("/state"));
        assertEquals(List.of(USER), called);
        var fromButton = handler.handle(update("Отметить состояние", List.of()));
        assertEquals(List.of(USER, USER), called);
        assertEquals(fromCommand, fromButton);
        assertEquals("CHECKIN ENTRY", ((BotAction.SendMessage) fromCommand.getFirst()).text());
    }

    @ParameterizedTest @ValueSource(strings = {"/start payload", "/start@demo_bot", "/start@DEMO_BOT payload"})
    void acceptsOwnCommandAddressAndArguments(String text) {
        assertEquals(2, handler.handle(command(text)).size());
    }

    @ParameterizedTest @ValueSource(strings = {"/start@other_bot", "/state@other_bot", "/starter", "/states", "/unknown", "/START", "", "Отметить состояние ", "текст"})
    void unrelatedInputsDoNotDispatch(String text) {
        assertTrue(handler.handle(command(text)).isEmpty()); assertTrue(called.isEmpty());
    }

    @Test void missingCommandEntityDoesNotFabricateACommand() {
        assertTrue(handler.handle(update("/state", null)).isEmpty());
        assertTrue(handler.handle(update("/start", List.of())).isEmpty());
        assertTrue(called.isEmpty());
    }

    @Test void invalidEntitiesAndPartialCommandAreIgnoredWithoutException() {
        for (var entity : List.of(new BotUpdate.Entity(null, 0, 6),
                new BotUpdate.Entity("bold", 0, 6), new BotUpdate.Entity("bot_command", -1, 6),
                new BotUpdate.Entity("bot_command", 1, 6), new BotUpdate.Entity("bot_command", 0, -1),
                new BotUpdate.Entity("bot_command", 0, 0), new BotUpdate.Entity("bot_command", 0, Integer.MAX_VALUE))) {
            assertTrue(handler.handle(update("/state", List.of(entity))).isEmpty(), entity.toString());
        }
        assertTrue(handler.handle(update("/stateful", List.of(new BotUpdate.Entity("bot_command", 0, 6)))).isEmpty());
        assertTrue(handler.handle(update(null, List.of())).isEmpty());
        assertTrue(handler.handle(null).isEmpty());
        assertTrue(called.isEmpty());
    }

    @Test void accessGateProtectsEverySupportedActionAcrossDeniedInputs() {
        for (String text : List.of("/start", "/state", "Отметить состояние")) {
            var entities = text.startsWith("/") ? List.of(new BotUpdate.Entity("bot_command", 0, text.length())) : List.<BotUpdate.Entity>of();
            List<BotUpdate> denied = new ArrayList<>();
            for (var chat : BotUpdate.ChatType.values()) if (chat != BotUpdate.ChatType.PRIVATE)
                denied.add(new BotUpdate(BotUpdate.Kind.MESSAGE, chat, USER, USER, false, text, entities));
            for (var kind : BotUpdate.Kind.values()) if (kind != BotUpdate.Kind.MESSAGE && kind != BotUpdate.Kind.CALLBACK)
                denied.add(new BotUpdate(kind, BotUpdate.ChatType.PRIVATE, USER, USER, false, text, entities));
            denied.add(new BotUpdate(BotUpdate.Kind.MESSAGE, BotUpdate.ChatType.PRIVATE, 2002, 2002L, false, text, entities));
            denied.add(new BotUpdate(BotUpdate.Kind.MESSAGE, BotUpdate.ChatType.PRIVATE, USER, null, false, text, entities));
            denied.add(new BotUpdate(BotUpdate.Kind.MESSAGE, BotUpdate.ChatType.PRIVATE, USER, USER, true, text, entities));
            denied.add(new BotUpdate(BotUpdate.Kind.MESSAGE, BotUpdate.ChatType.PRIVATE, 2002, USER, false, text, entities));
            denied.add(new BotUpdate(null, BotUpdate.ChatType.PRIVATE, USER, USER, false, text, entities));
            denied.add(new BotUpdate(BotUpdate.Kind.MESSAGE, null, USER, USER, false, text, entities));
            for (var input : denied) assertTrue(handler.handle(input).isEmpty(), input.toString());
        }
        assertTrue(called.isEmpty(), "No denied input may invoke BE2-05");
    }

    @Test void emptyAllowlistDeniesEvenOtherwiseValidCommandsAndButton() {
        var denyAll = new BotHandler(new BotSettings(Set.of(), "demo_bot", URL), id -> fail("No dispatch"));
        assertTrue(denyAll.handle(command("/start")).isEmpty());
        assertTrue(denyAll.handle(command("/state")).isEmpty());
        assertTrue(denyAll.handle(update("Отметить состояние", List.of())).isEmpty());
    }

    @Test void absentMiniAppExplicitlyClearsMenuAndExplainsMissingCabinet() {
        var noUrl = new BotHandler(new BotSettings(Set.of(USER), "demo_bot", null), QuickCheckin.unavailable());
        var actions = noUrl.handle(command("/start"));
        assertEquals(2, actions.size());
        assertNull(((BotAction.SetMenuButton) actions.get(0)).url());
        assertTrue(((BotAction.SendMessage) actions.get(1)).text().contains("Кабинет пока не подключён"));
        assertTrue(((BotAction.SendMessage) noUrl.handle(command("/state")).getFirst()).text().contains("Данные не сохранены"));
    }

    @Test void placeholderNeverClaimsSavingAndIsSameForBothRoutes() {
        var unavailable = new BotHandler(new BotSettings(Set.of(USER), "demo_bot", URL), QuickCheckin.unavailable());
        var state = unavailable.handle(command("/state"));
        assertEquals(state, unavailable.handle(update("Отметить состояние", List.of())));
        var text = ((BotAction.SendMessage) state.getFirst()).text();
        assertTrue(text.contains("пока недоступны")); assertTrue(text.contains("Данные не сохранены"));
    }
}
