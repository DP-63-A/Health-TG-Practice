package org.healthtg.bot;

import org.healthtg.bot.checkin.CheckinSelector;
import org.healthtg.bot.text.TextInputParser;
import org.healthtg.core.dialog.DialogState;
import org.healthtg.core.dialog.DialogStateService;
import org.healthtg.core.dialog.SaveDialogStateCommand;
import org.healthtg.core.entry.CreateCheckinCommand;
import org.healthtg.core.entry.EntryCoreService;
import org.healthtg.core.entry.Entry;
import org.healthtg.core.entry.EntryNotFoundException;
import org.healthtg.core.entry.EntryStatusConflictException;
import org.healthtg.core.entry.EntryVersionConflictException;
import org.healthtg.core.entry.OwnerContext;
import org.healthtg.core.entry.TelegramUpdateKey;
import org.healthtg.user.UserAccount;
import org.healthtg.user.UserService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.time.Clock;
import java.time.ZoneId;
import java.util.List;
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
    private final CheckinSelector selector;
    private final Clock clock;
    private final org.healthtg.bot.draft.DraftReviewFlow drafts;
    private final CheckinReceiptFlow checkinReceipts;
    private final org.healthtg.bot.text.TextDialogFlow textDialog;
    private org.healthtg.bot.visual.FoodPhotoFlow photos;
    public CoreBotFlow withPhotos(org.healthtg.bot.visual.FoodPhotoFlow photos) { this.photos = photos; return this; }


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
        this.selector = selector;
        this.clock = clock;
        this.textDialog = new org.healthtg.bot.text.TextDialogFlow(entries, dialogs, parser, drafts, clock);
    }

    @Override
    public synchronized List<BotAction> beginCheckin(BotUpdate update) {
        UserAccount user = users.findOrCreate(update.senderId());
        OwnerContext owner = new OwnerContext(user.id());
        if (isStale(update, owner)) return staleResponse(update);
        if (photos != null) { var resumed=photos.resume(update,owner,user.timezone()); if(resumed.isPresent()) return resumed.get(); }
        var active = entries.findActiveDraft(owner);
        if (active.isPresent()) return drafts.card(update, active.get(), user.timezone(), "Завершите текущий черновик.");
        if (photos != null) { var pendingPhoto = photos.guard(update, owner); if (pendingPhoto.isPresent()) return pendingPhoto.get(); }
        var pendingText = textDialog.guard(update, owner);
        if (pendingText.isPresent()) return pendingText.get();
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
        if (update.image() == null && (update.text() == null || update.text().startsWith("/"))) return List.of();
        UserAccount user = users.findOrCreate(update.senderId());
        OwnerContext owner = new OwnerContext(user.id());
        if (isStale(update, owner)) return staleResponse(update);
        if (photos != null) { var resumed=photos.resume(update,owner,user.timezone()); if(resumed.isPresent()) return resumed.get(); }
        if (photos != null && photos.hasPending(owner)) return photos.message(update, owner, user.timezone());
        if (update.image() != null) {
            var current = entries.findActiveDraft(owner);
            if (current.isPresent()) return drafts.card(update, current.get(), user.timezone(), "Завершите текущий черновик.");
            var pending = textDialog.guard(update, owner);
            if (pending.isPresent()) return pending.get();
            if (dialogs.find(owner).filter(s -> isActiveCheckin(s.step())).isPresent())
                return List.of(new BotAction.SendInlineMessage(update.chatId(), "Завершите выбор состояния.", List.of()));
            return photos == null ? List.of(new BotAction.SendInlineMessage(update.chatId(), "Распознавание фото не настроено.", List.of())) : photos.message(update, owner, user.timezone());
        }
        var correction = drafts.message(update, owner, user.timezone());
        if (photos != null) { var resumed=photos.resume(update,owner,user.timezone()); if(resumed.isPresent()) return resumed.get(); }
        if (correction.isPresent()) return correction.get();
        var active = entries.findActiveDraft(owner);
        if (active.isPresent()) {
            var draft = active.get();
            if (key(update.updateId()).storageKey().equals(draft.telegramUpdateKey())) {
                save(owner, draft.id(), "draft_review", Map.of("entry_revision", draft.revision()),
                        update.updateId());
            }
            return drafts.card(update, draft, user.timezone(), "Завершите текущий черновик.");
        }

        return textDialog.message(update, owner, user.timezone());
    }

    @Override
    public synchronized List<BotAction> handleCallback(BotUpdate update) {
        if (update.callbackId() == null || update.callbackData() == null) return List.of();
        UserAccount user = users.findOrCreate(update.senderId());
        OwnerContext owner = new OwnerContext(user.id());
        if (isStale(update, owner)) return staleResponse(update);
        if(photos!=null && update.callbackData().startsWith("pq:")) return photos.callback(update,owner,user.timezone());
        if (photos != null) { var resumed=photos.resume(update,owner,user.timezone()); if(resumed.isPresent()) return resumed.get(); }
        if (photos != null) {
            if (update.callbackData().startsWith("fp:") || update.callbackData().startsWith("pq:")) return photos.callback(update, owner, user.timezone());
            var pendingPhoto = photos.guard(update, owner);
            if (pendingPhoto.isPresent()) return pendingPhoto.get();
        }
        if (update.callbackData().startsWith("tx:")) return textDialog.callback(update, owner, user.timezone());
        if (update.callbackData().startsWith("dr:")) {
            var result=drafts.callback(update, owner, user.timezone());
            if(photos!=null) {
                var resumed=photos.resume(update,owner,user.timezone());
                if(resumed.isPresent()) {
                    var combined=new java.util.ArrayList<BotAction>(result);
                    resumed.get().stream().filter(a -> !(a instanceof BotAction.AnswerCallback)).forEach(combined::add);
                    return List.copyOf(combined);
                }
            }
            return result;
        }
        if (update.callbackData().startsWith("cancel:")) {
            if(dialogs.find(owner).filter(s -> s.context().containsKey("photo_queue")).isPresent())
                return List.of(new BotAction.AnswerCallback(update.callbackId(),"Используйте актуальную карточку показателя"));
            return cancel(update, owner);
        }
        if (update.callbackData().startsWith("qc:")) return checkinReceipts.cancel(update, owner);
        Optional<List<BotAction>> completed = completedCheckin(update, owner, user.timezone());
        if (completed.isPresent()) return completed.get();

        synchronized (selector) {
            return handleCheckinCallback(update, owner, user.timezone());
        }
    }

    private boolean isStale(BotUpdate update, OwnerContext owner) {
        return photos != null && dialogs.find(owner).filter(state -> update.updateId()
                < org.healthtg.bot.visual.FoodPhotoFlow.observedUpdate(state)
                || (state.context().get("last_photo_update") instanceof Number photo
                && update.updateId() <= photo.longValue())).isPresent();
    }
    private static List<BotAction> staleResponse(BotUpdate update) {
        return update.callbackId() == null
                ? List.of(new BotAction.SendInlineMessage(update.chatId(), "Сообщение устарело. Продолжите текущий диалог.", List.of()))
                : List.of(new BotAction.AnswerCallback(update.callbackId(), "Кнопка устарела"));
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
