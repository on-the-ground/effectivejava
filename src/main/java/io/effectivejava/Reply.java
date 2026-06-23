package io.effectivejava;

import java.util.concurrent.CancellationException;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;

/**
 * A one-shot reply channel for request-reply effects.
 *
 * <p>The performer creates a {@code Reply}, includes it in a request req, and blocks
 * on {@link #await()}. The handler calls {@link #send} to deliver the result and unblock
 * the performer, or {@link #cancel} to unblock it with a {@link CancellationException}.
 *
 * <pre>{@code
 * record EchoRequest(String text, Reply<String> reply) {}
 *
 * var reply = new Reply<String>();
 * HandlerScope.perform(ECHO, new EchoRequest("hello", reply));
 * String result = reply.await();  // blocks until handler calls send()
 * }</pre>
 *
 * @param <R> the result type
 */
public final class Reply<R> {

    private final CompletableFuture<R> future = new CompletableFuture<>();

    /**
     * Sends {@code result} to the performer, unblocking any thread waiting on {@link #await()}.
     *
     * @param result the result to deliver
     */
    public void send(R result) { future.complete(result); }

    /**
     * Cancels this reply channel, unblocking any thread waiting on {@link #await()} with a
     * {@link CancellationException}.
     */
    public void cancel() { future.cancel(false); }

    /**
     * Blocks until the handler calls {@link #send} or {@link #cancel}.
     *
     * @return the result passed to {@link #send}
     * @throws CancellationException if {@link #cancel} was called
     * @throws Exception if the handler completed with an exceptional result
     */
    public R await() throws Exception {
        try {
            return future.get();
        } catch (ExecutionException e) {
            Throwable cause = e.getCause();
            if (cause instanceof Error err) throw err;
            throw (Exception) cause;
        }
    }
}
