import org.gradle.external.javadoc.StandardJavadocDocletOptions

plugins {
    `java-library`
    `maven-publish`
    signing
}

group = "io.github.joohyung-park"
version = "0.5.0"

java {
    toolchain {
        languageVersion = JavaLanguageVersion.of(25)
    }
    withSourcesJar()
    withJavadocJar()
}

repositories {
    mavenCentral()
}

val errorProneVersion = "2.50.0"

// io.effectivejava.checker.RequireContextChecker is an Error Prone BugChecker shipped inside
// this artifact; its sources reference javac internals (com.sun.tools.javac.*), which requires
// these exports on JDK 16+ (JEP 396) both to compile it and to run it under CompilationTestHelper
// in tests. error_prone_core is compileOnly, so it adds nothing to a normal consumer's runtime
// or transitive dependencies — see the README for how a consumer opts into the checker itself.
val errorProneExports = listOf(
    "jdk.compiler/com.sun.tools.javac.api",
    "jdk.compiler/com.sun.tools.javac.code",
    "jdk.compiler/com.sun.tools.javac.file",
    "jdk.compiler/com.sun.tools.javac.main",
    "jdk.compiler/com.sun.tools.javac.model",
    "jdk.compiler/com.sun.tools.javac.parser",
    "jdk.compiler/com.sun.tools.javac.processing",
    "jdk.compiler/com.sun.tools.javac.tree",
    "jdk.compiler/com.sun.tools.javac.util",
)
// --add-opens only matters for reflective access at runtime (CompilationTestHelper setting
// javac internals accessible), never for compiling/documenting source that merely references
// those types — passing it to compileJava/javadoc is a silently-ignored no-op that javac warns
// about, so it's kept out of errorProneCompileArgs and only added to the test JVM's args.
val errorProneOpens = listOf(
    "jdk.compiler/com.sun.tools.javac.code",
    "jdk.compiler/com.sun.tools.javac.comp",
)
val errorProneExportArgs = errorProneExports.map { "--add-exports=$it=ALL-UNNAMED" }
val errorProneOpenArgs = errorProneOpens.map { "--add-opens=$it=ALL-UNNAMED" }

dependencies {
    implementation("io.github.joohyung-park:proxxy:0.2.1")
    compileOnly("com.google.errorprone:error_prone_core:$errorProneVersion")

    testImplementation(platform("org.junit:junit-bom:5.11.4"))
    testImplementation("org.junit.jupiter:junit-jupiter")
    testImplementation("com.google.errorprone:error_prone_core:$errorProneVersion")
    testImplementation("com.google.errorprone:error_prone_test_helpers:$errorProneVersion")
    testRuntimeOnly("org.junit.platform:junit-platform-launcher")
}

tasks.withType<JavaCompile>().configureEach {
    options.compilerArgs.addAll(errorProneExportArgs)
}

tasks.test {
    useJUnitPlatform()
    jvmArgs(errorProneExportArgs + errorProneOpenArgs)
}

tasks.javadoc {
    // The javadoc tool resolves io.effectivejava.checker.RequireContextChecker's own method
    // signatures too, so it needs the same javac-internals exports as compilation.
    val docletOptions = options as StandardJavadocDocletOptions
    docletOptions.addMultilineStringsOption("-add-exports").value =
        errorProneExports.map { "$it=ALL-UNNAMED" }
}

publishing {
    publications {
        create<MavenPublication>("maven") {
            from(components["java"])
            pom {
                name.set("Effect-ive Java")
                description.set(
                    "Algebraic Effect Handlers for Java. Bind effect handlers to a dynamic scope so they are " +
                    "discoverable from anywhere in the call stack without threading explicit parameters through " +
                    "every layer. Implements fire-and-forget effects, request-reply effects, and multi-effect " +
                    "composition using Java 25 ScopedValue and virtual-thread daemons."
                )
                inceptionYear.set("2026")
                url.set("https://github.com/on-the-ground/effectivejava")
                licenses {
                    license {
                        name.set("MIT License")
                        url.set("https://opensource.org/licenses/MIT")
                        distribution.set("repo")
                    }
                }
                developers {
                    developer {
                        id.set("joohyung-park")
                        name.set("Joohyung Park")
                        url.set("https://github.com/joohyung-park/")
                    }
                }
                scm {
                    url.set("https://github.com/on-the-ground/effectivejava/")
                    connection.set("scm:git:git://github.com/on-the-ground/effectivejava.git")
                    developerConnection.set("scm:git:ssh://git@github.com/on-the-ground/effectivejava.git")
                }
            }
        }
    }
    repositories {
        maven {
            name = "stagingDeploy"
            url = uri(layout.buildDirectory.dir("staging-deploy"))
        }
    }
}

signing {
    sign(publishing.publications["maven"])
}

tasks.register<Zip>("bundleForMavenCentral") {
    dependsOn("publishMavenPublicationToStagingDeployRepository")
    from(layout.buildDirectory.dir("staging-deploy"))
    archiveFileName.set("bundle.zip")
    destinationDirectory.set(layout.buildDirectory.dir("bundle"))
}
