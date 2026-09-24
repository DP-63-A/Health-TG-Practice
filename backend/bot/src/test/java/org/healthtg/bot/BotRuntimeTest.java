package org.healthtg.bot;

import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.context.annotation.AnnotationConfigApplicationContext;
import org.telegram.telegrambots.meta.api.methods.GetMe;
import org.telegram.telegrambots.meta.api.methods.commands.SetMyCommands;
import org.telegram.telegrambots.meta.api.methods.updates.GetUpdates;
import org.telegram.telegrambots.meta.api.methods.updates.GetWebhookInfo;
import org.telegram.telegrambots.meta.api.methods.send.SendMessage;
import org.telegram.telegrambots.meta.api.objects.User;
import org.telegram.telegrambots.meta.api.objects.ApiResponse;
import org.telegram.telegrambots.meta.api.objects.WebhookInfo;
import org.telegram.telegrambots.meta.exceptions.TelegramApiException;
import org.telegram.telegrambots.meta.exceptions.TelegramApiRequestException;
import org.telegram.telegrambots.meta.generics.TelegramClient;
import org.mockito.ArgumentCaptor;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class BotRuntimeTest {
    private final TelegramClient client = mock(TelegramClient.class);
    private final AtomicInteger closed = new AtomicInteger();
    private final CountDownLatch transportClosed = new CountDownLatch(1);
    private BotRuntime runtime() {
        return new BotRuntime(client, RuntimeSettings.from(RuntimeSettingsTest.environment()), () -> {
            closed.incrementAndGet(); transportClosed.countDown();
        });
    }
    private void successfulHandshake(String webhookUrl) throws Exception {
        var info = new WebhookInfo(); info.setUrl(webhookUrl);
        when(client.execute(any(GetWebhookInfo.class))).thenReturn(info);
        var me = new User(9999L, "Synthetic bot", true); me.setUserName("test_bot");
        when(client.execute(any(GetMe.class))).thenReturn(me);
        when(client.execute(any(SetMyCommands.class))).thenReturn(true);
    }

    @Test void activeWebhookIsNotDeletedAndStartupClosesResources() throws Exception {
        successfulHandshake("https://example.com/secret-webhook");
        var runtime = runtime();
        var error = assertThrows(IllegalStateException.class, runtime::start);
        assertTrue(error.getMessage().contains("webhook"));
        assertFalse(error.toString().contains("secret-webhook")); assertNull(error.getCause());
        verify(client).execute(any(GetWebhookInfo.class)); verifyNoMoreInteractions(client);
        assertFalse(runtime.isRunning()); assertEquals(1, closed.get()); runtime.close(); assertEquals(1, closed.get());
    }

    @Test void startupExceptionCannotExposeTokenInMessageOrCause() throws Exception {
        when(client.execute(any(GetWebhookInfo.class))).thenThrow(new TelegramApiException(RuntimeSettingsTest.SYNTHETIC_TOKEN));
        var runtime = runtime();
        var error = assertThrows(IllegalStateException.class, runtime::start);
        assertFalse(error.toString().contains(RuntimeSettingsTest.SYNTHETIC_TOKEN)); assertNull(error.getCause());
        assertEquals(1, closed.get()); assertFalse(runtime.isRunning());
    }

    @Test void springLifecycleRegistersCommandsPollsAndClosesTransportOnce() throws Exception {
        successfulHandshake("");
        var polling = new CountDownLatch(1);
        var cancelled = new CountDownLatch(1);
        when(client.execute(any(GetUpdates.class))).thenAnswer(invocation -> {
            polling.countDown();
            try { new CountDownLatch(1).await(); }
            catch (InterruptedException expected) { Thread.currentThread().interrupt(); cancelled.countDown(); }
            return List.of();
        });
        var runtime = runtime();
        try (var context = new AnnotationConfigApplicationContext()) {
            context.registerBean(BotRuntime.class, () -> runtime);
            context.refresh();
            assertTrue(polling.await(5, TimeUnit.SECONDS)); assertTrue(runtime.isRunning());
            runtime.start();
            var commands = ArgumentCaptor.forClass(SetMyCommands.class);
            verify(client).execute(commands.capture());
            assertEquals(List.of("start", "state"), commands.getValue().getCommands().stream().map(c -> c.getCommand()).toList());
            var requests = ArgumentCaptor.forClass(GetUpdates.class);
            verify(client).execute(requests.capture());
            assertEquals(0, requests.getValue().getOffset());
            assertTrue(requests.getValue().getTimeout() > 0);
            assertEquals(List.of("message"), requests.getValue().getAllowedUpdates());
        }
        assertTrue(cancelled.await(5, TimeUnit.SECONDS));
        assertFalse(runtime.isRunning()); assertEquals(1, closed.get()); runtime.close(); assertEquals(1, closed.get());
    }

    @Test void pollingProcessesSdkMessageAndAcknowledgesOnlyAfterDelivery() throws Exception {
        successfulHandshake("");
        var secondPoll = new CountDownLatch(1);
        AtomicInteger requested = new AtomicInteger();
        when(client.execute(any(GetUpdates.class))).thenAnswer(invocation -> {
            GetUpdates request = invocation.getArgument(0);
            if (requested.getAndIncrement() == 0) return List.of(TelegramAdapterTest.message("/state"));
            assertEquals(11, request.getOffset());
            secondPoll.countDown();
            try { new CountDownLatch(1).await(); }
            catch (InterruptedException expected) { Thread.currentThread().interrupt(); }
            return List.of();
        });
        try (var runtime = runtime()) {
            runtime.start(); assertTrue(secondPoll.await(5, TimeUnit.SECONDS));
            var messages = ArgumentCaptor.forClass(SendMessage.class);
            verify(client).execute(messages.capture());
            assertTrue(messages.getValue().getText().contains("Данные не сохранены"));
        }
        assertEquals(1, closed.get());
    }

    private static TelegramApiRequestException apiError(int code) {
        return new TelegramApiRequestException("Synthetic API error",
                new ApiResponse<Object>(false, code, "Synthetic failure", null, null));
    }

    private static void waitUntilInterrupted() {
        try { new CountDownLatch(1).await(); }
        catch (InterruptedException expected) { Thread.currentThread().interrupt(); }
    }

    @ParameterizedTest @ValueSource(ints = {401, 409})
    void fatalPollingErrorStopsAndClosesExactlyOnce(int code) throws Exception {
        successfulHandshake("");
        var error = apiError(code); assertEquals(code, error.getErrorCode());
        when(client.execute(any(GetUpdates.class))).thenThrow(error);
        try (var runtime = runtime()) {
            runtime.start();
            assertTrue(transportClosed.await(5, TimeUnit.SECONDS));
            assertFalse(runtime.isRunning()); assertEquals(1, closed.get());
            verify(client).execute(any(GetUpdates.class));
            verify(client, never()).execute(any(SendMessage.class));
        }
        assertEquals(1, closed.get());
    }

    @Test void temporaryPollingFailureRetriesAndDeliversNextMessage() throws Exception {
        successfulHandshake("");
        var requested = new AtomicInteger(); var delivered = new CountDownLatch(1);
        var offsets = new CopyOnWriteArrayList<Integer>();
        when(client.execute(any(GetUpdates.class))).thenAnswer(invocation -> {
            GetUpdates request = invocation.getArgument(0); offsets.add(request.getOffset());
            int attempt = requested.getAndIncrement();
            if (attempt == 0) throw new TelegramApiException("Synthetic unavailable");
            if (attempt == 1) return List.of(TelegramAdapterTest.message("/state"));
            waitUntilInterrupted(); return List.of();
        });
        when(client.execute(any(SendMessage.class))).thenAnswer(invocation -> { delivered.countDown(); return null; });
        try (var runtime = runtime()) {
            runtime.start(); assertTrue(delivered.await(8, TimeUnit.SECONDS));
            assertTrue(runtime.isRunning());
            assertEquals(List.of(0, 0), offsets.subList(0, 2));
            verify(client).execute(any(SendMessage.class));
        }
        assertEquals(1, closed.get());
    }

    @Test void failedDeliveryIsRetriedWithoutAcknowledgementThenSuccessfulDeliveryAdvancesOffset() throws Exception {
        successfulHandshake("");
        var requested = new AtomicInteger(); var acknowledged = new CountDownLatch(1);
        var offsets = new CopyOnWriteArrayList<Integer>();
        when(client.execute(any(GetUpdates.class))).thenAnswer(invocation -> {
            GetUpdates request = invocation.getArgument(0); offsets.add(request.getOffset());
            if (requested.getAndIncrement() < 2) return List.of(TelegramAdapterTest.message("/state"));
            acknowledged.countDown(); waitUntilInterrupted(); return List.of();
        });
        when(client.execute(any(SendMessage.class)))
                .thenThrow(new TelegramApiException("Synthetic send failure")).thenReturn(null);
        try (var runtime = runtime()) {
            runtime.start(); assertTrue(acknowledged.await(10, TimeUnit.SECONDS));
            assertEquals(List.of(0, 0, 11), offsets);
            verify(client, times(2)).execute(any(SendMessage.class));
            assertTrue(runtime.isRunning());
        }
        assertEquals(1, closed.get());
    }

    @Test void threeFailedDeliveriesStopWithoutAcknowledgingUndeliveredMessage() throws Exception {
        successfulHandshake("");
        var offsets = new CopyOnWriteArrayList<Integer>();
        when(client.execute(any(GetUpdates.class))).thenAnswer(invocation -> {
            GetUpdates request = invocation.getArgument(0); offsets.add(request.getOffset());
            return List.of(TelegramAdapterTest.message("/state"));
        });
        when(client.execute(any(SendMessage.class))).thenThrow(new TelegramApiException("Synthetic send failure"));
        try (var runtime = runtime()) {
            runtime.start(); assertTrue(transportClosed.await(15, TimeUnit.SECONDS));
            assertFalse(runtime.isRunning());
            assertEquals(List.of(0, 0, 0), offsets);
            verify(client, times(3)).execute(any(SendMessage.class));
            assertEquals(1, closed.get());
        }
        assertEquals(1, closed.get());
    }
}
