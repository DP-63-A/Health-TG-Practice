package org.healthtg.bot;

import java.time.Instant;
import java.util.HashMap;
import java.util.Map;

/** Bot-owned port, not the shared BE1 API. An adapter must supply durable idempotency and ownership. */
public interface CheckinStore {
    enum Category {
        SLEEP("😴 Качество сна"), DIGESTION("🍽 Комфорт пищеварения"),
        WELLBEING("💚 Самочувствие"), MOOD("🙂 Настроение");
        private final String label;
        Category(String label) { this.label = label; }
        public String label() { return label; }
    }
    record Request(String key, long owner, Category category, int value, Instant occurredAt) {
        public Request {
            if (key == null || key.isBlank() || owner <= 0 || category == null || value < 1 || value > 5 || occurredAt == null)
                throw new IllegalArgumentException("Invalid checkin");
        }
    }
    record Saved(String id, Request request) {}
    Saved save(Request request);
    void cancel(long owner, String id);

    /** Explicit demonstration only; retains tombstones so cancelling never permits a duplicate save. */
    final class Fixture implements CheckinStore {
        private final Map<String, Saved> entries = new HashMap<>();
        private final java.util.Set<String> cancelled = new java.util.HashSet<>();
        synchronized boolean isCancelled(String id) { return cancelled.contains(id); }
        synchronized int size() { return entries.size(); }
        public synchronized Saved save(Request request) {
            Saved existing = entries.get(request.key());
            if (existing != null) {
                if (!existing.request().equals(request)) throw new IllegalArgumentException("Key reused");
                return existing;
            }
            if (entries.size() >= 1000) throw new IllegalStateException("Fixture capacity reached");
            Saved saved = new Saved(request.key(), request);
            entries.put(request.key(), saved);
            return saved;
        }
        public synchronized void cancel(long owner, String id) {
            Saved saved = entries.get(id);
            if (saved == null || saved.request().owner() != owner) throw new IllegalArgumentException("Unknown checkin");
            cancelled.add(id);
        }
    }
}
