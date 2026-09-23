package org.healthtg.bot;

import java.time.Clock;
import java.time.format.DateTimeFormatter;
import java.util.*;

/** Single-process dialogue. All mutable transitions are serialized, including storage calls. */
public final class CheckinDialogue implements QuickCheckin {
    private static final String NOTICE = "\nТестовый режим: в БД не сохранено.";
    private static final DateTimeFormatter TIME = DateTimeFormatter.ofPattern("dd.MM.uuuu HH:mm:ss 'Europe/Warsaw' XXX").withZone(java.time.ZoneId.of("Europe/Warsaw"));
    private final CheckinStore store;
    private final Clock clock;
    private final Map<String, Session> sessions = new HashMap<>();
    private final Map<Long, Session> current = new HashMap<>();
    private static final class Session {
        final String id = UUID.randomUUID().toString().replace("-", "");
        final long owner;
        final Integer startUpdate;
        CheckinStore.Category category;
        Integer categoryUpdate;
        CheckinStore.Request request;
        CheckinStore.Saved saved;
        boolean cancelled;
        Session(long owner, Integer startUpdate) { this.owner = owner; this.startUpdate = startUpdate; }
    }
    public CheckinDialogue(CheckinStore store, Clock clock) {
        this.store = Objects.requireNonNull(store);
        this.clock = Objects.requireNonNull(clock);
    }
    @Override public String begin(long user) { return "Откройте /state для выбора категории."; }

    @Override public synchronized List<BotAction> begin(BotUpdate update) {
        Session old = current.get(update.senderId());
        if (old != null && update.updateId() != null && old.startUpdate != null && update.updateId() <= old.startUpdate) {
            return List.of(categories(update.chatId(), old));
        }
        if (old != null && old.request != null && old.saved == null) {
            return List.of(message(update.chatId(), "Результат предыдущего сохранения ещё не подтверждён. Сначала повторите его проверку.",
                    List.of(List.of(button("Повторить", old, "retry")))));
        }
        if (sessions.size() >= 1000) return List.of(message(update.chatId(), "Лимит тестовых диалогов достигнут. Перезапустите тестовый бот; записи в памяти будут потеряны.", List.of()));
        Session session = new Session(update.senderId(), update.updateId());
        sessions.put(session.id, session);
        current.put(session.owner, session);
        return List.of(categories(update.chatId(), session));
    }
    private BotAction.InlineMessage categories(long chat, Session s) {
        return message(chat, "Выберите категорию:", Arrays.stream(CheckinStore.Category.values())
                .map(c -> List.of(button(c.label(), s, "c" + c.ordinal()))).toList());
    }
    private BotAction.InlineMessage scores(long chat, Session s) {
        return message(chat, s.category.label() + "\nВыберите оценку: 1 — очень плохо, 5 — очень хорошо.",
                List.of(java.util.stream.IntStream.rangeClosed(1, 5).mapToObj(i -> button(Integer.toString(i), s, "v" + i)).toList()));
    }
    @Override public synchronized List<BotAction> callback(BotUpdate update) {
        List<BotAction> actions = new ArrayList<>();
        actions.add(new BotAction.AnswerCallback(update.callbackId()));
        actions.add(process(update));
        return List.copyOf(actions);
    }
    private BotAction process(BotUpdate u) {
        String data = u.callbackData();
        if (data == null || !data.matches("q:[a-f0-9]{32}:(c[0-3]|v[1-5]|retry|cancel)")) return stale(u);
        String[] parts = data.split(":");
        Session s = sessions.get(parts[1]);
        if (s == null || s.owner != u.senderId()) return stale(u);
        String action = parts[2];
        if (action.equals("cancel")) {
            if (s.saved == null) return stale(u);
            if (!s.cancelled) {
                try { store.cancel(s.owner, s.saved.id()); s.cancelled = true; }
                catch (RuntimeException e) { return message(u.chatId(), "Не удалось отменить отметку. Попробуйте ещё раз.", List.of(List.of(button("Повторить отмену", s, "cancel")))); }
            }
            return message(u.chatId(), "Тестовая отметка отменена." + NOTICE, List.of());
        }
        if (current.get(s.owner) != s) return stale(u);
        if (s.saved != null) return saved(u.chatId(), s);
        if (action.startsWith("c")) {
            if (s.request != null) return stale(u);
            if (s.category == null) {
                s.category = CheckinStore.Category.values()[action.charAt(1) - '0'];
                s.categoryUpdate = u.updateId();
            } else if (!Objects.equals(s.categoryUpdate, u.updateId())) return stale(u);
            return scores(u.chatId(), s);
        }
        if (s.category == null) return stale(u);
        if (s.request == null) {
            if (!action.startsWith("v")) return stale(u);
            s.request = new CheckinStore.Request(s.id, s.owner, s.category, action.charAt(1) - '0', clock.instant());
        }
        // Every later button/retry uses the first selected payload, even after an ambiguous storage failure.
        try { s.saved = Objects.requireNonNull(store.save(s.request)); }
        catch (RuntimeException e) {
            return message(u.chatId(), "Не удалось подтвердить сохранение. Повторите ту же отметку.",
                    List.of(List.of(button("Повторить", s, "retry"))));
        }
        return saved(u.chatId(), s);
    }
    private BotAction.InlineMessage saved(long chat, Session s) {
        if (s.cancelled) return message(chat, "Тестовая отметка уже отменена." + NOTICE, List.of());
        var r = s.saved.request();
        return message(chat, r.category().label() + ": " + r.value() + "/5\n" + TIME.format(r.occurredAt()) + NOTICE,
                List.of(List.of(button("Отменить", s, "cancel"))));
    }
    private BotAction.InlineMessage stale(BotUpdate u) {
        return message(u.chatId(), "Кнопка недействительна. После перезапуска прежние тестовые записи недоступны. Начните заново: /state.", List.of());
    }
    private static BotAction.Button button(String text, Session s, String action) { return new BotAction.Button(text, "q:" + s.id + ":" + action); }
    private static BotAction.InlineMessage message(long chat, String text, List<List<BotAction.Button>> rows) { return new BotAction.InlineMessage(chat, text, rows); }
}
