package org.healthtg.bot;

import org.healthtg.bot.checkin.CheckinSelector;
import org.healthtg.bot.text.TextInputParser;
import org.healthtg.bot.text.TextParseResult;
import org.healthtg.core.dialog.DialogState;
import org.healthtg.core.dialog.DialogStateService;
import org.healthtg.core.dialog.SaveDialogStateCommand;
import org.healthtg.core.entry.CreateCheckinCommand;
import org.healthtg.core.entry.CreateDraftCommand;
import org.healthtg.core.entry.EntryCoreService;
import org.healthtg.core.entry.EntryNotFoundException;
import org.healthtg.core.entry.EntryStatusConflictException;
import org.healthtg.core.entry.EntryVersionConflictException;
import org.healthtg.core.entry.EntryType;
import org.healthtg.core.entry.OwnerContext;
import org.healthtg.core.entry.SourceKind;
import org.healthtg.core.entry.TelegramUpdateKey;
import org.healthtg.user.UserAccount;
import org.healthtg.user.UserService;

import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalTime;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.util.List;
import java.util.Map;
import java.util.UUID;

public final class CoreBotFlow implements BotFlow {
    private static final String BOT_KEY = "main";
    private final UserService users;
    private final EntryCoreService entries;
    private final DialogStateService dialogs;
    private final TextInputParser parser;
    private final CheckinSelector selector;
    private final Clock clock;

    public CoreBotFlow(UserService users, EntryCoreService entries, DialogStateService dialogs, Clock clock) {
        this(users, entries, dialogs, new TextInputParser(), new CheckinSelector(), clock);
    }

    CoreBotFlow(UserService users, EntryCoreService entries, DialogStateService dialogs,
                TextInputParser parser, CheckinSelector selector, Clock clock) {
        this.users = users;
        this.entries = entries;
        this.dialogs = dialogs;
        this.parser = parser;
        this.selector = selector;
        this.clock = clock;
    }

    @Override
    public List<BotAction> beginCheckin(BotUpdate update) {
        UserAccount user = users.findOrCreate(update.senderId());
        OwnerContext owner = new OwnerContext(user.id());
        var active = entries.findActiveDraft(owner);
        if (active.isPresent()) return activeDraft(update, active.get().id(), active.get().revision());
        CheckinSelector.Outcome outcome = selector.begin(update.senderId(), update.chatId(), update.updateId());
        save(owner, null, "checkin_category", Map.of("selector_id", token(outcome),
                "start_update", update.updateId()), update.updateId());
        return List.of(inline(update.chatId(), outcome.view()));
    }

    @Override
    public List<BotAction> handleMessage(BotUpdate update) {
        if (update.text() == null || update.text().isBlank() || update.text().startsWith("/")) return List.of();
        UserAccount user = users.findOrCreate(update.senderId());
        OwnerContext owner = new OwnerContext(user.id());
        var active = entries.findActiveDraft(owner);
        if (active.isPresent()) return activeDraft(update, active.get().id(), active.get().revision());

        TextParseResult parsed = parser.parse(update.text());
        if (parsed.outcome() != TextParseResult.Outcome.PARSED || parsed.data() == null) {
            String text = parsed.outcome() == TextParseResult.Outcome.NOTE_SUGGESTED
                    ? "Не удалось выделить показатели. Отправьте более точную формулировку."
                    : "Нужно уточнить данные перед сохранением.";
            return List.of(new BotAction.SendInlineMessage(update.chatId(), text, List.of()));
        }
        var data = parsed.data();
        Instant occurredAt = occurredAt(data.date(), data.time(), user.timezone());
        var result = entries.createDraft(new CreateDraftCommand(owner,
                EntryType.valueOf(data.type().toUpperCase()), SourceKind.TEXT,
                Map.of("telegram_update_id", update.updateId()), occurredAt,
                data.payload(), data.fieldOrigins(), key(update.updateId())));
        save(owner, result.entry().id(), "draft_review", Map.of("entry_revision", result.entry().revision()),
                update.updateId());
        return List.of(new BotAction.SendInlineMessage(update.chatId(),
                result.outcome() == org.healthtg.core.entry.DraftCreationResult.Outcome.CREATED
                        ? "Черновик сохранён. Проверьте его в дневнике или отмените."
                        : "У вас уже есть активный черновик. Завершите или отмените его.",
                cancelRows(result.entry().id(), result.entry().revision())));
    }

    @Override
    public List<BotAction> handleCallback(BotUpdate update) {
        if (update.callbackId() == null || update.callbackData() == null) return List.of();
        UserAccount user = users.findOrCreate(update.senderId());
        OwnerContext owner = new OwnerContext(user.id());
        if (update.callbackData().startsWith("cancel:")) return cancel(update, owner);

        restoreSelector(update.senderId(), owner);
        CheckinSelector.Outcome outcome = selector.callback(update.senderId(), update.chatId(),
                update.updateId(), update.callbackData());
        if (outcome.status() == CheckinSelector.Status.REJECTED) {
            return List.of(new BotAction.AnswerCallback(update.callbackId(), "Кнопка устарела"));
        }
        if (outcome.status() == CheckinSelector.Status.SELECTED) {
            var selection = outcome.view().selection();
            var category = org.healthtg.core.entry.CheckinCategory.fromCode(selection.category().code());
            entries.createCheckin(new CreateCheckinCommand(owner, category, selection.score(),
                    clock.instant(), key(update.updateId())));
            save(owner, null, "idle", Map.of(), update.updateId());
            return List.of(new BotAction.AnswerCallback(update.callbackId(), "Сохранено"),
                    new BotAction.SendInlineMessage(update.chatId(), "Отметка сохранена.", List.of()));
        }
        if (outcome.view().stage() == CheckinSelector.Stage.COMPLETE) {
            return List.of(new BotAction.AnswerCallback(update.callbackId(), "Уже сохранено"),
                    new BotAction.SendInlineMessage(update.chatId(), "Отметка уже сохранена.", List.of()));
        }
        Map<String, Object> context = Map.of("selector_id", token(outcome),
                "start_update", selector.startUpdate(update.senderId()),
                "category", selector.category(update.senderId()).name());
        save(owner, null, outcome.view().stage() == CheckinSelector.Stage.SCORE
                ? "checkin_score" : "checkin_category", context, update.updateId());
        return List.of(new BotAction.AnswerCallback(update.callbackId(), null), inline(update.chatId(), outcome.view()));
    }

    private List<BotAction> cancel(BotUpdate update, OwnerContext owner) {
        String[] parts = update.callbackData().split(":");
        if (parts.length != 3) return List.of(new BotAction.AnswerCallback(update.callbackId(), "Кнопка недействительна"));
        try {
            entries.cancel(owner, UUID.fromString(parts[1]), Long.parseLong(parts[2]));
            save(owner, null, "idle", Map.of(), update.updateId());
            return List.of(new BotAction.AnswerCallback(update.callbackId(), "Черновик отменён"));
        } catch (EntryNotFoundException | EntryStatusConflictException | EntryVersionConflictException
                 | IllegalArgumentException invalidOrStale) {
            return List.of(new BotAction.AnswerCallback(update.callbackId(), "Черновик уже изменён"));
        }
    }

    private void restoreSelector(long telegramId, OwnerContext owner) {
        dialogs.find(owner).filter(state -> state.step().startsWith("checkin_")).ifPresent(state -> {
            Object id = state.context().get("selector_id");
            Object start = state.context().get("start_update");
            if (!(id instanceof String token) || !(start instanceof Number first)) return;
            org.healthtg.bot.checkin.CheckinCategory category = null;
            Object rawCategory = state.context().get("category");
            if (rawCategory instanceof String name && !name.isBlank()) {
                category = org.healthtg.bot.checkin.CheckinCategory.valueOf(name);
            }
            selector.restore(telegramId, UUID.fromString(token), first.longValue(),
                    updateId(state.telegramUpdateKey()), category);
        });
    }

    private List<BotAction> activeDraft(BotUpdate update, UUID id, long revision) {
        return List.of(new BotAction.SendInlineMessage(update.chatId(),
                "У вас уже есть активный черновик. Проверьте его в дневнике или отмените.",
                cancelRows(id, revision)));
    }

    private void save(OwnerContext owner, UUID entryId, String step, Map<String, Object> context, long updateId) {
        dialogs.save(new SaveDialogStateCommand(owner, entryId, step, context, key(updateId)));
    }

    private Instant occurredAt(LocalDate date, LocalTime time, ZoneId zone) {
        ZonedDateTime now = ZonedDateTime.ofInstant(clock.instant(), zone);
        return ZonedDateTime.of(date == null ? now.toLocalDate() : date,
                time == null ? now.toLocalTime() : time, zone).toInstant();
    }

    private static TelegramUpdateKey key(long updateId) { return new TelegramUpdateKey(BOT_KEY, updateId); }
    private static long updateId(String storageKey) { return Long.parseLong(storageKey.substring(storageKey.indexOf(':') + 1)); }
    private static String token(CheckinSelector.Outcome outcome) {
        String data = outcome.view().rows().getFirst().getFirst().callbackData();
        return UUID.fromString(data.substring(2, 34).replaceFirst(
                "([0-9a-f]{8})([0-9a-f]{4})([0-9a-f]{4})([0-9a-f]{4})([0-9a-f]{12})", "$1-$2-$3-$4-$5")).toString();
    }
    private static BotAction.SendInlineMessage inline(long chatId, CheckinSelector.View view) {
        return new BotAction.SendInlineMessage(chatId, view.text(), view.rows().stream()
                .map(row -> row.stream().map(button -> new BotAction.InlineButton(
                        button.text(), button.callbackData())).toList()).toList());
    }
    private static List<List<BotAction.InlineButton>> cancelRows(UUID id, long revision) {
        return List.of(List.of(new BotAction.InlineButton("Отменить черновик", "cancel:" + id + ":" + revision)));
    }
}
