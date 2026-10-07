package org.healthtg.bot;

import org.healthtg.bot.checkin.CheckinCategory;
import org.healthtg.core.entry.Entry;
import org.healthtg.core.entry.EntryCoreService;
import org.healthtg.core.entry.EntryNotFoundException;
import org.healthtg.core.entry.EntryStatus;
import org.healthtg.core.entry.EntryStatusConflictException;
import org.healthtg.core.entry.EntryType;
import org.healthtg.core.entry.EntryVersionConflictException;
import org.healthtg.core.entry.OwnerContext;
import org.healthtg.core.entry.SourceKind;

import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.Arrays;
import java.util.List;
import java.util.UUID;

/** Receipts and deletion of confirmed quick check-ins; never changes dialog state. */
final class CheckinReceiptFlow {
    private static final DateTimeFormatter TIME = DateTimeFormatter.ofPattern("dd.MM.uuuu HH:mm XXX");
    private final EntryCoreService entries;

    CheckinReceiptFlow(EntryCoreService entries) {
        this.entries = entries;
    }

    List<BotAction> replay(BotUpdate update, OwnerContext owner, Object entryId, ZoneId zone) {
        if (!(entryId instanceof String id)) return unavailable(update);
        try {
            return receipt(update, entries.requireEntry(owner, UUID.fromString(id)), zone, true);
        } catch (IllegalArgumentException | EntryNotFoundException invalid) {
            return unavailable(update);
        }
    }

    List<BotAction> receipt(BotUpdate update, Entry entry, ZoneId zone, boolean repeated) {
        if (!quickCheckin(entry)) return unavailable(update);
        if (entry.status() == EntryStatus.DELETED) return removed(update, true);
        if (entry.status() != EntryStatus.CONFIRMED) return unavailable(update);
        String category = Arrays.stream(CheckinCategory.values())
                .filter(value -> value.code().equals(entry.payload().get("category")))
                .map(CheckinCategory::label).findFirst().orElseThrow(() ->
                        new IllegalArgumentException("Invalid stored check-in category"));
        String text = (repeated ? "Отметка уже сохранена." : "Отметка сохранена.")
                + "\n" + category + ": " + entry.payload().get("score") + " из 5."
                + "\nВремя отметки: " + TIME.format(entry.occurredAt().atZone(zone)) + ".";
        return List.of(new BotAction.AnswerCallback(update.callbackId(), repeated ? "Уже сохранено" : "Сохранено"),
                new BotAction.SendInlineMessage(update.chatId(), text, List.of(List.of(
                        new BotAction.InlineButton("Отменить отметку", "qc:" + entry.id() + ":" + entry.revision())))));
    }

    List<BotAction> cancel(BotUpdate update, OwnerContext owner) {
        String data = update.callbackData();
        if (data.length() > 64) return unavailable(update);
        String[] parts = data.split(":", -1);
        if (parts.length != 3 || !parts[0].equals("qc") || !parts[2].matches("[1-9][0-9]{0,18}")) {
            return unavailable(update);
        }
        UUID id;
        long revision;
        try {
            id = UUID.fromString(parts[1]);
            if (!id.toString().equals(parts[1])) return unavailable(update);
            revision = Long.parseLong(parts[2]);
        } catch (IllegalArgumentException invalid) {
            return unavailable(update);
        }
        try {
            Entry entry = entries.requireEntry(owner, id);
            if (!quickCheckin(entry)) return unavailable(update);
            if (entry.status() == EntryStatus.DELETED) return removed(update, true);
            if (entry.status() != EntryStatus.CONFIRMED || entry.revision() != revision) return changed(update);
            try {
                entries.delete(owner, id, revision);
                return removed(update, false);
            } catch (EntryStatusConflictException | EntryVersionConflictException conflict) {
                // Another request may have deleted the entry after our read.
                Entry actual = entries.requireEntry(owner, id);
                return quickCheckin(actual) && actual.status() == EntryStatus.DELETED
                        ? removed(update, true) : changed(update);
            }
        } catch (EntryNotFoundException missing) {
            return unavailable(update);
        }
    }

    private static boolean quickCheckin(Entry entry) {
        return entry.type() == EntryType.CHECKIN && entry.sourceKind() == SourceKind.QUICK_CHECKIN;
    }

    private static List<BotAction> removed(BotUpdate update, boolean repeated) {
        return List.of(new BotAction.AnswerCallback(update.callbackId(),
                        repeated ? "Отметка уже отменена" : "Отметка отменена"),
                new BotAction.SendInlineMessage(update.chatId(),
                        "Отметка отменена и больше не учитывается в дневнике и аналитике.", List.of()));
    }

    private static List<BotAction> changed(BotUpdate update) {
        return List.of(new BotAction.AnswerCallback(update.callbackId(), "Отметка уже изменена"),
                new BotAction.SendInlineMessage(update.chatId(),
                        "Отметка изменилась. Проверьте актуальную запись в дневнике; старая кнопка её не отменяет.", List.of()));
    }

    private static List<BotAction> unavailable(BotUpdate update) {
        return List.of(new BotAction.AnswerCallback(update.callbackId(), "Отметка недоступна"));
    }
}
