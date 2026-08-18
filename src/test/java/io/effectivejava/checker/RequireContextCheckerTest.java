package io.effectivejava.checker;

import com.google.errorprone.CompilationTestHelper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class RequireContextCheckerTest {

    private CompilationTestHelper helper;

    @BeforeEach
    void setUp() {
        helper = CompilationTestHelper.newInstance(RequireContextChecker.class, getClass());
    }

    @Test
    void direct_find_without_context_errors() {
        helper.addSourceLines(
                        "Test.java",
                        "import io.effectivejava.HandlerScope;",
                        "class Test {",
                        "  interface Logger { void log(String k, String m); }",
                        "  void m() {",
                        "    // BUG: Diagnostic contains: requires @Context(Logger)",
                        "    HandlerScope.find(Logger.class);",
                        "  }",
                        "}")
                .doTest();
    }

    @Test
    void direct_find_with_matching_context_is_clean() {
        helper.addSourceLines(
                        "Test.java",
                        "import io.effectivejava.Context;",
                        "import io.effectivejava.HandlerScope;",
                        "class Test {",
                        "  interface Logger { void log(String k, String m); }",
                        "  @Context(Logger.class)",
                        "  void m() {",
                        "    HandlerScope.find(Logger.class);",
                        "  }",
                        "}")
                .doTest();
    }

    @Test
    void find_inside_inline_bind_run_is_clean_without_context() {
        helper.addSourceLines(
                        "Test.java",
                        "import io.effectivejava.HandlerScope;",
                        "class Test {",
                        "  interface Logger { void log(String k, String m); }",
                        "  void m() throws Exception {",
                        "    HandlerScope.open()",
                        "        .bind(Logger.class, () -> (k, msg) -> {})",
                        "        .run(() -> {",
                        "          HandlerScope.find(Logger.class);",
                        "        });",
                        "  }",
                        "}")
                .doTest();
    }

    @Test
    void find_inside_unknown_nested_lambda_does_not_inherit_run_binding() {
        helper.addSourceLines(
                        "Test.java",
                        "import io.effectivejava.HandlerScope;",
                        "class Test {",
                        "  interface Logger { void log(String k, String m); }",
                        "  void submit(Runnable task) {}",
                        "  void m() throws Exception {",
                        "    HandlerScope.open()",
                        "        .bind(Logger.class, () -> (k, msg) -> {})",
                        "        .run(() -> {",
                        "          submit(() -> {",
                        "            // BUG: Diagnostic contains: across a lambda boundary",
                        "            HandlerScope.find(Logger.class);",
                        "          });",
                        "        });",
                        "  }",
                        "}")
                .doTest();
    }

    @Test
    void run_started_inside_unknown_lambda_provides_its_own_binding() {
        helper.addSourceLines(
                        "Test.java",
                        "import io.effectivejava.HandlerScope;",
                        "class Test {",
                        "  interface Logger { void log(String k, String m); }",
                        "  void submit(Runnable task) {}",
                        "  void m() {",
                        "    submit(() -> {",
                        "      try {",
                        "        HandlerScope.open()",
                        "            .bind(Logger.class, () -> (k, msg) -> {})",
                        "            .run(() -> HandlerScope.find(Logger.class));",
                        "      } catch (Exception e) {",
                        "        throw new RuntimeException(e);",
                        "      }",
                        "    });",
                        "  }",
                        "}")
                .doTest();
    }

    @Test
    void enclosing_context_does_not_cross_unknown_lambda() {
        helper.addSourceLines(
                        "Test.java",
                        "import io.effectivejava.Context;",
                        "import io.effectivejava.HandlerScope;",
                        "class Test {",
                        "  interface Logger { void log(String k, String m); }",
                        "  void submit(Runnable task) {}",
                        "  @Context(Logger.class)",
                        "  void m() {",
                        "    submit(() -> {",
                        "      // BUG: Diagnostic contains: across a lambda boundary",
                        "      HandlerScope.find(Logger.class);",
                        "    });",
                        "  }",
                        "}")
                .doTest();
    }

    @Test
    void context_method_reference_errors() {
        helper.addSourceLines(
                        "Test.java",
                        "import io.effectivejava.Context;",
                        "class Test {",
                        "  interface Logger { void log(String k, String m); }",
                        "  @Context(Logger.class)",
                        "  void helper() {}",
                        "  void m() {",
                        "    // BUG: Diagnostic contains: cannot be used as a method reference",
                        "    Runnable task = this::helper;",
                        "  }",
                        "}")
                .doTest();
    }

    @Test
    void find_method_reference_errors() {
        helper.addSourceLines(
                        "Test.java",
                        "import io.effectivejava.HandlerScope;",
                        "import java.util.function.Function;",
                        "class Test {",
                        "  interface Logger { void log(String k, String m); }",
                        "  void m() {",
                        "    // BUG: Diagnostic contains: HandlerScope::find is not supported",
                        "    Function<Class<Logger>, Logger> finder = HandlerScope::find;",
                        "  }",
                        "}")
                .doTest();
    }

    @Test
    void call_to_context_annotated_helper_without_context_errors() {
        helper.addSourceLines(
                        "Test.java",
                        "import io.effectivejava.Context;",
                        "import io.effectivejava.HandlerScope;",
                        "class Test {",
                        "  interface Logger { void log(String k, String m); }",
                        "  @Context(Logger.class)",
                        "  void helper() {",
                        "    HandlerScope.find(Logger.class).log(\"k\", \"m\");",
                        "  }",
                        "  void caller() {",
                        "    // BUG: Diagnostic contains: requires @Context(Logger)",
                        "    helper();",
                        "  }",
                        "}")
                .doTest();
    }

    @Test
    void call_to_context_annotated_helper_with_matching_context_is_clean() {
        helper.addSourceLines(
                        "Test.java",
                        "import io.effectivejava.Context;",
                        "import io.effectivejava.HandlerScope;",
                        "class Test {",
                        "  interface Logger { void log(String k, String m); }",
                        "  @Context(Logger.class)",
                        "  void helper() {",
                        "    HandlerScope.find(Logger.class).log(\"k\", \"m\");",
                        "  }",
                        "  @Context(Logger.class)",
                        "  void caller() {",
                        "    helper();",
                        "  }",
                        "}")
                .doTest();
    }

    @Test
    void call_to_context_annotated_helper_inside_inline_bind_run_is_clean() {
        helper.addSourceLines(
                        "Test.java",
                        "import io.effectivejava.Context;",
                        "import io.effectivejava.HandlerScope;",
                        "class Test {",
                        "  interface Logger { void log(String k, String m); }",
                        "  @Context(Logger.class)",
                        "  void helper() {",
                        "    HandlerScope.find(Logger.class).log(\"k\", \"m\");",
                        "  }",
                        "  void caller() throws Exception {",
                        "    HandlerScope.open()",
                        "        .bind(Logger.class, () -> (k, msg) -> {})",
                        "        .run(() -> {",
                        "          helper();",
                        "        });",
                        "  }",
                        "}")
                .doTest();
    }

    @Test
    void find_with_non_literal_class_argument_errors() {
        helper.addSourceLines(
                        "Test.java",
                        "import io.effectivejava.HandlerScope;",
                        "class Test {",
                        "  interface Logger { void log(String k, String m); }",
                        "  void m(Class<Logger> c) {",
                        "    // BUG: Diagnostic contains: requires a class literal argument",
                        "    HandlerScope.find(c);",
                        "  }",
                        "}")
                .doTest();
    }

    @Test
    void builder_stored_in_variable_still_errors() {
        helper.addSourceLines(
                        "Test.java",
                        "import io.effectivejava.HandlerScope;",
                        "class Test {",
                        "  interface Logger { void log(String k, String m); }",
                        "  void m() throws Exception {",
                        "    HandlerScope.Builder b = HandlerScope.open().bind(Logger.class, () -> (k, msg) -> {});",
                        "    b.run(() -> {",
                        "      // BUG: Diagnostic contains: requires @Context(Logger)",
                        "      HandlerScope.find(Logger.class);",
                        "    });",
                        "  }",
                        "}")
                .doTest();
    }

    @Test
    void nested_scopes_both_discharge_sets_apply() {
        helper.addSourceLines(
                        "Test.java",
                        "import io.effectivejava.HandlerScope;",
                        "class Test {",
                        "  interface Logger { void log(String k, String m); }",
                        "  interface Greeter { String greet(String k, String n); }",
                        "  void m() throws Exception {",
                        "    HandlerScope.open()",
                        "        .bind(Logger.class, () -> (k, msg) -> {})",
                        "        .run(() -> {",
                        "          HandlerScope.open()",
                        "              .bind(Greeter.class, () -> (k, n) -> n)",
                        "              .run(() -> {",
                        "                HandlerScope.find(Logger.class);",
                        "                HandlerScope.find(Greeter.class);",
                        "              });",
                        "        });",
                        "  }",
                        "}")
                .doTest();
    }
}
