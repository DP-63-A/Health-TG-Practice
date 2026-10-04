package org.healthtg.bot;

import java.util.List;

public interface BotFlow {
    List<BotAction> beginCheckin(BotUpdate update);
    List<BotAction> handleMessage(BotUpdate update);
    List<BotAction> handleCallback(BotUpdate update);

    static BotFlow unavailable() {
        return new BotFlow() {
            @Override public List<BotAction> beginCheckin(BotUpdate update) { return List.of(); }
            @Override public List<BotAction> handleMessage(BotUpdate update) { return List.of(); }
            @Override public List<BotAction> handleCallback(BotUpdate update) { return List.of(); }
        };
    }
}
