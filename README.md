# Effect-ive Java

Algebraic Effect Handlers for Java — bind effect handlers to a dynamic scope so they are discoverable from anywhere in the call stack, without threading explicit parameters through every layer.

## Motivation

Algebraic Effect Handlers let you separate *what* an effect does from *where* the effect is handled. Code deep in a call stack can invoke a logging effect, a metrics effect, or a request-reply effect without knowing who handles it. The caller decides, at the boundary, what each effect means.

This library implements that model using Java's `ScopedValue` (ambient context propagation) and [`Proxxy`](https://github.com/joohyung-park/proxxy) (partitioned virtual-thread actors). Each handler is backed by a Proxxy proxy: method calls are routed to partition threads by a configurable router function, so the same routing key always reaches the same thread and the same target instance — no synchronization required.

## Requirements

- Java 25+ (`ScopedValue` is a standard API from Java 25)

## Installation

```kotlin
// build.gradle.kts
dependencies {
    implementation("io.github.joohyung-park:effectivejava:0.3.0")
}
```

## Core concepts

| Term | Meaning |
|---|---|
| Effect interface | A plain Java interface whose methods represent effects. No annotations required. |
| `bind(Type.class, factory)` | Register a handler implementation for an effect type. |
| `find(Type.class)` | Discover the proxy bound to an effect type in the current scope. |
| `run(body)` | Start all handlers, execute `body` with them discoverable, then tear down. |
| Router | A `ToIntBiFunction<Method, Object[]>` that maps a call to a partition. Default: `BY_FIRST_ARG`. |

## Usage

### Fire-and-forget effect

```java
interface Logger {
    void log(String userId, String message);
}

HandlerScope.builder()
    .bind(Logger.class, () -> (userId, msg) -> System.out.println("[LOG] " + msg))
    .run(() -> {
        Logger log = HandlerScope.find(Logger.class);
        log.log("alice", "application started");  // routed to alice's partition thread
        log.log("alice", "processing req");        // same thread, same Logger instance
    });
```

Void methods are fire-and-forget — the caller does not block.

### Request-reply effect

Non-void methods block the caller until the handler returns.

```java
interface Greeter {
    String greet(String userId, String name);
}

HandlerScope.builder()
    .bind(Greeter.class, () -> (userId, name) -> "Hello, " + name + "!")
    .run(() -> {
        String result = HandlerScope.find(Greeter.class).greet("alice", "World");
        System.out.println(result); // "Hello, World!"
    });
```

### Multiple effect types

```java
HandlerScope.builder()
    .bind(Logger.class,  MyLogger::new)
    .bind(Greeter.class, MyGreeter::new)
    .run(() -> {
        HandlerScope.find(Logger.class).log("alice", "user login");
        String greeting = HandlerScope.find(Greeter.class).greet("alice", "World");
    });
```

### Custom router

By default, calls are routed by the first argument's hash code (`BY_FIRST_ARG`). Supply an explicit router for finer control.

```java
// Route every call to partition 0 — fully ordered, single-threaded handler
HandlerScope.builder()
    .bind(Logger.class, MyLogger::new, (method, args) -> 0)
    .run(() -> { ... });

// Route by second argument instead of first
HandlerScope.builder()
    .bind(Logger.class, MyLogger::new, (method, args) -> args[1].hashCode())
    .run(() -> { ... });
```

### Fail-loud contract

`find` throws `IllegalStateException` when called outside a scope or for an unregistered type. A missing handler is always a programming error — never silence it.

```java
// ✗ throws — no scope active
HandlerScope.find(Logger.class);

// ✗ throws — Logger not bound
HandlerScope.builder()
    .bind(Greeter.class, MyGreeter::new)
    .run(() -> HandlerScope.find(Logger.class));

// ✓ correct
HandlerScope.builder()
    .bind(Logger.class, MyLogger::new)
    .run(() -> HandlerScope.find(Logger.class).log("alice", "safe here"));
```

## Lifecycle

```
HandlerScope.builder()
    .bind(Logger.class, MyLogger::new)   ← factory registered, not yet started
    .run(body)                           ← Proxxy proxy created (2 partition threads per handler)
        body executes                    ← find() returns the proxy; calls routed by router
                                         ← same routing key → same thread → same target instance
    ← body exits (normal or exception)  ← all proxies closed
                                         ← pending non-void calls complete before shutdown
                                         ← run() returns only after all handlers finish
```

## License

MIT — see [LICENSE](LICENSE).
