package io.effectivejava;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.StructuredTaskScope;
import java.util.function.Consumer;

/**
 * Algebraic effect handlers for Java.
 *
 * <p>Bind effect handlers to a dynamic scope so they are discoverable from anywhere in the
 * call stack via {@link ScopedValue}, without threading explicit parameters through every layer.
 * Each handler runs as an independent virtual-thread actor with a message queue; the scope
 * tears them all down automatically when the body exits.
 *
 * <p><strong>Fail-loud contract:</strong> {@link #perform} throws {@link IllegalStateException}
 * if no handler is bound for the given key. A missing handler is always a programming error —
 * this library never silences it.
 *
 * <pre>{@code
 * static final ScopedValue<HandlerScope.Channel<String>> LOG = ScopedValue.newInstance();
 *
 * HandlerScope.builder()
 *     .bind(LOG, msg -> System.out.println("[LOG] " + msg))
 *     .run(() -> {
 *         HandlerScope.perform(LOG, "application started");
 *     });
 * }</pre>
 *
 * @see HandlerScope#builder()
 * @see HandlerScope#perform(ScopedValue, Object)
 */
public class HandlerScope {

    /** Not instantiable — all API is static. */
    private HandlerScope() {}

    // ── Public API ────────────────────────────────────────────────────────────

    /**
     * A message channel bound to an effect handler.
     *
     * <p>Instances are created internally by {@link Builder#bind} and exposed to callers
     * only via {@link ScopedValue}. Use {@link HandlerScope#perform} rather than calling
     * {@code send} directly.
     *
     * @param <T> the message type this channel accepts
     */
    @FunctionalInterface
    public interface Channel<T> {
        /**
         * Enqueues {@code message} for the bound handler.
         *
         * @param message the message to deliver
         * @throws IllegalStateException if the handler thread was interrupted and can no
         *         longer accept messages
         */
        void send(T message);
    }

    /**
     * A {@link Runnable} variant that is permitted to throw checked exceptions.
     *
     * <p>Passed to {@link Builder#run} as the body to execute inside the handler scope.
     */
    @FunctionalInterface
    public interface ThrowingRunnable {
        /**
         * Executes this block, potentially throwing a checked exception.
         *
         * @throws Exception any checked exception the block wishes to propagate
         */
        void run() throws Exception;
    }

    /**
     * Returns a new {@link Builder} for constructing a handler scope.
     *
     * @return a fresh builder
     */
    public static Builder builder() {
        return new Builder();
    }

    /**
     * Performs an effect by sending {@code msg} to the handler bound to {@code key}.
     *
     * <p>A missing handler is a programming error. If no handler is bound for {@code key}
     * in the current scope, this method throws {@link IllegalStateException} immediately.
     * Always call {@code perform} inside a {@link Builder#run} body that has bound this key.
     *
     * @param <T> the message type
     * @param key  the {@link ScopedValue} key identifying the effect
     * @param msg  the message to deliver
     * @throws IllegalStateException if no handler is bound for {@code key} in the current scope,
     *         or if the handler channel has been interrupted and can no longer accept messages
     */
    public static <T> void perform(ScopedValue<Channel<T>> key, T msg) {
        if (!key.isBound()) {
            throw new IllegalStateException(
                    "No handler bound for effect key " + key + ". " +
                    "Ensure perform() is called inside a HandlerScope.builder().run() body " +
                    "that has bound this key.");
        }
        key.get().send(msg);
    }

    // ── Builder ───────────────────────────────────────────────────────────────

    /**
     * Fluent builder for constructing a handler scope.
     *
     * <p>Register one or more handlers with {@link #bind}, then call {@link #run} to execute
     * a body with those handlers active.
     */
    public static final class Builder {

        /** Use {@link HandlerScope#builder()} to obtain an instance. */
        private Builder() {}

        private final List<Handler<?>> handlers = new ArrayList<>();

        /**
         * Registers a handler for the effect identified by {@code key}.
         *
         * <p>Messages sent via {@link HandlerScope#perform(ScopedValue, Object)} with this
         * {@code key} are delivered to {@code processor} on a dedicated virtual thread.
         *
         * @param <T>       the message type
         * @param key       the {@link ScopedValue} key identifying the effect
         * @param processor the consumer invoked for each message; called on the handler thread
         * @return this builder, for chaining
         */
        public <T> Builder bind(ScopedValue<Channel<T>> key, Consumer<T> processor) {
            handlers.add(new Handler<>(new LinkedBlockingQueue<>(), processor, key));
            return this;
        }

        /**
         * Starts all registered handlers as virtual threads, executes {@code body} with them
         * discoverable via {@link ScopedValue}, then tears down the scope.
         *
         * <p>On exit (normal or exceptional), handler threads are interrupted. Any messages
         * still in the queue are drained and delivered before the handler threads finish.
         * The method returns only after all handler threads have finished.
         *
         * @param body the block to execute inside the scope
         * @throws Exception any exception thrown by {@code body}
         */
        @SuppressWarnings({"rawtypes", "unchecked"})
        public void run(ThrowingRunnable body) throws Exception {
            if (handlers.isEmpty()) {
                body.run();
                return;
            }

            ScopedValue.Carrier carrier = null;

            for (var h : handlers) {
                ScopedValue key = h.key();
                Channel ch = h.channel();
                carrier = (carrier == null)
                        ? ScopedValue.where(key, ch)
                        : carrier.where(key, ch);
            }

            // StructuredTaskScope must be nested inside carrier.call() so that
            // forked threads do not outlive the ScopedValue bindings they inherit.
            carrier.call(() -> {
                var started = new CountDownLatch(handlers.size());
                var threads = new CopyOnWriteArrayList<Thread>();

                try (var scope = StructuredTaskScope.open(StructuredTaskScope.Joiner.awaitAll())) {
                    for (var h : handlers) h.forkOn(scope, threads, started);
                    started.await();  // ensure all handlers are blocking before body runs
                    try {
                        body.run();
                    } finally {
                        threads.forEach(Thread::interrupt);  // out-of-band stop
                        try { scope.join(); } catch (InterruptedException ie) {
                            Thread.currentThread().interrupt();
                        }
                    }
                }
                return null;
            });
        }
    }

    // ── Internals ─────────────────────────────────────────────────────────────

    private record Handler<T>(
            LinkedBlockingQueue<T> queue,
            Consumer<T> interpret,
            ScopedValue<Channel<T>> key
    ) {
        Channel<T> channel() {
            return msg -> {
                try {
                    queue.put(msg);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    throw new IllegalStateException(
                            "Handler channel interrupted; effect could not be delivered: " + msg);
                }
            };
        }

        @SuppressWarnings("rawtypes")
        void forkOn(StructuredTaskScope scope, List<Thread> threads, CountDownLatch started) {
            scope.fork(() -> {
                threads.add(Thread.currentThread());
                started.countDown();
                try {
                    while (true) {
                        interpret.accept(queue.take());
                    }
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    List<T> pending = new ArrayList<>();
                    queue.drainTo(pending);
                    pending.forEach(interpret::accept);
                    return null;
                }
            });
        }
    }
}
