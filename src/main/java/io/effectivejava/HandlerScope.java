package io.effectivejava;

import io.proxxy.Proxxy;

import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.function.Supplier;
import java.util.function.ToIntBiFunction;

/**
 * Algebraic effect handlers for Java.
 *
 * <p>Bind effect handlers to a dynamic scope so they are discoverable from anywhere in the
 * call stack via {@link #find}, without threading explicit parameters through every layer.
 * Each handler runs as a partitioned virtual-thread actor backed by {@link Proxxy}; the scope
 * tears everything down automatically when the body exits.
 *
 * <p><strong>Fail-loud contract:</strong> {@link #find} throws {@link IllegalStateException}
 * if no handler is bound for the given effect type. A missing handler is always a programming
 * error — this library never silences it.
 *
 * <pre>{@code
 * interface Logger {
 *     void log(String userId, String message);
 * }
 *
 * HandlerScope.builder()
 *     .bind(Logger.class, MyLogger::new)
 *     .run(() -> {
 *         Logger log = HandlerScope.find(Logger.class);
 *         log.log("alice", "application started");
 *     });
 * }</pre>
 *
 * @see HandlerScope#builder()
 * @see HandlerScope#find(Class)
 */
public final class HandlerScope {

    private HandlerScope() {}

    private static final ScopedValue<Map<Class<?>, Object>> SCOPE = ScopedValue.newInstance();

    /** Routes by the first argument's hash code — the standard affinity-key pattern. */
    public static final ToIntBiFunction<Method, Object[]> BY_FIRST_ARG =
            (method, args) -> args.length == 0 || args[0] == null ? 0 : args[0].hashCode();

    // ── Public API ────────────────────────────────────────────────────────────

    /**
     * Returns the proxy bound to {@code effectType} in the current scope.
     *
     * <p>The returned object is a {@link Proxxy}-backed proxy: calling any of its methods
     * dispatches to the handler's partition thread determined by the router supplied to
     * {@link Builder#bind}. Void methods are fire-and-forget; non-void methods block until
     * the result is returned.
     *
     * @param <T>        the effect interface type
     * @param effectType the interface class to look up
     * @return the proxy for {@code effectType}
     * @throws IllegalStateException if called outside a {@link Builder#run} body, or if
     *                               {@code effectType} was not registered with {@link Builder#bind}
     */
    @SuppressWarnings("unchecked")
    public static <T> T find(Class<T> effectType) {
        Objects.requireNonNull(effectType);
        if (!SCOPE.isBound()) {
            throw new IllegalStateException(
                "No HandlerScope is active. Call find() inside a HandlerScope.builder().run() body.");
        }
        T proxy = (T) SCOPE.get().get(effectType);
        if (proxy == null) {
            throw new IllegalStateException(
                "No handler bound for " + effectType.getName() + ". " +
                "Ensure bind(" + effectType.getSimpleName() + ".class, ...) was called on the builder.");
        }
        return proxy;
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
     * A {@link Runnable} variant that is permitted to throw checked exceptions.
     *
     * <p>Passed to {@link Builder#run} as the body to execute inside the handler scope.
     */
    @FunctionalInterface
    public interface ThrowingRunnable {
        void run() throws Exception;
    }

    // ── Builder ───────────────────────────────────────────────────────────────

    /**
     * Fluent builder for constructing a handler scope.
     *
     * <p>Register one or more effect handlers with {@link #bind}, then call {@link #run} to
     * execute a body with those handlers discoverable via {@link HandlerScope#find}.
     */
    public static final class Builder {

        private Builder() {}

        private final List<Entry<?>> entries = new ArrayList<>();

        /**
         * Registers a handler using {@link HandlerScope#BY_FIRST_ARG} routing,
         * one partition, and a buffer of 1024.
         *
         * @param <T>        the effect interface type
         * @param effectType the interface to proxy
         * @param factory    called once per partition to produce a target instance
         * @return this builder, for chaining
         */
        public <T> Builder bind(Class<T> effectType, Supplier<T> factory) {
            return bind(effectType, factory, BY_FIRST_ARG, 2, 1024);
        }

        /**
         * Registers a handler with an explicit router, one partition, and a buffer of 1024.
         *
         * @param <T>        the effect interface type
         * @param effectType the interface to proxy
         * @param factory    called once per partition to produce a target instance
         * @param router     maps (method, args) to a routing hash; result is taken mod partitionCount
         * @return this builder, for chaining
         */
        public <T> Builder bind(Class<T> effectType, Supplier<T> factory,
                                ToIntBiFunction<Method, Object[]> router) {
            return bind(effectType, factory, router, 2, 1024);
        }

        /**
         * Registers a handler with explicit router and partitioning.
         *
         * @param <T>            the effect interface type
         * @param effectType     the interface to proxy
         * @param factory        called once per partition to produce a target instance
         * @param router         maps (method, args) to a routing hash; result is taken mod partitionCount
         * @param partitionCount number of independent partitions (threads + target instances)
         * @param bufferSize     capacity of each partition's event queue
         * @return this builder, for chaining
         */
        public <T> Builder bind(Class<T> effectType, Supplier<T> factory,
                                ToIntBiFunction<Method, Object[]> router,
                                int partitionCount, int bufferSize) {
            Objects.requireNonNull(effectType);
            Objects.requireNonNull(factory);
            Objects.requireNonNull(router);
            entries.add(new Entry<>(effectType, factory, router, partitionCount, bufferSize));
            return this;
        }

        /**
         * Starts all registered handlers, executes {@code body} with them discoverable via
         * {@link HandlerScope#find}, then tears down the scope.
         *
         * <p>On exit (normal or exceptional), each handler's daemon is closed. Pending non-void
         * calls complete before shutdown; queued void calls may be dropped.
         *
         * @param body the block to execute inside the scope
         * @throws Exception any exception thrown by {@code body}
         */
        public void run(ThrowingRunnable body) throws Exception {
            if (entries.isEmpty()) {
                body.run();
                return;
            }

            Map<Class<?>, Object> map = new HashMap<>();
            List<Proxxy.ProxyHandle<?>> handles = new ArrayList<>(entries.size());

            for (Entry<?> e : entries) {
                launch(e, handles, map);
            }

            ScopedValue.where(SCOPE, Map.copyOf(map)).call(() -> {
                try {
                    body.run();
                } finally {
                    for (Proxxy.ProxyHandle<?> h : handles) {
                        try { h.close(); }
                        catch (InterruptedException ex) { Thread.currentThread().interrupt(); }
                        catch (Exception ignored) {}
                    }
                }
                return null;
            });
        }

        private static <T> void launch(Entry<T> e, List<Proxxy.ProxyHandle<?>> handles, Map<Class<?>, Object> map) {
            var handle = Proxxy.start(e.effectType(), e.factory(), e.partitionCount(), e.bufferSize(), e.router());
            handles.add(handle);
            map.put(e.effectType(), handle.proxy());
        }
    }

    // ── Internals ─────────────────────────────────────────────────────────────

    private record Entry<T>(
            Class<T> effectType,
            Supplier<T> factory,
            ToIntBiFunction<Method, Object[]> router,
            int partitionCount,
            int bufferSize
    ) {}
}
