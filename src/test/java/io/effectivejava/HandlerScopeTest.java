package io.effectivejava;

import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.CancellationException;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.atomic.AtomicBoolean;

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
    void perform_throws_when_no_handler_bound() {
        assertThrows(IllegalStateException.class, () -> HandlerScope.perform(LOG, "unbound"));
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

    // ── Edge cases ────────────────────────────────────────────────────────────

    @Test
    void no_handlers_runs_body_directly() throws Exception {
        AtomicBoolean ran = new AtomicBoolean(false);
        HandlerScope.builder().run(() -> ran.set(true));
        assertTrue(ran.get());
    }

    @Test
    void events_drained_even_when_body_throws() {
        List<String> received = new CopyOnWriteArrayList<>();

        assertThrows(RuntimeException.class, () ->
                HandlerScope.builder()
                        .bind(LOG, received::add)
                        .run(() -> {
                            HandlerScope.perform(LOG, "before-throw");
                            throw new RuntimeException("boom");
                        }));

        assertTrue(received.contains("before-throw"));
    }

    // ── Partition behaviour ───────────────────────────────────────────────────

    @Test
    void events_in_same_partition_are_ordered() throws Exception {
        ScopedValue<HandlerScope.Channel<Integer>> NUMS = ScopedValue.newInstance();
        List<Integer> received = new CopyOnWriteArrayList<>();
        CountDownLatch latch = new CountDownLatch(3);

        HandlerScope.builder()
                .bind(NUMS, n -> { received.add(n); latch.countDown(); })
                .run(() -> {
                    // Integer hashCode == value itself: 0, 2, 4 all → partition 0
                    HandlerScope.perform(NUMS, 0);
                    HandlerScope.perform(NUMS, 2);
                    HandlerScope.perform(NUMS, 4);
                    latch.await();
                });

        assertEquals(List.of(0, 2, 4), received);
    }

    @Test
    void blocking_event_in_one_partition_does_not_stall_other_partition() throws Exception {
        ScopedValue<HandlerScope.Channel<Integer>> NUMS = ScopedValue.newInstance();
        CountDownLatch blocker   = new CountDownLatch(1);
        CountDownLatch otherDone = new CountDownLatch(1);
        List<Integer>  received  = new CopyOnWriteArrayList<>();

        HandlerScope.builder()
                .bind(NUMS, n -> {
                    if (n == 0) {  // partition 0: 의도적으로 블로킹
                        try { blocker.await(); } catch (InterruptedException e) { Thread.currentThread().interrupt(); }
                    } else {       // partition 1: 블로킹 없이 처리
                        received.add(n);
                        otherDone.countDown();
                    }
                })
                .run(() -> {
                    HandlerScope.perform(NUMS, 0);  // partition 0 블로킹 시작
                    HandlerScope.perform(NUMS, 1);  // partition 1, 독립적으로 진행
                    otherDone.await();              // partition 1 완료 확인
                    blocker.countDown();            // partition 0 해제
                });

        assertTrue(received.contains(1));
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
