# Effect-ive Java

Algebraic Effect Handlers for Java — bind effect handlers to a dynamic scope so they are discoverable from anywhere in the call stack, without threading explicit parameters through every layer.

## Motivation

Algebraic Effect Handlers let you separate *what* an effect does from *where* the effect is handled. Code deep in a call stack can `perform` a logging effect, a metrics effect, or a resumable req-reply effect without knowing who handles it. The caller decides, at the boundary, what each effect means.

This library implements that model using Java's `ScopedValue` (ambient context propagation) and `StructuredTaskScope` (virtual-thread lifetime management). Each handler runs as an independent virtual-thread actor with a channel; the scope tears them all down automatically when the body exits.

## Requirements

- Java 25 with `--enable-preview` (uses `ScopedValue` and `StructuredTaskScope`, both preview APIs in Java 25)

## Installation

### JitPack (early access)

```kotlin
// settings.gradle.kts
dependencyResolutionManagement {
    repositories {
        maven("https://jitpack.io")
    }
}

// build.gradle.kts
dependencies {
    implementation("com.github.on-the-ground:effectivejava:0.1.0")
}
```

Enable preview in your build:

```kotlin
tasks.withType<JavaCompile>().configureEach {
    options.compilerArgs.addAll(listOf("--enable-preview"))
}
tasks.withType<Test>().configureEach { jvmArgs("--enable-preview") }
tasks.withType<JavaExec>().configureEach { jvmArgs("--enable-preview") }
```

## Core concepts

| Term | Meaning |
|---|---|
| `perform(key, msg)` | Invoke an effect — enqueue `msg` to the handler bound to `key`. Returns `false` if no handler is bound. |
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

### Graceful no-op outside a scope

`perform` returns `false` when no handler is bound, so callers outside any scope are safe:

```java
boolean sent = HandlerScope.perform(LOG, "no handler here"); // false, no exception
```

## Lifecycle

```
HandlerScope.builder()
    .bind(...)
    .run(body)           ← handlers start as virtual threads
        body executes    ← effects are discoverable via ScopedValue
    ← body exits         ← handlers interrupted out-of-band
                         ← queued messages are drained and delivered
                         ← scope.join() waits for all handlers to finish
```

## Preview API note

`ScopedValue` and `StructuredTaskScope` are preview APIs. This library will track their stabilization. Once both are finalized (targeted for a future LTS), a stable release will be published to Maven Central.

## License

MIT — see [LICENSE](LICENSE).
