package io.effectivejava.checker;

import static com.google.errorprone.matchers.method.MethodMatchers.instanceMethod;
import static com.google.errorprone.matchers.method.MethodMatchers.staticMethod;
import static java.util.stream.Collectors.joining;

import com.google.common.collect.ImmutableList;
import com.google.errorprone.BugPattern;
import com.google.errorprone.BugPattern.SeverityLevel;
import com.google.errorprone.VisitorState;
import com.google.errorprone.bugpatterns.BugChecker;
import com.google.errorprone.matchers.Description;
import com.google.errorprone.matchers.Matcher;
import com.google.errorprone.util.ASTHelpers;
import com.google.errorprone.util.MoreAnnotations;
import com.sun.source.tree.ExpressionTree;
import com.sun.source.tree.LambdaExpressionTree;
import com.sun.source.tree.MemberSelectTree;
import com.sun.source.tree.MemberReferenceTree;
import com.sun.source.tree.MethodInvocationTree;
import com.sun.source.tree.MethodTree;
import com.sun.source.tree.Tree;
import com.sun.source.util.TreePath;
import com.sun.tools.javac.code.Attribute;
import com.sun.tools.javac.code.Symbol;
import com.sun.tools.javac.code.Type;
import java.util.LinkedHashSet;
import java.util.Optional;
import java.util.Set;

/**
 * Enforces that every call to {@code HandlerScope.find(X.class)} — and every call to a method
 * annotated {@code @Context({X.class, ...})} — is covered, at the call site, by either the
 * enclosing method's own {@code @Context} declaration or an inline
 * {@code HandlerScope.open().bind(X.class, ...).run(...)} chain wrapping the call.
 *
 * <p>This is a purely local, per-call-site check (no whole-program fixpoint): {@code @Context}
 * is always explicitly declared by the programmer, never inferred, so propagation falls out of
 * applying the same rule uniformly to {@code find()} calls and to calls to other
 * {@code @Context}-annotated methods.
 *
 * <p>Handler context is scoped to the current dynamic execution and is not propagated to
 * arbitrary asynchronous work. When walking outward from a call site, this checker therefore
 * stops at any lambda other than the directly enclosing {@code HandlerScope.run} body. This is
 * intentionally conservative: even a callback that happens to execute immediately on the same
 * thread is rejected when that fact cannot be proven locally.
 *
 * <p>This class ships inside the {@code effectivejava} artifact itself (its {@code
 * error_prone_core} dependency is {@code compileOnly}, so it adds nothing to a normal consumer's
 * runtime or transitive dependencies). It only runs for a consumer who opts in by applying the
 * {@code net.ltgt.errorprone} Gradle plugin and adding {@code effectivejava} to the {@code
 * errorprone} configuration — see the project README for the exact wiring.
 */
@BugPattern(
        name = "RequireContext",
        summary = "HandlerScope effect used without a matching @Context declaration or bind() block",
        severity = SeverityLevel.ERROR)
public final class RequireContextChecker extends BugChecker
        implements BugChecker.MethodInvocationTreeMatcher, BugChecker.MemberReferenceTreeMatcher {

    /** Instantiated by Error Prone's {@code ServiceLoader} discovery; not called directly. */
    public RequireContextChecker() {}

    private static final String HANDLER_SCOPE = "io.effectivejava.HandlerScope";
    private static final String BUILDER = "io.effectivejava.HandlerScope.Builder";
    private static final String CONTEXT_ANNOTATION = "io.effectivejava.Context";

    private static final Matcher<ExpressionTree> FIND = staticMethod().onClass(HANDLER_SCOPE).named("find");
    private static final Matcher<ExpressionTree> OPEN = staticMethod().onClass(HANDLER_SCOPE).named("open");
    private static final Matcher<ExpressionTree> BIND = instanceMethod().onDescendantOf(BUILDER).named("bind");
    private static final Matcher<ExpressionTree> RUN = instanceMethod().onDescendantOf(BUILDER).named("run");

    @Override
    public Description matchMethodInvocation(MethodInvocationTree tree, VisitorState state) {
        if (FIND.matches(tree, state)) {
            Type effect = classLiteralType(tree.getArguments().get(0), state);
            if (effect == null) {
                return buildDescription(tree)
                        .setMessage(
                                "HandlerScope.find(...) requires a class literal argument (e.g. Foo.class) "
                                        + "so this checker can statically verify the effect is declared.")
                        .build();
            }
            return checkRequired(tree, state, ImmutableList.of(effect));
        }

        Symbol.MethodSymbol sym = ASTHelpers.getSymbol(tree);
        if (sym == null) {
            return Description.NO_MATCH;
        }
        ImmutableList<Type> required = contextAnnotationTypes(sym);
        if (required.isEmpty()) {
            return Description.NO_MATCH;
        }
        return checkRequired(tree, state, required);
    }

    @Override
    public Description matchMemberReference(MemberReferenceTree tree, VisitorState state) {
        Symbol.MethodSymbol sym = ASTHelpers.getSymbol(tree);
        if (sym == null) {
            return Description.NO_MATCH;
        }

        if (sym.owner.getQualifiedName().contentEquals(HANDLER_SCOPE)
                && sym.getSimpleName().contentEquals("find")) {
            return buildDescription(tree)
                    .setMessage(
                            "HandlerScope::find is not supported because a method reference can "
                                    + "escape the current HandlerScope. Call HandlerScope.find(X.class) "
                                    + "directly in a context the checker can verify.")
                    .build();
        }

        ImmutableList<Type> required = contextAnnotationTypes(sym);
        if (required.isEmpty()) {
            return Description.NO_MATCH;
        }
        String names = required.stream().map(t -> t.tsym.getSimpleName().toString()).collect(joining(", "));
        return buildDescription(tree)
                .setMessage(
                        "A method requiring @Context("
                                + names
                                + ") cannot be used as a method reference because it can escape "
                                + "the current HandlerScope. Invoke the method directly in a context "
                                + "the checker can verify.")
                .build();
    }

    private Description checkRequired(MethodInvocationTree tree, VisitorState state, ImmutableList<Type> required) {
        Coverage coverage = coveredTypes(state);
        ImmutableList<Type> missing =
                required.stream()
                        .filter(t -> coverage.types().stream().noneMatch(c -> ASTHelpers.isSameType(c, t, state)))
                        .collect(ImmutableList.toImmutableList());
        if (missing.isEmpty()) {
            return Description.NO_MATCH;
        }
        String names = missing.stream().map(t -> t.tsym.getSimpleName().toString()).collect(joining(", "));
        if (coverage.crossedUnknownLambda()) {
            return buildDescription(tree)
                    .setMessage(
                            "This call requires "
                                    + names
                                    + " across a lambda boundary whose execution context cannot be verified. "
                                    + "HandlerScope context is not propagated to asynchronous work; bind the "
                                    + "effect in a HandlerScope.run(...) inside this lambda, or move the call "
                                    + "into the directly enclosing run body.")
                    .build();
        }
        return buildDescription(tree)
                .setMessage(
                        "This call requires @Context("
                                + names
                                + ") — declare it on the enclosing method, or wrap this call in "
                                + "HandlerScope.open().bind("
                                + names
                                + ", ...).run(...).")
                .build();
    }

    /** Walks outward from the call site collecting discharged effects, stopping at the enclosing method. */
    private Coverage coveredTypes(VisitorState state) {
        Set<Type> covered = new LinkedHashSet<>();
        TreePath path = state.getPath().getParentPath();
        while (path != null) {
            Tree node = path.getLeaf();
            if (node instanceof LambdaExpressionTree) {
                TreePath parentPath = path.getParentPath();
                Tree parentNode = parentPath == null ? null : parentPath.getLeaf();
                if (parentNode instanceof MethodInvocationTree) {
                    MethodInvocationTree invocation = (MethodInvocationTree) parentNode;
                    if (RUN.matches(invocation, state) && invocation.getArguments().contains(node)) {
                        ExpressionTree methodSelect = invocation.getMethodSelect();
                        if (methodSelect instanceof MemberSelectTree) {
                            ExpressionTree receiver = ((MemberSelectTree) methodSelect).getExpression();
                            dischargeChain(receiver, state).ifPresent(covered::addAll);
                            path = path.getParentPath();
                            continue;
                        }
                    }
                }
                return new Coverage(covered, true);
            } else if (node instanceof MethodTree) {
                Symbol.MethodSymbol enclosing = ASTHelpers.getSymbol((MethodTree) node);
                if (enclosing != null) {
                    covered.addAll(contextAnnotationTypes(enclosing));
                }
                break;
            }
            path = path.getParentPath();
        }
        return new Coverage(covered, false);
    }

    private record Coverage(Set<Type> types, boolean crossedUnknownLambda) {}

    /**
     * Recognizes only a literal, inline {@code HandlerScope.open().bind(A.class,...).bind(B.class,...)}
     * chain. Anything else (a builder stored in a variable, built conditionally, etc.) is not
     * recognized — this is intentionally conservative: it never discharges something it shouldn't,
     * it just sometimes fails to credit a discharge that a human could see is safe.
     */
    private static Optional<Set<Type>> dischargeChain(ExpressionTree receiver, VisitorState state) {
        Set<Type> bound = new LinkedHashSet<>();
        ExpressionTree walk = receiver;
        while (true) {
            if (!(walk instanceof MethodInvocationTree)) {
                return Optional.empty();
            }
            MethodInvocationTree call = (MethodInvocationTree) walk;
            if (BIND.matches(call, state)) {
                Type boundType = classLiteralType(call.getArguments().get(0), state);
                if (boundType == null) {
                    return Optional.empty();
                }
                bound.add(boundType);
                ExpressionTree methodSelect = call.getMethodSelect();
                if (!(methodSelect instanceof MemberSelectTree)) {
                    return Optional.empty();
                }
                walk = ((MemberSelectTree) methodSelect).getExpression();
                continue;
            }
            if (OPEN.matches(call, state)) {
                return Optional.of(bound);
            }
            return Optional.empty();
        }
    }

    /** Returns the type {@code X} for a literal {@code X.class} expression, or null otherwise. */
    private static Type classLiteralType(ExpressionTree tree, VisitorState state) {
        if (tree.getKind() != Tree.Kind.MEMBER_SELECT) {
            return null;
        }
        MemberSelectTree select = (MemberSelectTree) tree;
        if (!select.getIdentifier().contentEquals("class")) {
            return null;
        }
        return ASTHelpers.getType(select.getExpression());
    }

    /** Reads {@code @Context({...})} off a symbol without triggering classloading of the referenced types. */
    private static ImmutableList<Type> contextAnnotationTypes(Symbol sym) {
        for (Attribute.Compound compound : sym.getRawAttributes()) {
            if (compound.type.tsym.getQualifiedName().contentEquals(CONTEXT_ANNOTATION)) {
                return MoreAnnotations.getValue(compound, "value")
                        .map(
                                attr ->
                                        MoreAnnotations.asTypes(attr)
                                                .map(tm -> (Type) tm)
                                                .collect(ImmutableList.toImmutableList()))
                        .orElse(ImmutableList.of());
            }
        }
        return ImmutableList.of();
    }
}
