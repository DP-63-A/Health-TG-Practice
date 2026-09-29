package org.healthtg.bot;

import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.SmartLifecycle;
import org.telegram.telegrambots.meta.api.methods.GetMe;
import org.telegram.telegrambots.meta.api.methods.commands.SetMyCommands;
import org.telegram.telegrambots.meta.api.methods.updates.GetUpdates;
import org.telegram.telegrambots.meta.api.methods.updates.GetWebhookInfo;
import org.telegram.telegrambots.meta.api.objects.commands.BotCommand;
import org.telegram.telegrambots.meta.api.objects.commands.scope.BotCommandScopeAllPrivateChats;
import org.telegram.telegrambots.meta.generics.TelegramClient;
import org.telegram.telegrambots.meta.exceptions.TelegramApiException;
import org.telegram.telegrambots.meta.exceptions.TelegramApiRequestException;

/** One polling worker. No webhooks are changed and pending updates are never purged. */
public final class BotRuntime implements SmartLifecycle, AutoCloseable {
    private static final Logger LOG = LoggerFactory.getLogger(BotRuntime.class);
    private final TelegramClient client;
    private final RuntimeSettings settings;
    private final Runnable closeTransport;
    private final BotFlow flow;
    private final ExecutorService executor = Executors.newSingleThreadExecutor(r -> new Thread(r, "telegram-polling"));
    private volatile boolean running;
    private boolean closed;
    private Future<?> worker;

    public BotRuntime(TelegramClient client, RuntimeSettings settings, Runnable closeTransport) {
        this(client, settings, closeTransport, BotFlow.unavailable());
    }

    public BotRuntime(TelegramClient client, RuntimeSettings settings, Runnable closeTransport, BotFlow flow) {
        this.client = client;
        this.settings = settings;
        this.closeTransport = closeTransport;
        this.flow = flow;
    }

    @Override public synchronized void start() {
        if (running) return;
        if (closed) throw new IllegalStateException("Бот уже остановлен; запустите приложение заново.");
        try {
            var webhook = client.execute(new GetWebhookInfo());
            if (webhook == null || webhook.getUrl() == null) throw new TelegramApiException("Missing webhook result");
            if (!webhook.getUrl().isBlank()) {
                throw new IllegalStateException("У бота включён webhook. Используйте отдельного тестового бота или отключите webhook вручную.");
            }
            var me = client.execute(new GetMe());
            if (me == null || !Boolean.TRUE.equals(me.getIsBot())) throw new TelegramApiException("Missing bot identity");
            BotHandler handler = new BotHandler(settings.forUsername(me.getUserName()), QuickCheckin.unavailable(), flow);
            client.execute(SetMyCommands.builder().scope(new BotCommandScopeAllPrivateChats())
                    .commands(List.of(new BotCommand("start", "Открыть учебный дневник"),
                            new BotCommand("state", "Отметить состояние"))).build());
            running = true;
            worker = executor.submit(() -> poll(new TelegramAdapter(client, handler)));
            LOG.info("Бот запущен: long polling, закрытый доступ. Реальные данные о здоровье не отправляйте.");
        } catch (TelegramApiException e) {
            close();
            throw new IllegalStateException("Не удалось подключить бота. Проверьте токен и доступ к Telegram; подробности ответа скрыты.");
        } catch (RuntimeException e) {
            close();
            // Only our fixed webhook error is useful; SDK/config values must not reach logs.
            if (e instanceof IllegalStateException && e.getMessage() != null && e.getMessage().startsWith("У бота включён webhook")) throw e;
            throw new IllegalStateException("Не удалось настроить бота. Проверьте локальные настройки.");
        }
    }

    private void poll(TelegramAdapter adapter) {
        int offset = 0;
        int networkFailures = 0;
        int deliveryFailures = 0;
        try {
            while (running && !Thread.currentThread().isInterrupted()) {
                List<org.telegram.telegrambots.meta.api.objects.Update> updates;
                try {
                    updates = client.execute(GetUpdates.builder().offset(offset).timeout(30).limit(100)
                            .allowedUpdates(List.of("message", "callback_query")).build());
                    networkFailures = 0;
                } catch (TelegramApiException e) {
                    if (fatal(e)) { LOG.error("Telegram отклонил polling. Проверьте токен и что бот запущен только в одном месте."); break; }
                    LOG.warn("Telegram временно недоступен; повторяем запрос без вывода персональных данных.");
                    if (!pause(Math.min(30_000L, 1_000L << Math.min(networkFailures++, 5)))) break;
                    continue;
                }
                if (updates == null) { if (!pause(1000)) break; continue; }
                boolean failed = false;
                for (var update : updates) {
                    if (!running || Thread.currentThread().isInterrupted()) break;
                    if (update == null || update.getUpdateId() == null || update.getUpdateId() < offset) continue;
                    try {
                        adapter.accept(update);
                        offset = update.getUpdateId() + 1;
                        deliveryFailures = 0;
                    } catch (TelegramApiException e) {
                        failed = true;
                        if (fatal(e) || ++deliveryFailures >= 3) {
                            LOG.error("Ответ бота не доставлен. Бот остановлен; последнее сообщение не подтверждено.");
                            return;
                        }
                        LOG.warn("Не удалось отправить ответ; повторим обработку. Уже отправленная часть ответа может повториться.");
                        if (!pause(2000)) return;
                        break;
                    }
                }
                // Empty immediate responses must not produce a busy loop (e.g. during a proxy fault).
                if (!failed && updates.isEmpty() && !pause(100)) break;
            }
        } catch (RuntimeException e) {
            LOG.error("Обработка Telegram остановлена из-за внутренней ошибки. Содержимое сообщений и исключения скрыты.");
        } finally {
            close();
        }
    }

    private static boolean fatal(TelegramApiException e) {
        return e instanceof TelegramApiRequestException request
                && (Integer.valueOf(401).equals(request.getErrorCode()) || Integer.valueOf(409).equals(request.getErrorCode()));
    }
    private static boolean pause(long millis) {
        try { Thread.sleep(millis); return true; }
        catch (InterruptedException e) { Thread.currentThread().interrupt(); return false; }
    }
    @Override public boolean isRunning() { return running; }
    @Override public void stop() { close(); }
    @Override public synchronized void close() {
        if (closed) return;
        closed = true;
        running = false;
        if (worker != null) worker.cancel(true);
        executor.shutdownNow();
        closeTransport.run();
    }
}
