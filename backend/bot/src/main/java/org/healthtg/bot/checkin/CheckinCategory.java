package org.healthtg.bot.checkin;

/** The four independent categories from contracts/schemas/common.json. */
public enum CheckinCategory {
    SLEEP("sleep_quality", "😴 Качество сна"),
    DIGESTION("digestion_comfort", "🍽 Комфорт пищеварения"),
    WELLBEING("wellbeing", "💚 Самочувствие"),
    MOOD("mood", "🙂 Настроение");

    private final String code;
    private final String label;

    CheckinCategory(String code, String label) {
        this.code = code;
        this.label = label;
    }

    public String code() { return code; }
    public String label() { return label; }
}
