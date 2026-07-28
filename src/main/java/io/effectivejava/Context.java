package io.effectivejava;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * Declares that a method depends on one or more effect types being bound in the caller's
 * {@link HandlerScope}.
 *
 * <p>This annotation carries no runtime behavior by itself — {@link HandlerScope#find} still
 * fails loud with {@link IllegalStateException} if a declared effect isn't actually bound. Its
 * purpose is purely to make that dependency visible next to the method signature, the way a
 * {@code throws} clause makes a checked exception visible, instead of leaving it buried
 * somewhere in the method body.
 *
 * <p>This artifact also ships {@link io.effectivejava.checker.RequireContextChecker}, an Error
 * Prone {@code BugChecker} that uses this annotation to verify, at every {@link
 * HandlerScope#find} call site and every call to another {@code @Context}-annotated method, that
 * the required effect types are covered by either the enclosing method's own {@code @Context} or
 * an inline {@code HandlerScope.open().bind(X.class, ...).run(...)} block wrapping the call. The
 * checker only runs for a consumer who opts in by applying the {@code net.ltgt.errorprone}
 * Gradle plugin — see the project README for the exact wiring. Without it, {@code @Context} is
 * purely documentation.
 *
 * <pre>{@code
 * @Context(Logger.class)
 * void greetLoudly(String name) {
 *     HandlerScope.find(Logger.class).log("greet", "hello, " + name);
 * }
 * }</pre>
 *
 * @see HandlerScope#find(Class)
 * @see io.effectivejava.checker.RequireContextChecker
 */
@Retention(RetentionPolicy.CLASS)
@Target(ElementType.METHOD)
public @interface Context {

    /**
     * The effect types this method depends on.
     *
     * @return the effect types this method depends on
     */
    Class<?>[] value();
}
