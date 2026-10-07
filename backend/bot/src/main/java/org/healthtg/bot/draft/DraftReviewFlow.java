package org.healthtg.bot.draft;

import org.healthtg.bot.BotAction;
import org.healthtg.bot.BotUpdate;
import org.healthtg.bot.datetime.DateTimePicker;
import org.healthtg.bot.correction.CorrectionInputParser;
import org.healthtg.bot.correction.CorrectionResult;
import org.healthtg.core.dialog.DialogState;
import org.healthtg.core.dialog.DialogStateService;
import org.healthtg.core.dialog.SaveDialogStateCommand;
import org.healthtg.core.entry.*;

import java.math.BigDecimal;
import java.net.URI;
import java.time.*;
import java.util.*;

/** One polling process serializes dispatch; Entry CAS also protects against Mini App writes.
 * Pending intent is saved once per Telegram update, before touching Entry. It is deliberately
 * not overwritten with a completed state using that same update id.
 */
public final class DraftReviewFlow {
    private final EntryCoreService entries;
    private final DialogStateService dialogs;
    private final URI miniAppUrl;
    private final CorrectionInputParser parser = new CorrectionInputParser();
    private static final Map<String, String> FIELDS = Map.of(
            "v", "Значение", "m", "Масса, г", "e", "Энергия, ккал", "p", "Белки, г",
            "f", "Жиры, г", "c", "Углеводы, г", "d", "Дата");
    private static final Map<String, String> PATHS = Map.of("v", "value", "m", "mass_g",
            "e", "energy_kcal", "p", "protein_g", "f", "fat_g", "c", "carbs_g");

    public DraftReviewFlow(EntryCoreService entries, DialogStateService dialogs, URI miniAppUrl) {
        this.entries = entries;
        this.dialogs = dialogs;
        this.miniAppUrl = miniAppUrl;
    }

    public Optional<List<BotAction>> message(BotUpdate update, OwnerContext owner, ZoneId zone) {
        Optional<DialogState> stored = dialogs.find(owner);
        if (stored.isEmpty() || !stored.get().step().startsWith("draft_")) return Optional.empty();
        DialogState state = stored.get();
        if (state.activeEntryId() == null) {
            if ("draft_pending".equals(state.step()) || "draft_await".equals(state.step())) {
                return Optional.of(invalidContext(update, owner, entries.findActiveDraft(owner).orElse(null), zone));
            }
            return Optional.empty();
        }
        Entry entry;
        try {
            entry = entries.requireEntry(owner, state.activeEntryId());
        } catch (EntryNotFoundException missing) {
            return Optional.of(invalidContext(update, owner, entries.findActiveDraft(owner).orElse(null), zone));
        }
        if (("draft_pending".equals(state.step()) || "draft_await".equals(state.step()))
                && !validContext(state, entry)) return Optional.of(invalidContext(update, owner, entry, zone));
        if ("draft_pending".equals(state.step())) {
            List<BotAction> result = recover(update, owner, state, zone);
            entry = entries.requireEntry(owner, state.activeEntryId());
            if (update.updateId() <= savedUpdate(state)) return Optional.of(result);
            if (entry.status() != EntryStatus.DRAFT) return Optional.empty();
            save(owner, entry, "draft_review", base(entry, zone), update);
            return Optional.of(result);
        }
        if (entry.status() != EntryStatus.DRAFT) {
            // A late answer to a field prompt must never become a new entry.
            if ("draft_await".equals(state.step())) {
                save(owner, entry, "idle", Map.of(), update);
                return Optional.of(card(update, entry, zone, "Запись уже завершена; исправление не применено."));
            }
            return Optional.empty();
        }
        if (!"draft_await".equals(state.step())) return Optional.of(card(update, entry, zone,
                "Завершите или отмените этот черновик перед новым сообщением."));
        if (update.updateId() <= savedUpdate(state)) return Optional.of(card(update, entry, zone, "Используйте актуальные кнопки."));
        long revision = number(state.context(), "entry_revision");
        if (entry.revision() != revision) {
            save(owner, entry, "draft_review", base(entry, zone), update);
            return Optional.of(card(update, entry, zone, "Запись изменилась. Проверьте её и выберите поле заново."));
        }
        String field = String.valueOf(state.context().get("field"));
        if (!fields(entry).contains(field)) return Optional.of(card(update, entry, zone, "Выберите поле заново."));
        var parsed = "d".equals(field) ? parser.parseDate(update.text()) : parser.parseNumber(update.text());
        if (parsed instanceof CorrectionResult.Failure<?> failure) {
            return Optional.of(List.of(message(update, failure.message(), backRows(entry))));
        }
        Object value = ((CorrectionResult.Success<?>) parsed).value();
        if (value instanceof BigDecimal decimal && decimal.signum() < 0) {
            return Optional.of(List.of(message(update, "Значение не может быть отрицательным.", backRows(entry))));
        }
        if ("v".equals(field) && entry.type()==EntryType.METRICS && value instanceof BigDecimal decimal) {
            try { org.healthtg.bot.recognition.MetricCandidate.validateValue((String)entry.payload().get("code"),decimal); }
            catch (IllegalArgumentException invalid) {
                return Optional.of(List.of(message(update, "steps".equals(entry.payload().get("code"))
                        ? "Введите целое количество шагов от 0 до 1000000000."
                        : "Введите значение от 0 до 1000000000.", backRows(entry))));
            }
        }
        Map<String, Object> context = base(entry, ZoneId.of(String.valueOf(state.context().get("timezone"))));
        context.put("operation", "patch");
        context.put("field", field);
        // Strings preserve decimal precision through Mongo's untyped context map.
        context.put("value", value.toString());
        DialogState pending = save(owner, entry, "draft_pending", context, update);
        return Optional.of(recover(update, owner, pending, zone));
    }

    public List<BotAction> callback(BotUpdate update, OwnerContext owner, ZoneId zone) {
        String[] parts = update.callbackData().split(":", -1);
        if (parts.length != 4 || !"dr".equals(parts[0])) return answer(update, "Кнопка устарела");
        UUID id;
        long revision;
        try {
            id = UUID.fromString(parts[2]);
            revision = Long.parseLong(parts[3]);
            if (revision < 1) return answer(update, "Кнопка устарела");
        } catch (IllegalArgumentException invalid) { return answer(update, "Кнопка устарела"); }
        Optional<DialogState> stored = dialogs.find(owner);
        if (stored.isPresent() && "draft_pending".equals(stored.get().step())) {
            DialogState pending = stored.get();
            Entry pendingEntry;
            try {
                pendingEntry = pending.activeEntryId() == null ? null : entries.requireEntry(owner, pending.activeEntryId());
            } catch (EntryNotFoundException missing) { pendingEntry = null; }
            if (pendingEntry == null || !validContext(pending, pendingEntry)) {
                return invalidContext(update, owner, pendingEntry == null
                        ? entries.findActiveDraft(owner).orElse(null) : pendingEntry, zone);
            }
            List<BotAction> recovered = recover(update, owner, pending, zone);
            if (update.updateId() <= savedUpdate(pending)) return recovered;
        } else if (stored.isPresent() && update.updateId() <= savedUpdate(stored.get())) {
            return answer(update, "Используйте актуальные кнопки");
        }
        Entry entry;
        try { entry = entries.requireEntry(owner, id); }
        catch (EntryNotFoundException missing) { return answer(update, "Запись недоступна"); }
        if (entry.status() != EntryStatus.DRAFT || (revision != entry.revision() && !"r".equals(parts[1]))) {
            return card(update, entry, zone, "Запись уже изменена. Показана актуальная версия.");
        }
        Map<String, Object> context = base(entry, zone);
        String action = parts[1];
        if ("s".equals(action) || "x".equals(action)) {
            context.put("operation", "s".equals(action) ? "confirm" : "cancel");
            context.put("submission_id", "bot_" + entry.id() + "_" + revision);
            return recover(update, owner, save(owner, entry, "draft_pending", context, update), zone);
        }
        if (fields(entry).contains(action)) {
            context.put("field", action);
            DialogState accepted = save(owner, entry, "draft_await", context, update);
            if (!"draft_await".equals(accepted.step()) || !Objects.equals(accepted.context().get("field"), action)
                    || number(accepted.context(), "entry_revision") != entry.revision()) return card(update, entry, zone, "Диалог уже изменён.");
            return withAnswer(update, message(update, "d".equals(action)
                    ? "Введите дату: ДД.ММ.ГГГГ или ГГГГ-ММ-ДД."
                    : correctionQuestion(entry,action), backRows(entry)));
        }
        if ("n".equals(action)) {
            save(owner, entry, "draft_choose", context, update);
            List<List<BotAction.InlineButton>> rows = new ArrayList<>();
            for (String field : fields(entry)) rows.add(List.of(button(FIELDS.get(field), field, entry)));
            rows.addAll(backRows(entry));
            return withAnswer(update, message(update, "Что исправить?", rows));
        }
        if (!"b".equals(action) && !"r".equals(action)) return answer(update, "Кнопка устарела");
        save(owner, entry, "draft_review", context, update);
        return card(update, entry, zone, "Проверьте запись.");
    }

    private List<BotAction> recover(BotUpdate update, OwnerContext owner, DialogState state, ZoneId fallbackZone) {
        if (!"draft_pending".equals(state.step()) || state.activeEntryId() == null) return answer(update, "Диалог уже изменён");
        Map<String, Object> context = state.context();
        Entry entry = entries.requireEntry(owner, state.activeEntryId());
        if (!validContext(state, entry)) return invalidContext(update, owner, entry, fallbackZone);
        long revision = number(context, "entry_revision");
        ZoneId zone = ZoneId.of(String.valueOf(context.get("timezone")));
        String operation = String.valueOf(context.get("operation"));
        String notice = "Показана текущая запись. Проверьте результат операции.";
        try {
            if ("confirm".equals(operation)) {
                entry = entries.confirm(new ConfirmEntryCommand(owner, entry.id(),
                        String.valueOf(context.get("submission_id")), revision));
                notice = "Запись подтверждена.";
            } else if ("cancel".equals(operation)) {
                entry = entries.cancel(owner, entry.id(), revision);
                notice = "Черновик отменён.";
            } else if ("patch".equals(operation) && entry.status() == EntryStatus.DRAFT && entry.revision() == revision) {
                entry = patch(owner, entry, context, zone);
                notice = "Изменение сохранено. Проверьте запись.";
            }
        } catch (EntryVersionConflictException | EntryStatusConflictException conflict) {
            entry = entries.requireEntry(owner, entry.id());
            notice = "Запись изменилась. Старое действие не применено повторно; проверьте текущую версию.";
        } catch (EntryValidationException | DateTimeException invalid) {
            // The caller owns this update's transition. Consuming its key here would
            // prevent the same callback from selecting a corrected field below.
            notice = "Не удалось применить значение. Проверьте поле и выберите исправление заново.";
        }
        return card(update, entry, zone, notice);
    }

    private static String correctionQuestion(Entry entry,String field) {
        if ("v".equals(field) && entry.type()==EntryType.METRICS) {
            if ("steps".equals(entry.payload().get("code"))) return "Введите целое количество шагов от 0 до 1000000000.";
            if ("sleep_duration_min".equals(entry.payload().get("code"))) return "Введите длительность сна в минутах, например 450.";
        }
        return "Введите одно число для поля «"+FIELDS.get(field)+"», например 12,5.";
    }

    /** Validate persisted intent before any mutation; a partial/corrupt intent is never guessed. */
    private boolean validContext(DialogState state, Entry entry) {
        Map<String, Object> context = state.context();
        try {
            if (!entry.id().equals(state.activeEntryId()) || number(context, "schema_version") != 1
                    || number(context, "entry_revision") < 1 || !validUpdateKey(state.telegramUpdateKey())) return false;
            if (!(context.get("timezone") instanceof String zone) || zone.isBlank()) return false;
            ZoneId.of(zone);
            if ("draft_await".equals(state.step())) {
                return context.get("field") instanceof String field && fields(entry).contains(field);
            }
            if (!"draft_pending".equals(state.step()) || !(context.get("operation") instanceof String operation)) return false;
            if ("cancel".equals(operation)) return true;
            if ("confirm".equals(operation)) {
                return context.get("submission_id") instanceof String submission
                        && submission.equals("bot_" + entry.id() + "_" + number(context, "entry_revision"));
            }
            if (!"patch".equals(operation) || !(context.get("field") instanceof String field)
                    || !fields(entry).contains(field) || !(context.get("value") instanceof String value)
                    || value.isBlank() || value.length() > 2010) return false;
            if ("d".equals(field)) {
                var parsed = parser.parseDate(value);
                if (context.containsKey("picker_time")) {
                    if (entry.type() == EntryType.METRICS || !(context.get("picker_time") instanceof String time)) return false;
                    LocalTime.parse(time);
                }
                return parsed instanceof CorrectionResult.Success<?> date && date.value().toString().equals(value);
            }
            // BigDecimal.toString() can use an exponent even though user input never does.
            BigDecimal decimal = new BigDecimal(value);
            if (decimal.precision() > 2000 || decimal.scale() < -2000 || decimal.scale() > 2000) return false;
            return decimal.signum() >= 0;
        } catch (IllegalArgumentException | ArithmeticException | DateTimeException invalid) {
            return false;
        }
    }

    private List<BotAction> invalidContext(BotUpdate update, OwnerContext owner, Entry entry, ZoneId zone) {
        // save still enforces the original update high-water mark. A replay cannot overwrite state.
        dialogs.save(new SaveDialogStateCommand(owner, entry == null ? null : entry.id(),
                entry != null && entry.status() == EntryStatus.DRAFT ? "draft_review" : "idle",
                entry == null ? Map.of("schema_version", 1) : base(entry, zone),
                new TelegramUpdateKey("main", update.updateId())));
        String notice = "Не удалось восстановить незавершённое действие. Его результат неизвестен; повтор не выполнен. Проверьте текущее состояние и выберите действие заново.";
        return entry == null ? withAnswer(update, message(update, notice, List.of())) : card(update, entry, zone, notice);
    }

    private Entry patch(OwnerContext owner, Entry entry, Map<String, Object> context, ZoneId zone) {
        String field = String.valueOf(context.get("field"));
        String raw = String.valueOf(context.get("value"));
        Map<String, Object> payload;
        Instant occurredAt = null;
        String origin;
        if ("d".equals(field)) {
            LocalDate date = LocalDate.parse(raw);
            if (entry.type() == EntryType.METRICS) {
                payload = Map.of("local_date", date.toString());
                origin = "local_date";
            } else {
                LocalDateTime local = date.atTime(context.get("picker_time") instanceof String time
                        ? LocalTime.parse(time) : entry.occurredAt().atZone(zone).toLocalTime());
                var offsets = zone.getRules().getValidOffsets(local);
                if (offsets.size() != 1) throw new EntryValidationException("Ambiguous local time");
                occurredAt = local.toInstant(offsets.getFirst());
                payload = Map.of();
                origin = "occurred_at";
            }
        } else {
            String path = PATHS.get(field);
            if (path == null) throw new EntryValidationException("Invalid field");
            BigDecimal value = new BigDecimal(raw);
            boolean nutrient = Set.of("e", "p", "f", "c").contains(field);
            payload = nutrient ? Map.of("nutrients", Map.of(path, value)) : Map.of(path, value);
            origin = nutrient ? "nutrients." + path : path;
        }
        return entries.patch(new PatchEntryCommand(owner, entry.id(), number(context, "entry_revision"),
                occurredAt, payload, Map.of(origin, "reported")));
    }

    public List<BotAction> card(BotUpdate update, Entry entry, ZoneId zone, String notice) {
        StringBuilder text = new StringBuilder(notice).append("\n");
        text.append("Тип: ").append(switch (entry.type()) {
            case MEAL -> "питание"; case METRICS -> "показатель"; case NOTE -> "заметка"; case CHECKIN -> "быстрая отметка";
        }).append("\n");
        Map<String, Object> p = entry.payload();
        if (entry.type() == EntryType.METRICS) {
            String code = String.valueOf(p.get("code"));
            text.append(switch (code) { case "steps" -> "Шаги"; case "sleep_duration_min" -> "Сон"; default -> "Пульс"; })
                    .append(": ").append(value(p.get("value"))).append(" ").append(value(p.get("unit"))).append("\n")
                    .append(switch (code) { case "steps" -> "День итога"; case "sleep_duration_min" -> "Дата пробуждения"; default -> "Дата измерения"; })
                    .append(": ").append(value(p.get("local_date"))).append("\n");
            if (!"steps".equals(code)) text.append("Время: ").append(value(p.get("local_time"))).append("\n");
            if ("heart_rate".equals(code)) text.append("Уточнение: ").append(switch (String.valueOf(p.get("qualifier"))) {
                case "resting" -> "в покое"; case "instant" -> "разовое измерение"; default -> "неизвестно";
            }).append("\n");
            text.append("Сообщено: ").append(entry.occurredAt().atZone(zone)).append("\n");
        } else if (entry.type() == EntryType.MEAL) {
            text.append(value(p.get("description"))).append("\nМасса: ").append(value(p.get("mass_g"))).append(" г\n");
            Map<?, ?> nutrients = p.get("nutrients") instanceof Map<?, ?> map ? map : Map.of();
            for (String f : List.of("e", "p", "f", "c")) text.append(FIELDS.get(f)).append(": ")
                    .append(value(nutrients.get(PATHS.get(f)))).append("\n");
            text.append("Основа нутриентов: ").append(switch (String.valueOf(p.get("nutrients_basis"))) {
                case "per_100g" -> "на 100 г"; case "per_serving" -> "на порцию"; default -> "неизвестно";
            }).append("\n");
        } else text.append(value(p.get("text"))).append("\n");
        if (entry.type() != EntryType.METRICS) text.append("Дата: ").append(entry.occurredAt().atZone(zone)).append("\n");
        text.append("Источник: ").append(switch (entry.sourceKind()) {
            case TEXT -> "текстовое сообщение"; case FOOD_PHOTO -> "фотография еды";
            case HEALTH_SCREENSHOT -> "снимок показателей"; case WATCH_PHOTO -> "фотография часов";
            case QUICK_CHECKIN -> "быстрая отметка"; case SEED -> "демонстрационные данные";
        }).append("\n");
        for (String field : fields(entry)) {
            String path = "d".equals(field) ? (entry.type() == EntryType.METRICS ? "local_date" : "occurred_at")
                    : Set.of("e", "p", "f", "c").contains(field) ? "nutrients." + PATHS.get(field) : PATHS.get(field);
            String origin = entry.fieldOrigins().getOrDefault(path, entry.fieldOrigins().get("payload." + path));
            text.append(FIELDS.get(field)).append(" — ").append(originLabel(origin)).append("\n");
        }
        if (entry.type() == EntryType.NOTE) text.append("Текст — ").append(originLabel(entry.fieldOrigins().get("text"))).append("\n");
        if (entry.type() == EntryType.MEAL) text.append("Описание — ").append(originLabel(entry.fieldOrigins().get("description"))).append("\n");
        if (entry.type() == EntryType.METRICS) text.append("Единица — ").append(originLabel(entry.fieldOrigins().get("unit"))).append("\n");
        text.append("Статус: ").append(switch (entry.status()) {
            case DRAFT -> "черновик"; case CONFIRMED -> "подтверждено"; case CANCELLED -> "отменено"; case DELETED -> "удалено";
        });
        List<List<BotAction.InlineButton>> rows = new ArrayList<>();
        if (entry.status() == EntryStatus.DRAFT) {
            rows.add(List.of(button("Сохранить", "s", entry), button("Изменить", "n", entry)));
            rows.add(List.of(button("Не сохранять", "x", entry), button("Обновить", "r", entry)));
            if(dialogs!=null) dialogs.find(new OwnerContext(entry.ownerId())).filter(s -> entry.id().equals(s.activeEntryId())
                    && s.context().containsKey("photo_queue")).ifPresent(s -> rows.add(List.of(
                    new BotAction.InlineButton("Отменить оставшиеся показатели","pq:x:"+entry.id()+":"+s.revision()))));
        }
        if (miniAppUrl != null && entry.status() != EntryStatus.CANCELLED && entry.status() != EntryStatus.DELETED) {
            rows.add(List.of(new BotAction.InlineButton("Открыть в Mini App", null,
                    entryUrl(entry.id()))));
        }
        return withAnswer(update, message(update, limited(text.toString(), 4000), rows));
    }

    private URI entryUrl(UUID entryId) {
        // Preserve the configured deployment prefix and its already encoded components.
        String path = miniAppUrl.getRawPath();
        String suffix = path == null || path.endsWith("/") ? "" : "/";
        return URI.create(miniAppUrl.getScheme() + "://" + miniAppUrl.getRawAuthority()
                + (path == null ? "" : path) + suffix + "diary/" + entryId
                + (miniAppUrl.getRawQuery() == null ? "" : "?" + miniAppUrl.getRawQuery())
                + (miniAppUrl.getRawFragment() == null ? "" : "#" + miniAppUrl.getRawFragment()));
    }

    private static String originLabel(String origin) {
        if (origin == null) return "происхождение не указано";
        return switch (origin) { case "reported" -> "сообщено пользователем"; case "extracted" -> "извлечено";
            case "estimated" -> "оценка"; case "computed" -> "рассчитано"; default -> "происхождение не указано"; };
    }
    private static String value(Object value) { return value == null ? "неизвестно" : limited(value.toString(), 1800); }
    private static String limited(String text, int max) {
        return text.codePointCount(0, text.length()) <= max ? text : text.substring(0, text.offsetByCodePoints(0, max - 1)) + "…";
    }
    private static List<String> fields(Entry entry) {
        return switch (entry.type()) {
            case METRICS -> List.of("v", "d");
            case MEAL -> List.of("m", "e", "p", "f", "c", "d");
            case NOTE -> List.of("d");
            default -> List.of();
        };
    }
    private Map<String, Object> base(Entry entry, ZoneId zone) {
        Map<String, Object> context = new LinkedHashMap<>();
        context.put("schema_version", 1);
        context.put("entry_revision", entry.revision());
        context.put("timezone", zone.getId());
        dialogs.find(new OwnerContext(entry.ownerId())).filter(s -> entry.id().equals(s.activeEntryId()))
                .map(s -> s.context().get("photo_queue")).filter(q -> q instanceof Map<?,?>)
                .ifPresent(q -> context.put("photo_queue",q));
        return context;
    }
    private DialogState save(OwnerContext owner, Entry entry, String step, Map<String, Object> context, BotUpdate update) {
        var preserved=new LinkedHashMap<String,Object>(context);
        dialogs.find(owner).filter(s -> entry.id().equals(s.activeEntryId()))
                .map(s -> s.context().get("photo_queue")).filter(q -> q instanceof Map<?,?>)
                .ifPresent(q -> preserved.put("photo_queue",q));
        return dialogs.save(new SaveDialogStateCommand(owner, entry.id(), step, DateTimePicker.rotate(preserved),
                new TelegramUpdateKey("main", update.updateId())));
    }

    public Optional<DateTimePicker.Form> pickerForm(DialogState state, OwnerContext owner, ZoneId zone) {
        if (!state.step().equals("draft_await") || !"d".equals(state.context().get("field")) || state.activeEntryId() == null)
            return Optional.empty();
        var entry = entries.requireEntry(owner, state.activeEntryId());
        if (!validContext(state, entry) || entry.status() != EntryStatus.DRAFT
                || entry.revision() != number(state.context(), "entry_revision")
                || !zone.getId().equals(state.context().get("timezone"))) return Optional.empty();
        if (entry.type() == EntryType.METRICS) return Optional.of(new DateTimePicker.Form(
                entry.payload().get("local_date") instanceof String date ? date : null, null, false, false, false, "Дата показателя"));
        var local = entry.occurredAt().atZone(zone);
        return Optional.of(new DateTimePicker.Form(local.toLocalDate().toString(), local.toLocalTime().toString(),
                true, false, false, "Дата и время события"));
    }

    public List<BotAction> applyPicker(BotUpdate update, OwnerContext owner, ZoneId zone, DialogState state,
                                      DateTimePicker.Selection selection) {
        var entry = entries.requireEntry(owner, state.activeEntryId());
        if (update.updateId() <= savedUpdate(state) || entry.revision() != number(state.context(), "entry_revision")
                || entry.status() != EntryStatus.DRAFT) return card(update, entry, zone, "Запись изменилась. Выберите исправление заново.");
        var context = base(entry, zone);
        context.put("field", "d"); context.put("operation", "patch"); context.put("value", selection.date().toString());
        if (selection.time() != null) context.put("picker_time", selection.time().toString());
        return recover(update, owner, save(owner, entry, "draft_pending", context, update), zone);
    }
    private static long number(Map<String, Object> context, String name) {
        if (!(context.get(name) instanceof Number value)) throw new IllegalArgumentException("Missing integer context field");
        return new BigDecimal(value.toString()).longValueExact();
    }
    private static boolean validUpdateKey(String key) {
        if (key == null || !key.matches("main:[0-9]+")) return false;
        try { return Long.parseLong(key.substring(5)) >= 0; }
        catch (NumberFormatException invalid) { return false; }
    }
    private static long savedUpdate(DialogState state) {
        String key = state.telegramUpdateKey();
        if (!validUpdateKey(key)) return Long.MAX_VALUE;
        return Long.parseLong(key.substring(key.lastIndexOf(':') + 1));
    }
    private static BotAction.InlineButton button(String text, String action, Entry entry) {
        return new BotAction.InlineButton(text, "dr:" + action + ":" + entry.id() + ":" + entry.revision());
    }
    private static List<List<BotAction.InlineButton>> backRows(Entry entry) { return List.of(List.of(button("Назад к записи", "b", entry))); }
    private static BotAction.SendInlineMessage message(BotUpdate update, String text, List<List<BotAction.InlineButton>> rows) {
        return new BotAction.SendInlineMessage(update.chatId(), text, rows);
    }
    private static List<BotAction> answer(BotUpdate update, String text) {
        return update.callbackId() == null ? List.of(message(update, text, List.of()))
                : List.of(new BotAction.AnswerCallback(update.callbackId(), text));
    }
    private static List<BotAction> withAnswer(BotUpdate update, BotAction message) {
        return update.callbackId() == null ? List.of(message)
                : List.of(new BotAction.AnswerCallback(update.callbackId(), null), message);
    }
}
