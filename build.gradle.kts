plugins {
    java
    id("org.springframework.boot") version "4.1.1"
    id("io.spring.dependency-management") version "1.1.7"
}

group = "ai.devflow"
version = "0.1.0-SNAPSHOT"

java {
    toolchain {
        languageVersion = JavaLanguageVersion.of(21)
    }
}

repositories { mavenCentral() }

dependencyManagement {
    imports { mavenBom("org.springframework.ai:spring-ai-bom:2.0.1") }
}

dependencies {
    implementation("org.springframework.boot:spring-boot-starter-web")
    implementation("org.springframework.ai:spring-ai-starter-model-anthropic")
    implementation("org.eclipse.jgit:org.eclipse.jgit:7.1.0.202411261347-r")
    testImplementation("org.springframework.boot:spring-boot-starter-test")
    testRuntimeOnly("org.junit.platform:junit-platform-launcher")
}

// NOTE: this must configure the "test" task by name, not tasks.withType<Test>.
// withType<Test> also matches the liveTest task registered below, and when a
// tag is both included (by liveTest) and excluded (by this block) JUnit
// Platform's documented behavior is that exclusion wins -- so liveTest would
// silently run zero tests even with a real API key. Verified in Task 3.
tasks.named<Test>("test") {
    useJUnitPlatform {
        excludeTags("live")
    }
}

tasks.register<Test>("liveTest") {
    // Gradle only auto-wires classpath/testClassesDirs for the conventional
    // "test" task; a manually registered Test task needs it spelled out or it
    // silently reports NO-SOURCE and runs nothing -- verified in Task 3.
    testClassesDirs = sourceSets["test"].output.classesDirs
    classpath = sourceSets["test"].runtimeClasspath
    useJUnitPlatform { includeTags("live") }
    group = "verification"
}
