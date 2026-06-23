plugins {
    `java-library`
    id("com.vanniktech.maven.publish") version "0.30.0"
}

group = "io.github.joohyung-park"
version = "0.1.0"

java {
    toolchain {
        languageVersion = JavaLanguageVersion.of(25)
    }
    withSourcesJar()
    withJavadocJar()
}

tasks.withType<JavaCompile>().configureEach {
    options.compilerArgs.add("--enable-preview")
}

tasks.withType<Test>().configureEach {
    jvmArgs("--enable-preview")
}

tasks.withType<JavaExec>().configureEach {
    jvmArgs("--enable-preview")
}

tasks.withType<Javadoc>().configureEach {
    val opts = options as StandardJavadocDocletOptions
    opts.addBooleanOption("-enable-preview", true)
    opts.addStringOption("-release", "25")
}

repositories {
    mavenCentral()
}

dependencies {
    testImplementation(platform("org.junit:junit-bom:5.11.4"))
    testImplementation("org.junit.jupiter:junit-jupiter")
    testRuntimeOnly("org.junit.platform:junit-platform-launcher")
}

tasks.test {
    useJUnitPlatform()
}

mavenPublishing {
    publishToMavenCentral(com.vanniktech.maven.publish.SonatypeHost.CENTRAL_PORTAL, automaticRelease = true)
    signAllPublications()

    pom {
        name.set("Effect-ive Java")
        description.set(
            "Algebraic Effect Handlers for Java. Bind effect handlers to a dynamic scope so they are " +
            "discoverable from anywhere in the call stack without threading explicit parameters through " +
            "every layer. Implements fire-and-forget effects, request-reply effects, and multi-effect " +
            "composition using Java 25 ScopedValue and StructuredTaskScope (virtual threads)."
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