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
import org.healthtg.core.entry.Entry;
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
    private final org.healthtg.bot.draft.DraftReviewFlow drafts;
    private final CheckinReceiptFlow checkinReceipts;

    public CoreBotFlow(UserService users, EntryCoreService entries, DialogStateService dialogs, Clock clock) {
        this(users, entries, dialogs, new TextInputParser(), new CheckinSelector(), clock, null);
    }

    CoreBotFlow(UserService users, EntryCoreService entries, DialogStateService dialogs,
                TextInputParser parser, CheckinSelector selector, Clock clock) {
        this(users, entries, dialogs, parser, selector, clock, null);
    }

    public CoreBotFlow(UserService users, EntryCoreService entries, DialogStateService dialogs, Clock clock, java.net.URI miniAppUrl) {
        this(users, entries, dialogs, new TextInputParser(), new CheckinSelector(), clock, miniAppUrl);
    }

    private CoreBotFlow(UserService users, EntryCoreService entries, DialogStateService dialogs,
                        TextInputParser parser, CheckinSelector selector, Clock clock, java.net.URI miniAppUrl) {
        this.drafts = new org.healthtg.bot.draft.DraftReviewFlow(entries, dialogs, miniAppUrl);
        this.checkinReceipts = new CheckinReceiptFlow(entries);
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
        if (active.isPresent()) return drafts.card(update, active.get(), user.timezone(), "Завершите текущий черновик.");
        if (dialogs.find(owner).filter(state -> "text_clarification".equals(state.step())).isPresent()) {
            return List.of(new BotAction.SendInlineMessage(update.chatId(),
                    "Сначала завершите уточнение текстовой записи. Ваши данные сохранены.", List.of()));
        }
        CheckinSelector proposed = new CheckinSelector();
        CheckinSelector.Outcome outcome = proposed.begin(update.senderId(), update.chatId(), update.updateId());
        if (outcome.status() == CheckinSelector.Status.REJECTED) return List.of(inline(update.chatId(), outcome.view()));
        DialogState saved = save(owner, null, "checkin_category", Map.of("schema_version", DIALOG_SCHEMA_VERSION,
                "selector_id", proposed.selectionId(update.senderId()).toString(),
                "start_update", update.updateId()), update.updateId());
        if (!isActiveCheckin(saved.step())) {
            return List.of(new BotAction.SendInlineMessage(update.chatId(),
                    "Команда уже обработана. Продолжите текущий диалог или отправьте новую /state.", List.of()));
        }
        CheckinSelector accepted = new CheckinSelector();
        try {
            restoreInto(accepted, update.senderId(), saved);
        } catch (IllegalArgumentException | IndexOutOfBoundsException corrupted) {
            return List.of(new BotAction.SendInlineMessage(update.chatId(),
                    "Не удалось восстановить отметку. Отправьте новую /state.", List.of()));
        }
        return List.of(inline(update.chatId(), accepted.begin(update.senderId(), update.chatId(),
                accepted.startUpdate(update.senderId())).view()));
    }

    @Override
    public synchronized List<BotAction> handleMessage(BotUpdate update) {
        if (update.text() == null || update.text().startsWith("/")) return List.of();
        UserAccount user = users.findOrCreate(update.senderId());
        OwnerContext owner = new OwnerContext(user.id());
        var correction = drafts.message(update, owner, user.timezone());
        if (correction.isPresent()) return correction.get();
        if (update.text().isBlank()) return List.of();
        var active = entries.findActiveDraft(owner);
        if (active.isPresent()) {
            var draft = active.get();
            if (key(update.updateId()).storageKey().equals(draft.telegramUpdateKey())) {
                save(owner, draft.id(), "draft_review", Map.of("entry_revision", draft.revision()),
                        update.updateId());
            }
            return drafts.card(update, draft, user.timezone(), "Завершите текущий черновик.");
        }

        Optional<ClarificationContext> clarification = readClarification(owner, update.updateId());
        String input = clarification.map(value -> value.originalText() + " " + update.text())
                .orElse(update.text());
        TextParseResult parsed = parser.parse(input);
        if (clarification.isPresent()) parsed = mergeClarification(clarification.get(), parsed);
        Instant messageSentAt = clarification.isPresent()
                ? clarification.get().messageSentAt() : update.messageSentAt();
        if (parsed.data() != null && "metrics".equals(parsed.data().type())
                && messageSentAt == null) {
            save(owner, null, "idle", Map.of("schema_version", DIALOG_SCHEMA_VERSION), update.updateId());
            return List.of(new BotAction.SendInlineMessage(update.chatId(),
                    "Не удалось восстановить время исходного сообщения. Отправьте показатель заново с датой.",
                    List.of()));
        }
        if (parsed.outcome() == TextParseResult.Outcome.NEEDS_CLARIFICATION && parsed.data() != null) {
            saveClarification(owner, parsed, update.updateId(), messageSentAt);
            return List.of(new BotAction.SendInlineMessage(update.chatId(),
                    "Нужно уточнить данные перед сохранением.", List.of()));
        }
        if (parsed.outcome() != TextParseResult.Outcome.PARSED || parsed.data() == null) {
            String text = parsed.outcome() == TextParseResult.Outcome.REJECTED && !parsed.issues().isEmpty()
                    ? parsed.issues().getFirst().message()
                    : parsed.outcome() == TextParseResult.Outcome.NOTE_SUGGESTED
                    ? "Не удалось выделить показатели. Отправьте более точную формулировку."
                    : "Нужно уточнить данные перед сохранением.";
            return List.of(new BotAction.SendInlineMessage(update.chatId(), text, List.of()));
        }
        var data = parsed.data();
        Instant occurredAt = "metrics".equals(data.type()) ? messageSentAt
                : occurredAt(data.date(), data.time(), user.timezone());
        var result = entries.createDraft(new CreateDraftCommand(owner,
                EntryType.valueOf(data.type().toUpperCase()), SourceKind.TEXT,
                Map.of("telegram_update_id", update.updateId()), occurredAt,
                data.payload(), data.fieldOrigins(), key(update.updateId())));
        save(owner, result.entry().id(), "draft_review", Map.of("entry_revision", result.entry().revision()),
                update.updateId());
        return drafts.card(update, result.entry(), user.timezone(), "Проверьте черновик.");
    }

    @Override
    public synchronized List<BotAction> handleCallback(BotUpdate update) {
        if (update.callbackId() == null || update.callbackData() == null) return List.of();
        UserAccount user = users.findOrCreate(update.senderId());
        OwnerContext owner = new OwnerContext(user.id());
        if (update.callbackData().startsWith("dr:")) return drafts.callback(update, owner, user.timezone());
        if (update.callbackData().startsWith("cancel:")) return cancel(update, owner);
        if (update.callbackData().startsWith("qc:")) return checkinReceipts.cancel(update, owner);
        Optional<List<BotAction>> completed = completedCheckin(update, owner, user.timezone());
        if (completed.isPresent()) return completed.get();

        synchronized (selector) {
            return handleCheckinCallback(update, owner, user.timezone());
        }
    }

    private List<BotAction> handleCheckinCallback(BotUpdate update, OwnerContext owner, ZoneId zone) {
        if (!restoreSelector(update.senderId(), owner, update.updateId())) {
            return List.of(new BotAction.AnswerCallback(update.callbackId(), "Кнопка устарела"));
        }
        CheckinSelector.Outcome outcome = selector.callback(update.senderId(), update.chatId(),
                update.updateId(), update.callbackData());
        if (outcome.status() == CheckinSelector.Status.REJECTED) {
            return List.of(new BotAction.AnswerCallback(update.callbackId(), "Кнопка устарела"));
        }
        if (outcome.status() == CheckinSelector.Status.SELECTED) {
            Entry entry = persistCompletedCheckin(update, owner, outcome.view().selection());
            return checkinReceipts.receipt(update, entry, zone, false);
        }
        if (outcome.view().stage() == CheckinSelector.Stage.COMPLETE) {
            Entry entry = persistCompletedCheckin(update, owner, outcome.view().selection());
            return checkinReceipts.receipt(update, entry, zone, true);
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
            Optional<DialogState> observed = dialogs.find(owner)
                    .filter(state -> parameters.entryId().equals(state.activeEntryId()));
            entries.cancel(owner, parameters.entryId(), parameters.revision());
            observed.ifPresent(state -> dialogs.clearIfCurrent(owner, parameters.entryId(),
                    state.revision(), key(update.updateId())));
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

    private Optional<List<BotAction>> completedCheckin(BotUpdate update, OwnerContext owner, ZoneId zone) {
        return dialogs.find(owner)
                .filter(state -> "checkin_complete".equals(state.step()))
                .filter(state -> update.callbackData().equals(state.context().get("completed_callback")))
                .map(state -> checkinReceipts.replay(update, owner, state.context().get("entry_id"), zone));
    }

    private Entry persistCompletedCheckin(BotUpdate update, OwnerContext owner,
                                         org.healthtg.bot.checkin.CheckinSelection selection) {
        var category = org.healthtg.core.entry.CheckinCategory.fromCode(selection.category().code());
        var entry = entries.createCheckin(new CreateCheckinCommand(owner, category, selection.score(),
                clock.instant(), checkinKey(selector.startUpdate(update.senderId()))));
        if (!selector.hasAcknowledgedSelection(update.senderId())) {
            selector.acknowledgeSelection(update.senderId());
        }
        save(owner, null, "checkin_complete", Map.of(
                "schema_version", DIALOG_SCHEMA_VERSION,
                "selector_id", selector.selectionId(update.senderId()).toString(),
                "start_update", selector.startUpdate(update.senderId()),
                "category", selector.category(update.senderId()).name(),
                "score", selection.score(),
                "completed_callback", update.callbackData(),
                "entry_id", entry.id().toString()), update.updateId());
        return entry;
    }

    private boolean restoreSelector(long telegramId, OwnerContext owner, long currentUpdateId) {
        Optional<DialogState> stored = dialogs.find(owner);
        if (stored.isEmpty()) return false;
        if (!isActiveCheckin(stored.get().step())) {
            return "idle".equals(stored.get().step()) && selector.isComplete(telegramId);
        }
        if (selector.hasAcknowledgedSelection(telegramId)
                && selector.selectionId(telegramId).toString().equals(stored.get().context().get("selector_id"))) {
            return true;
        }
        try {
            restoreInto(selector, telegramId, stored.get());
            return true;
        } catch (IllegalArgumentException | IndexOutOfBoundsException corrupted) {
            LOG.warn("Stored check-in dialog state is invalid; resetting it without personal data");
            save(owner, null, "idle", Map.of("schema_version", DIALOG_SCHEMA_VERSION), currentUpdateId);
            return false;
        }
    }

    private static void restoreInto(CheckinSelector target, long telegramId, DialogState state) {
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
        target.replace(telegramId, UUID.fromString(token), first.longValue(),
                updateId(state.telegramUpdateKey()), lastCallback, category);
    }

    private List<BotAction> activeDraft(BotUpdate update, UUID id, long revision) {
        return List.of(new BotAction.SendInlineMessage(update.chatId(),
                "У вас уже есть активный черновик. Проверьте его в дневнике или отмените.",
                cancelRows(id, revision)));
    }

    private DialogState save(OwnerContext owner, UUID entryId, String step, Map<String, Object> context, long updateId) {
        return dialogs.save(new SaveDialogStateCommand(owner, entryId, step, context, key(updateId)));
    }

    private void saveClarification(OwnerContext owner, TextParseResult parsed, long updateId, Instant messageSentAt) {
        var data = parsed.data();
        Map<String, Object> context = new LinkedHashMap<>();
        context.put("schema_version", DIALOG_SCHEMA_VERSION);
        context.put("type", data.type());
        context.put("payload", data.payload());
        context.put("field_origins", data.fieldOrigins());
        context.put("original_text", parsed.originalText());
        if (messageSentAt != null) context.put("message_sent_at", messageSentAt.toString());
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
            for (String numeric : List.of("value", "mass_g")) {
                Object value = payload.get(numeric);
                if (value instanceof String textValue) payload.put(numeric, new java.math.BigDecimal(textValue));
            }
            Map<String, String> origins = stringMap(context.get("field_origins"));
            LocalDate date = context.get("date") instanceof String value ? LocalDate.parse(value) : null;
            LocalTime time = context.get("time") instanceof String value ? LocalTime.parse(value) : null;
            Instant messageSentAt = null;
            if (context.get("message_sent_at") instanceof String value) {
                try {
                    messageSentAt = Instant.parse(value);
                } catch (java.time.DateTimeException invalidTimestamp) {
                    // Keep the clarification: a reply must not become the original report.
                    LOG.warn("Stored clarification has an invalid original message timestamp");
                }
            }
            return Optional.of(new ClarificationContext(entryType, payload, origins, date, time, text, messageSentAt));
        } catch (IllegalArgumentException | java.time.DateTimeException invalid) {
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
                                        LocalTime time, String originalText, Instant messageSentAt) {}

    private Instant occurredAt(LocalDate date, LocalTime time, ZoneId zone) {
        ZonedDateTime now = ZonedDateTime.ofInstant(clock.instant(), zone);
        return ZonedDateTime.of(date == null ? now.toLocalDate() : date,
                time == null ? now.toLocalTime() : time, zone).toInstant();
    }

    private static TelegramUpdateKey key(long updateId) { return new TelegramUpdateKey(BOT_KEY, updateId); }
    private static TelegramUpdateKey checkinKey(long startUpdateId) {
        return new TelegramUpdateKey(BOT_KEY + "-checkin", startUpdateId);
    }
    private static boolean isActiveCheckin(String step) {
        return "checkin_category".equals(step) || "checkin_score".equals(step);
    }
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
