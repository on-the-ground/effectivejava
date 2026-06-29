# Effect-ive Java

Algebraic Effect Handlers for Java — bind effect handlers to a dynamic scope so they are discoverable from anywhere in the call stack, without threading explicit parameters through every layer.

## Motivation

Algebraic Effect Handlers let you separate *what* an effect does from *where* the effect is handled. Code deep in a call stack can `perform` a logging effect, a metrics effect, or a resumable req-reply effect without knowing who handles it. The caller decides, at the boundary, what each effect means.

This library implements that model using Java's `ScopedValue` (ambient context propagation) and [`daemonizer`](https://github.com/joohyung-park/daemonizer) (virtual-thread lifetime management). Each handler runs as a 2-partition virtual-thread actor: events are distributed across partitions by their `hashCode`, so a single blocking event only stalls its own partition while the other continues processing.

## Requirements

- Java 25 (no `--enable-preview` flag required — `ScopedValue` is a standard API from Java 25)

## Installation

```kotlin
// build.gradle.kts
dependencies {
    implementation("io.github.joohyung-park:effectivejava:0.2.2")
}
```

## Core concepts

| Term | Meaning |
|---|---|
| `perform(key, msg)` | Invoke an effect — enqueue `msg` to the handler bound to `key`. Throws `IllegalStateException` if no handler is bound. |
| `bind(key, processor)` | Register a handler for `key` inside the current scope. |
| `run(body)` | Start all handlers as virtual threads, execute `body` with them discoverable via `ScopedValue`, then tear down. |
| `Reply<R>` | A one-shot reply channel: `send(result)` on the handler side, `await()` on the performer side. |

## Usage

### Fire-and-forget effect

```java
// Declare an effect key — one per effect type, typically a static field
static final ScopedValue<HandlerScope.Channel<String>> LOG = ScopedValue.newInstance();

HandlerScope.builder()
    .bind(LOG, msg -> System.out.println("[LOG] " + msg))
    .run(() -> {
        // anywhere inside this body (including nested calls) can perform the effect
        HandlerScope.perform(LOG, "application started");
        HandlerScope.perform(LOG, "processing req");
    });
```

### Multiple effect types

```java
static final ScopedValue<HandlerScope.Channel<String>> LOG    = ScopedValue.newInstance();
static final ScopedValue<HandlerScope.Channel<String>> METRIC = ScopedValue.newInstance();

HandlerScope.builder()
    .bind(LOG,    msg -> System.out.println("[LOG] "    + msg))
    .bind(METRIC, msg -> System.out.println("[METRIC] " + msg))
    .run(() -> {
        HandlerScope.perform(LOG,    "user login");
        HandlerScope.perform(METRIC, "latency=42ms");
    });
```

### Request-reply effect

The performer blocks on `await()` until the handler calls `send()`.

```java
record EchoRequest(String text, Reply<String> reply) {}

static final ScopedValue<HandlerScope.Channel<EchoRequest>> ECHO = ScopedValue.newInstance();

HandlerScope.builder()
    .bind(ECHO, req -> req.reply().send(req.text().toUpperCase()))
    .run(() -> {
        var reply = new Reply<String>();
        HandlerScope.perform(ECHO, new EchoRequest("hello", reply));
        String result = reply.await();  // blocks until handler calls send()
        System.out.println(result); // "HELLO"
    });
```

Calling `reply.cancel()` instead of waiting unblocks `await()` with a `CancellationException`.

### Fail-loud contract

`perform` throws `IllegalStateException` when no handler is bound. A missing handler is always a programming error — never silence it:

```java
// ✗ throws IllegalStateException — must be called inside a bound scope
HandlerScope.perform(LOG, "no handler here");

// ✓ correct: perform only inside run()
HandlerScope.builder()
    .bind(LOG, msg -> System.out.println("[LOG] " + msg))
    .run(() -> {
        HandlerScope.perform(LOG, "safe here");
    });
```

## Lifecycle

```
HandlerScope.builder()
    .bind(...)
    .run(body)           ← each handler starts as a 2-partition PartitionedDaemon (2 virtual threads)
        body executes    ← effects are discoverable via ScopedValue
                         ← events routed to partitions by hashCode; one blocking event
                            only stalls its partition, the other partition runs freely
    ← body exits         ← all partition daemons are closed
                         ← queued messages are drained and delivered
                         ← run() returns only after all handlers finish
```

## License

MIT — see [LICENSE](LICENSE).
