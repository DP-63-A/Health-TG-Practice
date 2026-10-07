package org.healthtg.bot.text;

import org.healthtg.bot.BotAction;
import org.healthtg.bot.BotUpdate;
import org.healthtg.bot.correction.CorrectionInputParser;
import org.healthtg.bot.correction.CorrectionResult;
import org.healthtg.bot.draft.DraftReviewFlow;
import org.healthtg.core.dialog.DialogState;
import org.healthtg.core.dialog.DialogStateService;
import org.healthtg.core.dialog.SaveDialogStateCommand;
import org.healthtg.core.entry.*;

import java.math.BigDecimal;
import java.time.*;
import java.util.*;

/** Persisted conversation before Entry exists. Creation uses the final update key;
 * Core deduplicates retries and CoreBotFlow recovers an Entry after a lost response. */
public final class TextDialogFlow {
    private final EntryCoreService entries;
    private final DialogStateService dialogs;
    private final TextInputParser parser;
    private final DraftReviewFlow drafts;
    private final Clock clock;
    private final CorrectionInputParser corrections = new CorrectionInputParser();

    public TextDialogFlow(EntryCoreService entries, DialogStateService dialogs, TextInputParser parser,
                          DraftReviewFlow drafts, Clock clock) {
        this.entries = entries;
        this.dialogs = dialogs;
        this.parser = parser;
        this.drafts = drafts;
        this.clock = clock;
    }

    public Optional<List<BotAction>> guard(BotUpdate update, OwnerContext owner) {
        Optional<DialogState> found = dialogs.find(owner).filter(TextDialogFlow::pending);
        if (found.isEmpty()) return Optional.empty();
        DialogState state = found.get();
        if (!valid(state)) return Optional.of(invalid(update, owner));
        if (update.updateId() <= savedUpdate(state)) return Optional.of(response(update, "Команда уже обработана. Продолжите текущий диалог.", List.of()));
        return Optional.of(prompt(update, state, "Сначала завершите или отмените текстовый ввод."));
    }

    public List<BotAction> message(BotUpdate update, OwnerContext owner, ZoneId zone) {
        Optional<DialogState> stored = dialogs.find(owner).filter(TextDialogFlow::pending);
        if (stored.isPresent()) {
            DialogState state = stored.get();
            if (!valid(state)) return invalid(update, owner);
            if (update.updateId() <= savedUpdate(state)) return prompt(update, state, "Продолжите текущий диалог.");
            TextParseResult checked = parser.parse(update.text());
            if (checked.outcome() == TextParseResult.Outcome.REJECTED) {
                return prompt(update, state, checked.issues().getFirst().message());
            }
            if ("text_replace".equals(state.step())) return begin(update, owner, zone, checked);
            if ("text_note_offer".equals(state.step())) return prompt(update, state,
                    "Текст уже сохранён для предложения заметки. Выберите действие кнопкой.");
            return clarify(update, owner, state, zone);
        }
        return begin(update, owner, zone, parser.parse(update.text()));
    }

    public List<BotAction> callback(BotUpdate update, OwnerContext owner, ZoneId zone) {
        Optional<DialogState> stored = dialogs.find(owner).filter(TextDialogFlow::pending);
        if (stored.isEmpty()) return response(update, "Это действие уже завершено.", List.of());
        DialogState state = stored.get();
        if (!valid(state)) return invalid(update, owner);
        String[] parts = update.callbackData().split(":", -1);
        if (parts.length != 4 || !parts[2].equals(String.valueOf(originalUpdate(state)))
                || !parts[3].equals(String.valueOf(state.revision())) || update.updateId() <= savedUpdate(state)) {
            return prompt(update, state, "Кнопка устарела. Используйте текущие действия.");
        }
        // Never cancel or replace an Entry through the pre-entry dialog.
        Optional<Entry> active = entries.findActiveDraft(owner);
        if (active.isPresent()) return drafts.card(update, active.get(), zone, "Завершите текущий черновик.");
        if (parts[1].equals("x")) {
            dialogs.save(new SaveDialogStateCommand(owner, null, "idle", Map.of("schema_version", 1), key(update.updateId())));
            return response(update, "Текстовый ввод отменён. Можно отправить новое сообщение.", List.of());
        }
        if (parts[1].equals("z")) {
            DialogState next = save(owner, "text_replace", state.context(), update);
            return prompt(update, next, "Отправьте исправленное сообщение целиком. Прежний ввод не будет сохранён.");
        }
        if (!parts[1].equals("n") || !state.step().equals("text_note_offer")) return prompt(update, state, "Кнопка устарела.");
        Map<String, Object> data = new LinkedHashMap<>(state.context());
        data.putIfAbsent("original_update", originalUpdate(state));
        return advance(update, owner, data, zone);
    }

    private List<BotAction> begin(BotUpdate update, OwnerContext owner, ZoneId zone, TextParseResult parsed) {
        if (parsed.outcome() == TextParseResult.Outcome.REJECTED) {
            return response(update, parsed.issues().getFirst().message(), List.of());
        }
        Map<String, Object> data = new LinkedHashMap<>();
        data.put("schema_version", 1);
        data.put("original_update", update.updateId());
        data.put("original_text", update.text());
        if (update.messageSentAt() != null) data.put("message_sent_at", update.messageSentAt().toString());
        if (parsed.data() != null) {
            data.put("type", parsed.data().type());
            data.put("payload", parsed.data().payload());
            data.put("field_origins", parsed.data().fieldOrigins());
            if (parsed.data().date() != null) data.put("date", parsed.data().date().toString());
            if (parsed.data().time() != null) data.put("time", parsed.data().time().toString());
        }
        data.put("issues", parsed.issues().stream().map(issue -> Map.of(
                "code", issue.code(), "field", issue.field(), "message", issue.message())).toList());
        if (parsed.outcome() == TextParseResult.Outcome.NOTE_SUGGESTED) {
            // The text parser deliberately does not choose dates for notes.
            var explicit = ExplicitTime.extract(update.text());
            if (!explicit.rejected() && !explicit.ambiguous()) {
                if (explicit.date() != null) data.put("date", explicit.date().toString());
                if (explicit.time() != null) data.put("time", explicit.time().toString());
            }
            data.put("issues", List.of());
            return prompt(update, save(owner, "text_note_offer", data, update),
                    "Не удалось надёжно выделить показатели. Предложить исходный текст как обычную заметку?");
        }
        if (parsed.data() == null) {
            return prompt(update, save(owner, "text_replace", data, update), issueMessage(data));
        }
        return advance(update, owner, data, zone);
    }

    private List<BotAction> clarify(BotUpdate update, OwnerContext owner, DialogState state, ZoneId zone) {
        Map<String, Object> data = new LinkedHashMap<>(state.context());
        data.putIfAbsent("original_update", originalUpdate(state));
        Map<String, Object> payload = map(data.get("payload"));
        Map<String, Object> origins = map(data.get("field_origins"));
        if (!data.containsKey("date") && payload.get("local_date") instanceof String date) data.put("date", date);
        String field = expected(data);
        String input = update.text().strip();
        if (field.equals("date")) {
            var parsed = corrections.parseDate(input);
            if (!(parsed instanceof CorrectionResult.Success<?> success)) {
                return prompt(update, state, "Введите дату: ДД.ММ.ГГГГ или ГГГГ-ММ-ДД.");
            }
            data.put("date", success.value().toString());
            if ("metrics".equals(data.get("type"))) {
                payload.put("local_date", success.value().toString());
                origins.put("local_date", "reported");
            }
            removeIssues(data, Set.of("MISSING_DATE", "UNSUPPORTED_DATE_EXPRESSION"));
        } else if (field.equals("time")) {
            try {
                if (!input.matches("[0-9]{2}:[0-9]{2}")) throw new DateTimeException("format");
                data.put("time", LocalTime.parse(input).toString());
            } catch (DateTimeException invalid) { return prompt(update, state, "Введите время: ЧЧ:ММ, например 14:30."); }
        } else if (field.equals("unit")) {
            if (!input.toLowerCase(Locale.ROOT).matches("(?:bpm|уд/мин|ударов в минуту)")) {
                return prompt(update, state, "Укажите единицу пульса: уд/мин или bpm.");
            }
            payload.put("unit", "bpm"); origins.put("unit", "reported");
            removeIssues(data, Set.of("MISSING_UNIT"));
        } else if (field.equals("day_scope")) {
            if (!input.toLowerCase(Locale.ROOT).matches("(?:да|за день|итог дня|дневной итог)")) {
                return prompt(update, state, "Это итог за день? Ответьте «да», либо выберите «Ввести заново» или отмену.");
            }
            removeIssues(data, Set.of("MISSING_DAY_SCOPE"));
        } else {
            return prompt(update, state, "Выберите «Ввести заново» и отправьте одно однозначное событие целиком.");
        }
        data.put("payload", payload); data.put("field_origins", origins);
        return advance(update, owner, data, zone);
    }

    private List<BotAction> advance(BotUpdate update, OwnerContext owner, Map<String, Object> data, ZoneId zone) {
        String field = expected(data);
        if (!field.isEmpty()) return prompt(update, save(owner, "text_clarification", data, update), "Уточните данные.");
        if ("metrics".equals(data.get("type")) && !data.containsKey("message_sent_at")) {
            return prompt(update, save(owner, "text_replace", data, update),
                    "Не удалось восстановить время исходного сообщения. Отправьте показатель заново с датой.");
        }
        // The final answer plus the saved context determine a retryable creation command.
        try {
            data.put("occurred_at", timestamp(data, zone).toString());
            if ("note".equals(data.get("type"))) {
                Map<String, Object> origins = map(data.get("field_origins"));
                origins.put("occurred_at", "reported");
                data.put("field_origins", origins);
            }
        }
        catch (DateTimeException invalid) {
            data.remove("time");
            return prompt(update, save(owner, "text_clarification", data, update),
                    "Это время не существует или неоднозначно из-за перевода часов. Укажите другое время.");
        }
        // Creation already has a unique update key in Core. Keep the previous dialog
        // until creation succeeds: the same delivered update can retry from it. If
        // saving the review step fails afterwards, CoreBotFlow recovers the active Entry.
        Optional<DialogState> current = dialogs.find(owner);
        if (current.isPresent() && update.updateId() <= savedUpdate(current.get())) {
            return prompt(update, current.get(), "Сообщение уже обработано. Продолжите текущий диалог.");
        }
        try {
            Entry entry = create(owner, data, update.updateId());
            dialogs.save(new SaveDialogStateCommand(owner, entry.id(), "draft_review",
                    Map.of("entry_revision", entry.revision()), key(update.updateId())));
            return drafts.card(update, entry, zone, "Проверьте черновик.");
        }
        catch (EntryValidationException | DateTimeException invalid) {
            return prompt(update, save(owner, "text_replace", data, update), "Значения не прошли проверку. Отправьте исправленное сообщение целиком; запись не подтверждена.");
        }
    }

    private Entry create(OwnerContext owner, Map<String, Object> data, long updateId) {
        Map<String, Object> payload = map(data.get("payload"));
        for (String field : List.of("value", "mass_g")) {
            if (payload.get(field) instanceof String number) payload.put(field, new BigDecimal(number));
        }
        Map<String, String> origins = new LinkedHashMap<>();
        map(data.get("field_origins")).forEach((key, value) -> origins.put(key, (String) value));
        return entries.createDraft(new CreateDraftCommand(owner, EntryType.valueOf(((String) data.get("type")).toUpperCase(Locale.ROOT)),
                SourceKind.TEXT, Map.of("telegram_update_id", data.get("original_update")),
                Instant.parse((String) data.get("occurred_at")), payload, origins, key(updateId))).entry();
    }

    private Instant timestamp(Map<String, Object> data, ZoneId zone) {
        if ("metrics".equals(data.get("type"))) return Instant.parse((String) data.get("message_sent_at"));
        LocalDate date = LocalDate.parse((String) data.get("date"));
        LocalTime time = data.get("time") instanceof String text ? LocalTime.parse(text) : clock.instant().atZone(zone).toLocalTime();
        LocalDateTime local = date.atTime(time);
        if ("note".equals(data.get("type"))) {
            var offsets = zone.getRules().getValidOffsets(local);
            if (offsets.size() != 1) throw new DateTimeException("Ambiguous time");
            return local.toInstant(offsets.getFirst());
        }
        return local.atZone(zone).toInstant();
    }

    private static String expected(Map<String, Object> data) {
        if ("note".equals(data.get("type"))) {
            if (!data.containsKey("date")) return "date";
            if (!data.containsKey("time")) return "time";
            return "";
        }
        List<Map<String, Object>> issues = issues(data);
        for (var issue : issues) {
            if (!Set.of("MISSING_DATE", "UNSUPPORTED_DATE_EXPRESSION", "MISSING_UNIT", "MISSING_DAY_SCOPE")
                    .contains(issue.get("code"))) return "replace";
        }
        Map<String, Object> payload = map(data.get("payload"));
        if ("metrics".equals(data.get("type")) && !payload.containsKey("value")) return "replace";
        if (!data.containsKey("date") && !(payload.get("local_date") instanceof String)) return "date";
        if (issues.stream().anyMatch(i -> Set.of("MISSING_DATE", "UNSUPPORTED_DATE_EXPRESSION").contains(i.get("code")))) return "date";
        if (issues.stream().anyMatch(i -> "MISSING_UNIT".equals(i.get("code")))
                || ("heart_rate".equals(payload.get("code")) && !payload.containsKey("unit"))) return "unit";
        if (issues.stream().anyMatch(i -> "MISSING_DAY_SCOPE".equals(i.get("code")))) return "day_scope";
        return "";
    }

    private List<BotAction> prompt(BotUpdate update, DialogState state, String notice) {
        List<List<BotAction.InlineButton>> rows = new ArrayList<>();
        if (state.step().equals("text_note_offer")) rows.add(List.of(button("Создать заметку", "n", state)));
        String question = state.step().equals("text_clarification") ? switch (expected(state.context())) {
            case "date" -> "Введите календарную дату: ДД.ММ.ГГГГ или ГГГГ-ММ-ДД.";
            case "time" -> "Введите время заметки: ЧЧ:ММ. Часовой пояс — из вашего профиля.";
            case "unit" -> "Укажите единицу пульса: уд/мин или bpm.";
            case "day_scope" -> "Это итог за день? Ответьте «да», если это так.";
            default -> issueMessage(state.context());
        } : "";
        rows.add(List.of(button("Ввести заново", "z", state), button("Отменить ввод", "x", state)));
        return response(update, notice + (question.isBlank() ? "" : "\n" + question), rows);
    }

    private List<BotAction> invalid(BotUpdate update, OwnerContext owner) {
        dialogs.save(new SaveDialogStateCommand(owner, null, "idle", Map.of("schema_version", 1), key(update.updateId())));
        return response(update, "Не удалось восстановить текстовый ввод. Запись не подтверждена. Отправьте сообщение заново.", List.of());
    }

    private static boolean valid(DialogState state) {
        try {
            Map<String, Object> data = state.context();
            if (!Set.of("text_note_offer", "text_clarification", "text_replace").contains(state.step())) return false;
            if (!(data.get("schema_version") instanceof Number version)
                    || new BigDecimal(version.toString()).compareTo(BigDecimal.ONE) != 0) return false;
            if (!(data.get("original_text") instanceof String text) || text.isBlank() || text.codePointCount(0, text.length()) > 2000) return false;
            if (originalUpdate(state) < 0 || originalUpdate(state) > savedUpdate(state)) return false;
            if (data.containsKey("original_update") && !(data.get("original_update") instanceof Number)) return false;
            if (data.containsKey("issues") && !(data.get("issues") instanceof List<?>)) return false;
            for (var issue : issues(data)) {
                if (!(issue.get("code") instanceof String) || !(issue.get("message") instanceof String)) return false;
            }
            if (!"text_replace".equals(state.step())) {
                if (!(data.get("type") instanceof String type) || !Set.of("note", "metrics", "meal").contains(type)) return false;
                if ("text_note_offer".equals(state.step()) && !"note".equals(type)) return false;
                Map<String, Object> payload = map(data.get("payload"));
                if ("note".equals(type) && !text.equals(payload.get("text"))) return false;
                for (String field : List.of("value", "mass_g")) {
                    Object number = payload.get(field);
                    if (number != null) new BigDecimal(number.toString());
                }
                if (payload.get("local_date") != null) LocalDate.parse((String) payload.get("local_date"));
                if (map(data.get("field_origins")).values().stream().anyMatch(value -> !(value instanceof String))) return false;
            }
            if (data.get("date") != null) LocalDate.parse((String) data.get("date"));
            if (data.get("time") != null) LocalTime.parse((String) data.get("time"));
            if (data.get("message_sent_at") != null) Instant.parse((String) data.get("message_sent_at"));
            return true;
        } catch (RuntimeException invalid) { return false; }
    }

    private static boolean pending(DialogState state) { return state.step().startsWith("text_"); }
    private DialogState save(OwnerContext owner, String step, Map<String, Object> data, BotUpdate update) {
        return dialogs.save(new SaveDialogStateCommand(owner, null, step, data, key(update.updateId())));
    }
    private static TelegramUpdateKey key(long updateId) { return new TelegramUpdateKey("main", updateId); }
    private static long savedUpdate(DialogState state) {
        String key = state.telegramUpdateKey();
        if (key == null || !key.matches("main:[0-9]+")) throw new IllegalArgumentException("update key");
        return Long.parseLong(key.substring(5));
    }
    private static long originalUpdate(DialogState state) {
        Object value = state.context().get("original_update");
        return value instanceof Number number ? new BigDecimal(number.toString()).longValueExact() : savedUpdate(state);
    }
    private static Map<String, Object> map(Object value) {
        if (!(value instanceof Map<?, ?> source)) throw new IllegalArgumentException("map");
        Map<String, Object> copy = new LinkedHashMap<>();
        source.forEach((key, item) -> copy.put((String) key, item));
        return copy;
    }
    private static List<Map<String, Object>> issues(Map<String, Object> data) {
        if (!(data.get("issues") instanceof List<?> list)) return List.of();
        return list.stream().map(TextDialogFlow::map).toList();
    }
    private static void removeIssues(Map<String, Object> data, Set<String> codes) {
        data.put("issues", issues(data).stream().filter(i -> !codes.contains(i.get("code"))).toList());
    }
    private static String issueMessage(Map<String, Object> data) {
        return issues(data).stream().map(i -> String.valueOf(i.get("message"))).findFirst()
                .orElse("Отправьте одно событие целиком с явными значениями и датой.");
    }
    private static BotAction.InlineButton button(String title, String action, DialogState state) {
        return new BotAction.InlineButton(title, "tx:" + action + ":" + originalUpdate(state) + ":" + state.revision());
    }
    private static List<BotAction> response(BotUpdate update, String text, List<List<BotAction.InlineButton>> rows) {
        var message = new BotAction.SendInlineMessage(update.chatId(), text, rows);
        return update.callbackId() == null ? List.of(message) : List.of(new BotAction.AnswerCallback(update.callbackId(), null), message);
    }
}
