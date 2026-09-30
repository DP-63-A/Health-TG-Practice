package org.healthtg.bot;

import org.healthtg.bot.checkin.CheckinSelector;
import org.healthtg.bot.text.TextInputParser;
import org.healthtg.bot.text.TextParseResult;
import org.healthtg.core.dialog.DialogStateService;
import org.healthtg.core.dialog.DialogState;
import org.healthtg.core.dialog.SaveDialogStateCommand;
import org.healthtg.core.entry.CreateCheckinCommand;
import org.healthtg.core.entry.CreateDraftCommand;
import org.healthtg.core.entry.DraftCreationResult;
import org.healthtg.core.entry.Entry;
import org.healthtg.core.entry.EntryCoreService;
import org.healthtg.core.entry.EntryStatus;
import org.healthtg.core.entry.EntryType;
import org.healthtg.core.entry.SourceKind;
import org.healthtg.user.UserAccount;
import org.healthtg.user.UserService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneId;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.Executors;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.when;

class CoreBotFlowTest {
    private static final long TELEGRAM_ID = 1001;
    private static final UUID OWNER_ID = UUID.randomUUID();
    private static final Instant NOW = Instant.parse("2026-09-23T08:00:00Z");
    private final UserService users = mock(UserService.class);
    private final EntryCoreService entries = mock(EntryCoreService.class);
    private final DialogStateService dialogs = mock(DialogStateService.class);
    private final TextInputParser parser = mock(TextInputParser.class);
    private CoreBotFlow flow;

    @BeforeEach
    void setUp() {
        when(users.findOrCreate(TELEGRAM_ID)).thenReturn(
                new UserAccount(OWNER_ID, TELEGRAM_ID, ZoneId.of("Europe/Warsaw"), true));
        when(entries.findActiveDraft(any())).thenReturn(Optional.empty());
        when(dialogs.find(any())).thenReturn(Optional.empty());
        flow = new CoreBotFlow(users, entries, dialogs, parser, new CheckinSelector(),
                Clock.fixed(NOW, ZoneId.of("UTC")));
    }

    @Test
    void threeStepCheckinPersistsOneConfirmedSelection() {
        var category = assertInstanceOf(BotAction.SendInlineMessage.class,
                flow.beginCheckin(message(10, "Отметить состояние")).getFirst());
        String categoryCallback = category.rows().getFirst().getFirst().callbackData();

        var score = assertInstanceOf(BotAction.SendInlineMessage.class,
                flow.handleCallback(callback(11, categoryCallback)).get(1));
        String scoreCallback = score.rows().getFirst().get(4).callbackData();
        flow.handleCallback(callback(12, scoreCallback));

        ArgumentCaptor<CreateCheckinCommand> command = ArgumentCaptor.forClass(CreateCheckinCommand.class);
        verify(entries).createCheckin(command.capture());
        assertEquals(OWNER_ID, command.getValue().owner().userId());
        assertEquals("sleep_quality", command.getValue().category().code());
        assertEquals(5, command.getValue().score());
        assertEquals(12, command.getValue().updateKey().updateId());
    }

    @Test
    void parsedTextCreatesDraftAndPersistsReviewStep() {
        Map<String, Object> payload = Map.of("code", "steps", "value", 8000, "unit", "count");
        when(parser.parse("8000 шагов")).thenReturn(new TextParseResult(TextParseResult.Outcome.PARSED,
                "8000 шагов", new TextParseResult.ParsedData("metrics", payload,
                Map.of("value", "reported"), null, null), List.of()));
        Entry draft = new Entry(UUID.randomUUID(), OWNER_ID, EntryType.METRICS, EntryStatus.DRAFT,
                SourceKind.TEXT, Map.of("telegram_update_id", 20L), NOW, NOW, NOW, 1,
                payload, Map.of("value", "reported"), null, "main:20");
        when(entries.createDraft(any())).thenReturn(
                new DraftCreationResult(draft, DraftCreationResult.Outcome.CREATED));

        List<BotAction> actions = flow.handleMessage(message(20, "8000 шагов"));

        assertEquals(1, actions.size());
        ArgumentCaptor<CreateDraftCommand> command = ArgumentCaptor.forClass(CreateDraftCommand.class);
        verify(entries).createDraft(command.capture());
        assertEquals(OWNER_ID, command.getValue().owner().userId());
        assertEquals(EntryType.METRICS, command.getValue().type());
        assertEquals(20, command.getValue().updateKey().updateId());
        verify(dialogs).save(any());
    }

    @Test
    void activeDraftBlocksNewInputWithoutCallingParser() {
        Entry draft = new Entry(UUID.randomUUID(), OWNER_ID, EntryType.NOTE, EntryStatus.DRAFT,
                SourceKind.TEXT, Map.of(), NOW, NOW, NOW, 3, Map.of("text", "existing"),
                Map.of(), null, "main:1");
        when(entries.findActiveDraft(any())).thenReturn(Optional.of(draft));

        var action = assertInstanceOf(BotAction.SendInlineMessage.class,
                flow.handleMessage(message(30, "новый текст")).getFirst());

        assertEquals("cancel:" + draft.id() + ":3", action.rows().getFirst().getFirst().callbackData());
        verify(parser, never()).parse(any());
        verify(entries, never()).createDraft(any());
    }

    @Test
    void clarificationPersistsUsefulPartialData() {
        var data = new TextParseResult.ParsedData("metrics",
                Map.of("code", "heart_rate", "value", 72), Map.of("value", "reported"),
                java.time.LocalDate.of(2026, 9, 24), null);
        when(parser.parse("24.09.2026 пульс 72")).thenReturn(new TextParseResult(
                TextParseResult.Outcome.NEEDS_CLARIFICATION, "24.09.2026 пульс 72", data, List.of()));

        flow.handleMessage(message(40, "24.09.2026 пульс 72"));

        ArgumentCaptor<SaveDialogStateCommand> saved = ArgumentCaptor.forClass(SaveDialogStateCommand.class);
        verify(dialogs).save(saved.capture());
        assertEquals("text_clarification", saved.getValue().step());
        assertEquals(data.payload(), saved.getValue().context().get("payload"));
        assertEquals("2026-09-24", saved.getValue().context().get("date"));
        verify(entries, never()).createDraft(any());
    }

    @Test
    void clarificationContinuesFromPersistedStateAndCreatesDraft() {
        Map<String, Object> partial = Map.of("code", "heart_rate", "value", 72, "local_date", "2026-09-24");
        when(dialogs.find(any())).thenReturn(Optional.of(new DialogState(OWNER_ID, null,
                "text_clarification", Map.of("schema_version", 1, "type", "metrics",
                "payload", partial, "field_origins", Map.of("value", "reported"),
                "original_text", "24.09.2026 пульс 72"), 1, NOW, "main:40")));
        var parsed = new TextParseResult(TextParseResult.Outcome.PARSED,
                "24.09.2026 пульс 72 ударов в минуту",
                new TextParseResult.ParsedData("metrics", Map.of("unit", "bpm"),
                        Map.of("unit", "reported"), null, null), List.of());
        when(parser.parse("24.09.2026 пульс 72 ударов в минуту")).thenReturn(parsed);
        Entry draft = new Entry(UUID.randomUUID(), OWNER_ID, EntryType.METRICS, EntryStatus.DRAFT,
                SourceKind.TEXT, Map.of(), NOW, NOW, NOW, 1,
                Map.of("code", "heart_rate", "value", 72, "local_date", "2026-09-24", "unit", "bpm"),
                Map.of("value", "reported", "unit", "reported"), null, "main:41");
        when(entries.createDraft(any())).thenReturn(
                new DraftCreationResult(draft, DraftCreationResult.Outcome.CREATED));

        flow.handleMessage(message(41, "ударов в минуту"));

        verify(parser).parse("24.09.2026 пульс 72 ударов в минуту");
        ArgumentCaptor<CreateDraftCommand> command = ArgumentCaptor.forClass(CreateDraftCommand.class);
        verify(entries).createDraft(command.capture());
        assertEquals("heart_rate", command.getValue().payload().get("code"));
        assertEquals(72, command.getValue().payload().get("value"));
        assertEquals("2026-09-24", command.getValue().payload().get("local_date"));
        assertEquals("bpm", command.getValue().payload().get("unit"));
        assertEquals("reported", command.getValue().fieldOrigins().get("value"));
        assertEquals(41, command.getValue().updateKey().updateId());
    }

    @Test
    void malformedCancelParametersDoNotMasqueradeAsBusinessConflict() {
        var action = assertInstanceOf(BotAction.AnswerCallback.class,
                flow.handleCallback(callback(50, "cancel:not-a-uuid:nope")).getFirst());

        assertEquals("Некорректные параметры", action.text());
        verify(entries, never()).cancel(any(), any(), anyLong());
    }

    @Test
    void corruptedRestoredSelectorStateFallsBackToIdle() {
        when(dialogs.find(any())).thenReturn(Optional.of(new DialogState(OWNER_ID, null,
                "checkin_score", Map.of("schema_version", 1, "selector_id", "broken", "start_update", 10),
                1, NOW, "main:10")));

        var action = assertInstanceOf(BotAction.AnswerCallback.class,
                flow.handleCallback(callback(51, "q:00000000000000000000000000000000:v3")).getFirst());

        assertEquals("Кнопка устарела", action.text());
        ArgumentCaptor<SaveDialogStateCommand> saved = ArgumentCaptor.forClass(SaveDialogStateCommand.class);
        verify(dialogs).save(saved.capture());
        assertEquals("idle", saved.getValue().step());
    }

    @Test
    void concurrentScoreCallbacksPersistOneCheckin() throws Exception {
        var categories = (BotAction.SendInlineMessage) flow.beginCheckin(message(60, "/state")).getFirst();
        String category = categories.rows().getFirst().getFirst().callbackData();
        var scores = (BotAction.SendInlineMessage) flow.handleCallback(callback(61, category)).get(1);
        String score = scores.rows().getFirst().get(4).callbackData();

        try (var executor = Executors.newFixedThreadPool(2)) {
            var calls = List.of(
                    (java.util.concurrent.Callable<List<BotAction>>) () -> flow.handleCallback(callback(62, score)),
                    (java.util.concurrent.Callable<List<BotAction>>) () -> flow.handleCallback(callback(63, score)));
            for (var result : executor.invokeAll(calls)) result.get();
        }

        verify(entries, times(1)).createCheckin(any());
    }

    private static BotUpdate message(long updateId, String text) {
        return new BotUpdate(updateId, BotUpdate.Kind.MESSAGE, BotUpdate.ChatType.PRIVATE,
                TELEGRAM_ID, TELEGRAM_ID, false, text, List.of(), null, null);
    }

    private static BotUpdate callback(long updateId, String data) {
        return new BotUpdate(updateId, BotUpdate.Kind.CALLBACK, BotUpdate.ChatType.PRIVATE,
                TELEGRAM_ID, TELEGRAM_ID, false, null, List.of(), "callback-" + updateId, data);
    }
}
