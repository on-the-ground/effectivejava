package io.effectivejava;

import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.atomic.AtomicBoolean;

import static org.junit.jupiter.api.Assertions.*;

class HandlerScopeTest {

    // ── Effect interfaces ─────────────────────────────────────────────────────

    interface Logger {
        void log(String key, String message);
    }

    interface Greeter {
        String greet(String key, String name);
    }

    interface IntSink {
        void accept(Integer key);
    }

    // ── Fire-and-forget ───────────────────────────────────────────────────────

    @Test
    void fire_and_forget_handler_receives_messages() throws Exception {
        List<String> received = new CopyOnWriteArrayList<>();
        CountDownLatch latch = new CountDownLatch(2);

        HandlerScope.builder()
                .bind(Logger.class, () -> (key, msg) -> { received.add(msg); latch.countDown(); })
                .run(() -> {
                    Logger log = HandlerScope.find(Logger.class);
                    log.log("alice", "hello");
                    log.log("alice", "world");
                    latch.await();
                });

        assertEquals(List.of("hello", "world"), received);
    }

    @Test
    void find_throws_when_no_scope_active() {
        assertThrows(IllegalStateException.class, () -> HandlerScope.find(Logger.class));
    }

    @Test
    void find_throws_when_effect_not_registered() throws Exception {
        HandlerScope.builder()
                .bind(Greeter.class, () -> (key, name) -> "Hi")
                .run(() -> assertThrows(IllegalStateException.class,
                        () -> HandlerScope.find(Logger.class)));
    }

    @Test
    void multiple_effect_types_are_each_discoverable() throws Exception {
        List<String> logs     = new CopyOnWriteArrayList<>();
        List<String> greetees = new CopyOnWriteArrayList<>();
        CountDownLatch latch = new CountDownLatch(2);

        HandlerScope.builder()
                .bind(Logger.class,  () -> (key, msg)  -> { logs.add(msg); latch.countDown(); })
                .bind(Greeter.class, () -> (key, name) -> { greetees.add(name); latch.countDown(); return name; })
                .run(() -> {
                    HandlerScope.find(Logger.class).log("x", "user login");
                    HandlerScope.find(Greeter.class).greet("x", "alice");
                    latch.await();
                });

        assertEquals(List.of("user login"), logs);
        assertEquals(List.of("alice"), greetees);
    }

    // ── Edge cases ────────────────────────────────────────────────────────────

    @Test
    void no_handlers_runs_body_directly() throws Exception {
        AtomicBoolean ran = new AtomicBoolean(false);
        HandlerScope.builder().run(() -> ran.set(true));
        assertTrue(ran.get());
    }

    @Test
    void scope_tears_down_even_when_body_throws() {
        List<String> received = new CopyOnWriteArrayList<>();
        CountDownLatch latch = new CountDownLatch(1);

        assertThrows(RuntimeException.class, () ->
                HandlerScope.builder()
                        .bind(Logger.class, () -> (key, msg) -> { received.add(msg); latch.countDown(); })
                        .run(() -> {
                            HandlerScope.find(Logger.class).log("x", "before-throw");
                            latch.await();
                            throw new RuntimeException("boom");
                        }));

        assertTrue(received.contains("before-throw"));
    }

    // ── Partition behaviour ───────────────────────────────────────────────────

    @Test
    void events_in_same_partition_are_ordered() throws Exception {
        List<Integer> received = new CopyOnWriteArrayList<>();
        CountDownLatch latch = new CountDownLatch(3);

        HandlerScope.builder()
                .bind(IntSink.class, () -> n -> { received.add(n); latch.countDown(); },
                        HandlerScope.BY_FIRST_ARG, 2, 1024)
                .run(() -> {
                    IntSink sink = HandlerScope.find(IntSink.class);
                    // Integer.hashCode() == value; 0, 2, 4 → partition 0 (even % 2)
                    sink.accept(0);
                    sink.accept(2);
                    sink.accept(4);
                    latch.await();
                });

        assertEquals(List.of(0, 2, 4), received);
    }

    @Test
    void blocking_event_in_one_partition_does_not_stall_other() throws Exception {
        CountDownLatch blocker   = new CountDownLatch(1);
        CountDownLatch otherDone = new CountDownLatch(1);
        List<Integer>  received  = new CopyOnWriteArrayList<>();

        HandlerScope.builder()
                .bind(IntSink.class, () -> n -> {
                    if (n == 0) {
                        try { blocker.await(); } catch (InterruptedException e) { Thread.currentThread().interrupt(); }
                    } else {
                        received.add(n);
                        otherDone.countDown();
                    }
                }, HandlerScope.BY_FIRST_ARG, 2, 1024)
                .run(() -> {
                    IntSink sink = HandlerScope.find(IntSink.class);
                    sink.accept(0);  // partition 0: blocks
                    sink.accept(1);  // partition 1: proceeds independently
                    otherDone.await();
                    blocker.countDown();
                });

        assertTrue(received.contains(1));
    }

    // ── Request-reply ─────────────────────────────────────────────────────────

    @Test
    void non_void_effect_blocks_and_returns_result() throws Exception {
        HandlerScope.builder()
                .bind(Greeter.class, () -> (key, name) -> "Hello, " + name + "!")
                .run(() -> {
                    String result = HandlerScope.find(Greeter.class).greet("bob", "World");
                    assertEquals("Hello, World!", result);
                });
    }
}
