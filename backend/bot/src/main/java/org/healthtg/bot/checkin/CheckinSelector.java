package org.healthtg.bot.checkin;

import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.regex.Pattern;
import java.util.stream.IntStream;

/** In-memory UI state only. Not registered with Spring or the production bot. */
public final class CheckinSelector {
    private static final Pattern CALLBACK = Pattern.compile("q:[a-f0-9]{32}:(c[0-3]|v[1-5])");
    private final Map<Long, Session> sessions = new LinkedHashMap<>();
    private final int capacity;

    public enum Status { ACCEPTED, SELECTED, REPLAY, REJECTED }
    public enum Stage { CATEGORY, SCORE, COMPLETE, INVALID }
    public record Button(String text, String callbackData) {}
    public record View(Stage stage, String text, List<List<Button>> rows, CheckinSelection selection) {
        public View { rows = rows.stream().map(List::copyOf).toList(); }
    }
    /** Only SELECTED is a new selection event. REPLAY must not trigger a new operation. */
    public record Outcome(Status status, View view) {}

    private static final class Session {
        final UUID id;
        final long owner;
        final long startUpdate;
        long lastUpdate;
        String lastCallback;
        CheckinCategory category;
        CheckinSelection selection;

        Session(long owner, long updateId) {
            this(UUID.randomUUID(), owner, updateId, updateId, null);
        }

        Session(UUID id, long owner, long startUpdate, long lastUpdate, CheckinCategory category) {
            this.id = id;
            this.owner = owner;
            this.startUpdate = startUpdate;
            this.lastUpdate = lastUpdate;
            this.category = category;
        }
    }

    public CheckinSelector() { this(1000); }

    public CheckinSelector(int capacity) {
        if (capacity < 1) throw new IllegalArgumentException("capacity must be positive");
        this.capacity = capacity;
    }

    /** Caller must check the allowlist first. chatId must identify the owner's private chat. */
    public synchronized Outcome begin(long owner, long chatId, long updateId) {
        if (!validContext(owner, chatId, updateId)) return rejected();
        Session previous = sessions.get(owner);
        if (previous != null) {
            if (updateId == previous.startUpdate) return new Outcome(Status.REPLAY, view(previous));
            if (updateId <= previous.lastUpdate) return rejected();
            sessions.remove(owner);
        }
        if (sessions.size() >= capacity) sessions.remove(sessions.keySet().iterator().next());
        Session session = new Session(owner, updateId);
        sessions.put(owner, session);
        return new Outcome(Status.ACCEPTED, view(session));
    }

    public synchronized Outcome callback(long owner, long chatId, long updateId, String data) {
        if (!validContext(owner, chatId, updateId) || data == null || !CALLBACK.matcher(data).matches()) {
            return rejected();
        }
        Session session = sessions.get(owner);
        if (session == null || !data.substring(2, 34).equals(token(session))) return rejected();
        if (updateId == session.lastUpdate && data.equals(session.lastCallback)) {
            return new Outcome(Status.REPLAY, view(session));
        }
        if (updateId <= session.lastUpdate) return rejected();
        String action = data.substring(35);
        if (session.selection != null) {
            if (!action.equals("v" + session.selection.score())) return rejected();
            session.lastUpdate = updateId;
            session.lastCallback = data;
            return new Outcome(Status.REPLAY, view(session));
        }
        if (action.charAt(0) == 'c') {
            if (session.category != null) return rejected();
            session.category = CheckinCategory.values()[action.charAt(1) - '0'];
        } else {
            if (session.category == null) return rejected();
            session.selection = new CheckinSelection(session.id, owner, session.category, action.charAt(1) - '0');
        }
        session.lastUpdate = updateId;
        session.lastCallback = data;
        return new Outcome(session.selection == null ? Status.ACCEPTED : Status.SELECTED, view(session));
    }

    public synchronized void restore(long owner, UUID selectionId, long startUpdate, long lastUpdate,
                                     CheckinCategory category) {
        if (owner <= 0 || selectionId == null || startUpdate < 0 || lastUpdate < startUpdate) {
            throw new IllegalArgumentException("Invalid restored checkin session");
        }
        sessions.putIfAbsent(owner, new Session(selectionId, owner, startUpdate, lastUpdate, category));
    }

    public synchronized long startUpdate(long owner) {
        Session session = sessions.get(owner);
        if (session == null) throw new IllegalStateException("Checkin session not found");
        return session.startUpdate;
    }

    public synchronized CheckinCategory category(long owner) {
        Session session = sessions.get(owner);
        if (session == null) throw new IllegalStateException("Checkin session not found");
        return session.category;
    }

    private static boolean validContext(long owner, long chatId, long updateId) {
        return owner > 0 && owner == chatId && updateId >= 0;
    }

    private static String token(Session session) { return session.id.toString().replace("-", ""); }

    private static Button button(Session session, String label, String action) {
        return new Button(label, "q:" + token(session) + ":" + action);
    }

    private static View view(Session session) {
        if (session.selection != null) {
            return new View(Stage.COMPLETE, "Выбрано: " + session.category.label() + ": "
                    + session.selection.score() + "/5. Данные не сохранены.", List.of(), session.selection);
        }
        if (session.category != null) {
            return new View(Stage.SCORE, session.category.label()
                    + "\nВыберите оценку: 1 — очень плохо / низкий комфорт, 5 — очень хорошо / высокий комфорт.",
                    List.of(IntStream.rangeClosed(1, 5)
                            .mapToObj(score -> button(session, Integer.toString(score), "v" + score)).toList()), null);
        }
        return new View(Stage.CATEGORY, "Выберите категорию:", Arrays.stream(CheckinCategory.values())
                .map(category -> List.of(button(session, category.label(), "c" + category.ordinal()))).toList(), null);
    }

    private static Outcome rejected() {
        return new Outcome(Status.REJECTED, new View(Stage.INVALID,
                "Кнопка недействительна. Начните заново: /state.", List.of(), null));
    }
}
