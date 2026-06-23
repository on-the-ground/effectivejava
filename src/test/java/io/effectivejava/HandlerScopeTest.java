package io.effectivejava;

import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.CancellationException;

import static org.junit.jupiter.api.Assertions.*;

class HandlerScopeTest {

    static final ScopedValue<HandlerScope.Channel<String>> LOG = ScopedValue.newInstance();

    // ── Fire-and-forget ───────────────────────────────────────────────────────

    @Test
    void fire_and_forget_handler_receives_messages() throws Exception {
        List<String> received = Collections.synchronizedList(new ArrayList<>());

        HandlerScope.builder()
                .bind(LOG, received::add)
                .run(() -> {
                    HandlerScope.perform(LOG, "hello");
                    HandlerScope.perform(LOG, "world");
                });

        assertTrue(received.contains("hello"));
        assertTrue(received.contains("world"));
    }

    @Test
    void perform_returns_false_when_no_handler_bound() {
        boolean sent = HandlerScope.perform(LOG, "unbound");
        assertFalse(sent);
    }

    @Test
    void multiple_handler_types_are_each_discoverable() throws Exception {
        ScopedValue<HandlerScope.Channel<String>> metric = ScopedValue.newInstance();

        List<String> logs    = Collections.synchronizedList(new ArrayList<>());
        List<String> metrics = Collections.synchronizedList(new ArrayList<>());

        HandlerScope.builder()
                .bind(LOG,    logs::add)
                .bind(metric, metrics::add)
                .run(() -> {
                    HandlerScope.perform(LOG,    "user login");
                    HandlerScope.perform(metric, "latency=42ms");
                });

        assertEquals(List.of("user login"),   logs);
        assertEquals(List.of("latency=42ms"), metrics);
    }

    // ── Request-reply ─────────────────────────────────────────────────────────

    record EchoRequest(String text, Reply<String> reply) {}

    static final ScopedValue<HandlerScope.Channel<EchoRequest>> ECHO = ScopedValue.newInstance();

    @Test
    void reply_handler_returns_result_via_send() throws Exception {
        HandlerScope.builder()
                .bind(ECHO, req -> req.reply().send(req.text().toUpperCase()))
                .run(() -> {
                    var reply = new Reply<String>();
                    HandlerScope.perform(ECHO, new EchoRequest("hello", reply));
                    assertEquals("HELLO", reply.await());
                });
    }

    @Test
    void reply_cancel_unblocks_await_with_exception() throws Exception {
        HandlerScope.builder()
                .bind(ECHO, req -> { /* never sends */ })
                .run(() -> {
                    var reply = new Reply<String>();
                    HandlerScope.perform(ECHO, new EchoRequest("ignored", reply));
                    reply.cancel();
                    assertThrows(CancellationException.class, reply::await);
                });
    }
}
