# Effect-ive Java

Algebraic Effect Handlers for Java — bind effect handlers to a dynamic scope so they are discoverable from anywhere in the call stack, without threading explicit parameters through every layer.

## Motivation

Algebraic Effect Handlers let you separate *what* an effect does from *where* the effect is handled. Code deep in a call stack can invoke a logging effect, a metrics effect, or a request-reply effect without knowing who handles it. The caller decides, at the boundary, what each effect means.

This library implements that model using Java's `ScopedValue` (ambient context propagation) and [`Proxxy`](https://github.com/on-the-ground/proxxy) (partitioned virtual-thread actors). Each handler is backed by a Proxxy proxy: method calls are routed to partition threads by a configurable router function, so the same routing key always reaches the same thread and the same target instance — no synchronization required.

## Requirements

- Java 25+ (`ScopedValue` is a standard API from Java 25)

## Installation

```kotlin
// build.gradle.kts
dependencies {
    implementation("io.github.joohyung-park:effectivejava:0.5.0")
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
| `@Context(Type.class, ...)` | Declares, next to a method's signature, which effect types it depends on. No runtime behavior on its own — see [Declaring effects with `@Context`](#declaring-effects-with-context). |

## Usage

### Fire-and-forget effect

```java
interface Logger {
    void log(String userId, String message);
}

HandlerScope.open()
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

HandlerScope.open()
    .bind(Greeter.class, () -> (userId, name) -> "Hello, " + name + "!")
    .run(() -> {
        String result = HandlerScope.find(Greeter.class).greet("alice", "World");
        System.out.println(result); // "Hello, World!"
    });
```

### Multiple effect types

```java
HandlerScope.open()
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
HandlerScope.open()
    .bind(Logger.class, MyLogger::new, (method, args) -> 0)
    .run(() -> { ... });

// Route by second argument instead of first
HandlerScope.open()
    .bind(Logger.class, MyLogger::new, (method, args) -> args[1].hashCode())
    .run(() -> { ... });
```

### Fail-loud contract

`find` throws `IllegalStateException` when called outside a scope or for an unregistered type. A missing handler is always a programming error — never silence it.

```java
// ✗ throws — no scope active
HandlerScope.find(Logger.class);

// ✗ throws — Logger not bound
HandlerScope.open()
    .bind(Greeter.class, MyGreeter::new)
    .run(() -> HandlerScope.find(Logger.class));

// ✓ correct
HandlerScope.open()
    .bind(Logger.class, MyLogger::new)
    .run(() -> HandlerScope.find(Logger.class).log("alice", "safe here"));
```

### Declaring effects with `@Context`

The fail-loud contract only catches a missing handler when `find` actually runs, which can be buried arbitrarily deep in a method body — nothing about a method's signature tells a reader (or a refactor) that it depends on an effect. `@Context` makes that dependency explicit, the way a `throws` clause makes a checked exception explicit:

```java
@Context(Logger.class)
void greetLoudly(String name) {
    HandlerScope.find(Logger.class).log("greet", "hello, " + name);
}
```

`@Context` carries no runtime behavior by itself — it's a marker for readers and for tooling. This same artifact also ships `io.effectivejava.checker.RequireContextChecker`, an [Error Prone](https://errorprone.info/) `BugChecker` that enforces it at compile time: it rejects any `HandlerScope.find(X.class)` call, or call to another `@Context`-annotated method, that isn't covered by the enclosing method's own `@Context` or by an inline `HandlerScope.open().bind(X.class, ...).run(...)` block wrapping the call. Coverage is checked per call site, not inferred — a method that transitively depends on an effect through a call chain must declare it itself, same as `throws` propagation.

The checker only recognizes inline, literal `bind(...).run(...)` chains as discharging a requirement — a builder stored in a variable, built conditionally, or invoked via a method reference won't be credited, and the checker will conservatively ask for an explicit `@Context` instead. This is intentional: it can be overly strict, but it never silently lets an uncovered effect through.

#### Enabling the checker

The checker's `error_prone_core` dependency is `compileOnly`, so depending on `effectivejava` normally pulls in none of it — using `@Context` without enabling the checker is valid, it's just inert documentation at that point. To actually enforce it, apply the [`net.ltgt.errorprone`](https://plugins.gradle.org/plugin/net.ltgt.errorprone) Gradle plugin to *your own* build and add this same artifact to the `errorprone` configuration:

```kotlin
// build.gradle.kts
plugins {
    id("net.ltgt.errorprone") version "5.1.0"
}

dependencies {
    implementation("io.github.joohyung-park:effectivejava:0.5.0")
    errorprone("io.github.joohyung-park:effectivejava:0.5.0")
}
```

This is always opt-in — publishing a jar can't force a compiler plugin onto anyone else's build. Enforcement only exists in codebases that have deliberately wired it in; this repository's own build doesn't apply the checker to itself (`HandlerScope` implements `find`, it doesn't call it), so there's nothing here for it to check yet.

## Lifecycle

```
HandlerScope.open()
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
