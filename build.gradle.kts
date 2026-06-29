plugins {
    `java-library`
    `maven-publish`
    signing
}

group = "io.github.joohyung-park"
version = "0.2.2"

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

dependencies {
    implementation("io.github.joohyung-park:daemonizer:0.1.2")
    testImplementation(platform("org.junit:junit-bom:5.11.4"))
    testImplementation("org.junit.jupiter:junit-jupiter")
    testRuntimeOnly("org.junit.platform:junit-platform-launcher")
}

tasks.test {
    useJUnitPlatform()
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
