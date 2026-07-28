package io.effectivejava;

import io.proxxy.Proxxy;

import java.lang.reflect.Method;
import java.util.*;
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
 * HandlerScope.open()
 *     .bind(Logger.class, MyLogger::new)
 *     .run(() -> {
 *         Logger log = HandlerScope.find(Logger.class);
 *         log.log("alice", "application started");
 *     });
 * }</pre>
 *
 * <p>The fail-loud contract only catches a missing handler at the moment {@link #find} actually
 * runs, which can be arbitrarily deep in a call stack and easy to miss on inspection. {@link
 * Context @Context} lets a method declare which effect types it depends on next to its
 * signature, the way a {@code throws} clause declares a checked exception — it carries no
 * runtime behavior on its own, but this artifact also ships an Error Prone {@code BugChecker}
 * ({@link io.effectivejava.checker.RequireContextChecker}) that uses it to reject a build where a
 * {@link #find} call, or a call to another {@code @Context}-annotated method, isn't covered by
 * the caller's own {@code @Context} or an enclosing {@code bind(...).run(...)} block. The checker
 * only activates for a consumer who opts into it by applying the {@code net.ltgt.errorprone}
 * Gradle plugin to their own build — see the project README.
 *
 * @see HandlerScope#open()
 * @see HandlerScope#find(Class)
 * @see Context
 * @see io.effectivejava.checker.RequireContextChecker
 */
public final class HandlerScope {

    private HandlerScope() {}

    private static final ScopedValue<Map<Class<?>, Object>> SCOPE = ScopedValue.newInstance();

    /** Routes by the first argument's hash code — bitwise masked to ensure positivity. */
    public static final ToIntBiFunction<Method, Object[]> FIRST_ARGUMENT_HASH =
            (method, args) -> args.length == 0 || args[0] == null ? 0 : (args[0].hashCode() & Integer.MAX_VALUE);

    // ── Public API ────────────────────────────────────────────────────────────

    /**
     * Returns the proxy bound to {@code effectType} in the current scope.
     *
     * <p>The returned object is a {@link Proxxy}-backed proxy: calling any of its methods
     * dispatches to the handler's partition thread determined by the router supplied to
     * {@link Builder#bind}. Void methods are fire-and-forget; non-void methods block until
     * the result is returned.
     *
     * <p>Callers should declare {@code effectType} in an {@link Context @Context} annotation on
     * the enclosing method — see the class-level documentation.
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
                "No HandlerScope is active. Call find() inside a HandlerScope.open().run() body.");
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
    public static Builder open() {
        return new Builder();
    }

    /**
     * A {@link Runnable} variant that is permitted to throw checked exceptions.
     *
     * <p>Passed to {@link Builder#run} as the body to execute inside the handler scope.
     */
    @FunctionalInterface
    public interface ThrowingRunnable {
        /**
         * Runs the body.
         *
         * @throws Exception any exception the body needs to propagate
         */
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
         * Registers a handler using {@link HandlerScope#FIRST_ARGUMENT_HASH} routing,
         * two partitions, and a buffer of 1024.
         *
         * <p><strong>Note:</strong> {@code factory} runs before this scope's {@link
         * HandlerScope#find} binding is established, so a factory that itself calls {@link
         * HandlerScope#find} will always throw {@link IllegalStateException}.
         *
         * @param <T>        the effect interface type
         * @param effectType the interface to proxy
         * @param factory    called once per partition to produce a target instance
         * @return this builder, for chaining
         */
        public <T> Builder bind(Class<T> effectType, Supplier<T> factory) {
            return bind(effectType, factory, FIRST_ARGUMENT_HASH, 2, 1024);
        }

        /**
         * Registers a handler with an explicit router, two partitions, and a buffer of 1024.
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

            Map<Class<?>, Object> map = new IdentityHashMap<>();
            List<Proxxy.ProxyHandle<?>> handles = new ArrayList<>(entries.size());

            Throwable bodyException = null;
            try {
                for (Entry<?> e : entries) {
                    var handle = startProxxy(e);
                    handles.add(handle);
                    map.put(e.effectType(), handle.proxy());
                }

                ScopedValue.where(SCOPE, Collections.unmodifiableMap(map)).call(() -> {
                    body.run();
                    return null;
                });
            } catch (Throwable t) {
                bodyException = t;
            } finally {
                for (Proxxy.ProxyHandle<?> h : handles) {
                    try {
                        h.close();
                    } catch (InterruptedException ex) {
                        Thread.currentThread().interrupt();
                        if (bodyException != null) bodyException.addSuppressed(ex);
                        else bodyException = ex;
                    } catch (Exception ex) {
                        if (bodyException != null) bodyException.addSuppressed(ex);
                        else bodyException = ex;
                    }
                }
            }
            if (bodyException instanceof RuntimeException re) throw re;
            if (bodyException instanceof Error err) throw err;
            if (bodyException instanceof Exception ex) throw ex;
        }

        private static <T> Proxxy.ProxyHandle<T> startProxxy(Entry<T> entry) {
            return Proxxy.start(
                    entry.effectType(),
                    entry.factory(),
                    entry.partitionCount(),
                    entry.bufferSize(),
                    entry.router());
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
