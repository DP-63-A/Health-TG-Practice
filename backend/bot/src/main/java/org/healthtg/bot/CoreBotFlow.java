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
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalTime;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.util.List;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

public final class CoreBotFlow implements BotFlow {
    private static final String BOT_KEY = "main";
    private static final int DIALOG_SCHEMA_VERSION = 1;
    private static final Logger LOG = LoggerFactory.getLogger(CoreBotFlow.class);
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
        save(owner, null, "checkin_category", Map.of("schema_version", DIALOG_SCHEMA_VERSION,
                "selector_id", selector.selectionId(update.senderId()).toString(),
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

        Optional<ClarificationContext> clarification = readClarification(owner, update.updateId());
        String input = clarification.map(value -> value.originalText() + " " + update.text())
                .orElse(update.text());
        TextParseResult parsed = parser.parse(input);
        if (clarification.isPresent()) parsed = mergeClarification(clarification.get(), parsed);
        if (parsed.outcome() == TextParseResult.Outcome.NEEDS_CLARIFICATION && parsed.data() != null) {
            saveClarification(owner, parsed, update.updateId());
            return List.of(new BotAction.SendInlineMessage(update.chatId(),
                    "Нужно уточнить данные перед сохранением.", List.of()));
        }
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

        restoreSelector(update.senderId(), owner, update.updateId());
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
        Map<String, Object> context = Map.of("schema_version", DIALOG_SCHEMA_VERSION,
                "selector_id", selector.selectionId(update.senderId()).toString(),
                "start_update", selector.startUpdate(update.senderId()),
                "category", selector.category(update.senderId()).name(),
                "last_callback", selector.lastCallback(update.senderId()));
        save(owner, null, outcome.view().stage() == CheckinSelector.Stage.SCORE
                ? "checkin_score" : "checkin_category", context, update.updateId());
        return List.of(new BotAction.AnswerCallback(update.callbackId(), null), inline(update.chatId(), outcome.view()));
    }

    private List<BotAction> cancel(BotUpdate update, OwnerContext owner) {
        Optional<CancelParameters> parsed = parseCancel(update.callbackData());
        if (parsed.isEmpty()) {
            return List.of(new BotAction.AnswerCallback(update.callbackId(), "Некорректные параметры"));
        }
        try {
            CancelParameters parameters = parsed.get();
            entries.cancel(owner, parameters.entryId(), parameters.revision());
            save(owner, null, "idle", Map.of(), update.updateId());
            return List.of(new BotAction.AnswerCallback(update.callbackId(), "Черновик отменён"));
        } catch (EntryNotFoundException | EntryStatusConflictException | EntryVersionConflictException conflict) {
            return List.of(new BotAction.AnswerCallback(update.callbackId(), "Черновик уже изменён"));
        }
    }

    private static Optional<CancelParameters> parseCancel(String callbackData) {
        String[] parts = callbackData.split(":", -1);
        if (parts.length != 3 || !"cancel".equals(parts[0])) return Optional.empty();
        try {
            long revision = Long.parseLong(parts[2]);
            if (revision < 1) return Optional.empty();
            return Optional.of(new CancelParameters(UUID.fromString(parts[1]), revision));
        } catch (IllegalArgumentException invalid) {
            return Optional.empty();
        }
    }

    private record CancelParameters(UUID entryId, long revision) {}

    private void restoreSelector(long telegramId, OwnerContext owner, long currentUpdateId) {
        if (selector.hasSession(telegramId)) return;
        Optional<DialogState> stored = dialogs.find(owner).filter(state -> state.step().startsWith("checkin_"));
        if (stored.isEmpty()) return;
        try {
            DialogState state = stored.get();
            Object version = state.context().get("schema_version");
            Object id = state.context().get("selector_id");
            Object start = state.context().get("start_update");
            if (!(version instanceof Number schema) || schema.intValue() != DIALOG_SCHEMA_VERSION
                    || !(id instanceof String token) || !(start instanceof Number first)) {
                throw new IllegalArgumentException("Incomplete selector state");
            }
            String lastCallback = state.context().get("last_callback") instanceof String value ? value : null;
            org.healthtg.bot.checkin.CheckinCategory category = null;
            Object rawCategory = state.context().get("category");
            if (rawCategory instanceof String name && !name.isBlank()) {
                category = org.healthtg.bot.checkin.CheckinCategory.valueOf(name);
            }
            selector.restore(telegramId, UUID.fromString(token), first.longValue(),
                    updateId(state.telegramUpdateKey()), lastCallback, category);
        } catch (IllegalArgumentException | IndexOutOfBoundsException corrupted) {
            LOG.warn("Stored check-in dialog state is invalid; resetting it without personal data");
            save(owner, null, "idle", Map.of("schema_version", DIALOG_SCHEMA_VERSION), currentUpdateId);
        }
    }

    private List<BotAction> activeDraft(BotUpdate update, UUID id, long revision) {
        return List.of(new BotAction.SendInlineMessage(update.chatId(),
                "У вас уже есть активный черновик. Проверьте его в дневнике или отмените.",
                cancelRows(id, revision)));
    }

    private void save(OwnerContext owner, UUID entryId, String step, Map<String, Object> context, long updateId) {
        dialogs.save(new SaveDialogStateCommand(owner, entryId, step, context, key(updateId)));
    }

    private void saveClarification(OwnerContext owner, TextParseResult parsed, long updateId) {
        var data = parsed.data();
        Map<String, Object> context = new LinkedHashMap<>();
        context.put("schema_version", DIALOG_SCHEMA_VERSION);
        context.put("type", data.type());
        context.put("payload", data.payload());
        context.put("field_origins", data.fieldOrigins());
        context.put("original_text", parsed.originalText());
        if (data.date() != null) context.put("date", data.date().toString());
        if (data.time() != null) context.put("time", data.time().toString());
        save(owner, null, "text_clarification", context, updateId);
    }

    private Optional<ClarificationContext> readClarification(OwnerContext owner, long updateId) {
        Optional<DialogState> previous = dialogs.find(owner)
                .filter(state -> "text_clarification".equals(state.step()));
        if (previous.isEmpty()) return Optional.empty();
        try {
            Map<String, Object> context = previous.get().context();
            Object version = context.get("schema_version");
            Object original = context.get("original_text");
            Object type = context.get("type");
            if (!(version instanceof Number schema) || schema.intValue() != DIALOG_SCHEMA_VERSION
                    || !(original instanceof String text) || text.isBlank()
                    || !(type instanceof String entryType) || entryType.isBlank()) {
                throw new IllegalArgumentException("Incomplete clarification state");
            }
            Map<String, Object> payload = objectMap(context.get("payload"));
            Map<String, String> origins = stringMap(context.get("field_origins"));
            LocalDate date = context.get("date") instanceof String value ? LocalDate.parse(value) : null;
            LocalTime time = context.get("time") instanceof String value ? LocalTime.parse(value) : null;
            return Optional.of(new ClarificationContext(entryType, payload, origins, date, time, text));
        } catch (IllegalArgumentException invalid) {
            LOG.warn("Stored text clarification state is invalid; resetting it without personal data");
            save(owner, null, "idle", Map.of("schema_version", DIALOG_SCHEMA_VERSION), updateId);
            return Optional.empty();
        }
    }

    private static TextParseResult mergeClarification(ClarificationContext previous, TextParseResult parsed) {
        if (parsed.data() == null || !previous.type().equals(parsed.data().type())) return parsed;
        Map<String, Object> payload = new LinkedHashMap<>(previous.payload());
        payload.putAll(parsed.data().payload());
        Map<String, String> origins = new LinkedHashMap<>(previous.fieldOrigins());
        origins.putAll(parsed.data().fieldOrigins());
        var merged = new TextParseResult.ParsedData(previous.type(), payload, origins,
                parsed.data().date() == null ? previous.date() : parsed.data().date(),
                parsed.data().time() == null ? previous.time() : parsed.data().time());
        return new TextParseResult(parsed.outcome(), parsed.originalText(), merged, parsed.issues());
    }

    private static Map<String, Object> objectMap(Object value) {
        if (!(value instanceof Map<?, ?> map)) throw new IllegalArgumentException("Invalid payload state");
        Map<String, Object> result = new LinkedHashMap<>();
        map.forEach((key, item) -> {
            if (!(key instanceof String field)) throw new IllegalArgumentException("Invalid payload key");
            result.put(field, item);
        });
        return result;
    }

    private static Map<String, String> stringMap(Object value) {
        if (!(value instanceof Map<?, ?> map)) throw new IllegalArgumentException("Invalid origins state");
        Map<String, String> result = new LinkedHashMap<>();
        map.forEach((key, item) -> {
            if (!(key instanceof String field) || !(item instanceof String origin)) {
                throw new IllegalArgumentException("Invalid origin state");
            }
            result.put(field, origin);
        });
        return result;
    }

    private record ClarificationContext(String type, Map<String, Object> payload,
                                        Map<String, String> fieldOrigins, LocalDate date,
                                        LocalTime time, String originalText) {}

    private Instant occurredAt(LocalDate date, LocalTime time, ZoneId zone) {
        ZonedDateTime now = ZonedDateTime.ofInstant(clock.instant(), zone);
        return ZonedDateTime.of(date == null ? now.toLocalDate() : date,
                time == null ? now.toLocalTime() : time, zone).toInstant();
    }

    private static TelegramUpdateKey key(long updateId) { return new TelegramUpdateKey(BOT_KEY, updateId); }
    private static long updateId(String storageKey) {
        int separator = storageKey.indexOf(':');
        if (separator < 1 || separator == storageKey.length() - 1) {
            throw new IllegalArgumentException("Invalid Telegram update key");
        }
        return Long.parseLong(storageKey.substring(separator + 1));
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
