import com.diffplug.gradle.spotless.SpotlessExtension
import info.solidsoft.gradle.pitest.PitestPluginExtension
import org.gradle.api.plugins.quality.Checkstyle
import org.gradle.api.plugins.quality.CheckstyleExtension
import org.gradle.api.tasks.Exec
import org.gradle.api.tasks.SourceSetContainer
import org.gradle.api.tasks.testing.Test
import org.gradle.jvm.toolchain.JavaLanguageVersion

plugins {
    java
    id("org.springframework.boot") version "3.5.16" apply false
    id("io.spring.dependency-management") version "1.1.7" apply false
    id("com.diffplug.spotless") version "8.9.0"
    id("org.cyclonedx.bom") version "3.3.0"
    id("info.solidsoft.pitest") version "1.19.0" apply false
}

allprojects {
    group = "com.lifeos"
    version = "0.1.0-SNAPSHOT"

    repositories {
        mavenCentral()
    }
}

/**
 * The repository-level formatter deliberately limits itself to safe, deterministic whitespace
 * normalization. Java layout is enforced through the same policy in every service, while this
 * block covers build, workflow, and documentation files that do not have a common parser.
 */
extensions.configure<SpotlessExtension> {
    format("repositoryWhitespace") {
        target(
            "*.gradle.kts",
            "*.md",
            ".dockerignore",
            ".github/**/*.yml",
            ".github/**/*.yaml",
            "config/**/*.xml",
            "docs/**/*.md",
            "labs/**/*.md",
            "infrastructure/**/*.Dockerfile",
            "infrastructure/**/*.yml",
            "infrastructure/**/*.yaml",
            "scripts/**/*.sh",
            "scripts/**/*.js"
        )
        targetExclude("**/.gradle/**", "**/build/**", "gradle/wrapper/**")
        trimTrailingWhitespace()
        endWithNewline()
    }
}

/*
 * New Spring Boot services need no root-build edit when their tests follow the normal naming
 * conventions below. The explicit entries keep pre-existing suites that predate the convention in
 * the named CI stage; remove them only after those tests are renamed rather than silently losing
 * their coverage.
 */
val defaultIntegrationTestPatterns = setOf(
    "*IntegrationTest",
    "*IntegrationTests",
    "*IT",
    "*MigrationTest"
)
val supplementalIntegrationTestClasses = mapOf(
    ":services:gateway-service" to setOf(
        "com.lifeos.gateway.GatewayApplicationContextTest",
        "com.lifeos.gateway.ratelimit.RedisGatewayRateLimiterTest"
    ),
    ":services:identity-service" to setOf(
        "com.lifeos.identity.account.UserAccountControllerIntegrationTest",
        "com.lifeos.identity.auth.LoginControllerIntegrationTest",
        "com.lifeos.identity.auth.SessionControllerIntegrationTest",
        "com.lifeos.identity.migration.IdentityFlywayMigrationTest"
    ),
    ":services:task-goal-service" to setOf(
        "com.lifeos.taskgoal.goal.GoalAuthorizationIntegrationTest",
        "com.lifeos.taskgoal.goal.idempotency.GoalCreationIdempotencyRecoveryIntegrationTest",
        "com.lifeos.taskgoal.goal.idempotency.GoalCreationPostgresIntegrationTest",
        "com.lifeos.taskgoal.migration.TaskGoalFlywayMigrationTest"
    )
)

/*
 * These are executable HTTP boundary contracts, not a documentation-only inventory. Keeping the
 * class list here makes the stage fail closed if a service removes or renames its contract suite.
 * New services instead add a `*ContractTest` or `*ContractTests` class, which is discovered
 * automatically.
 */
val defaultContractTestPatterns = setOf("*ContractTest", "*ContractTests")
val supplementalContractTestClasses = mapOf(
    ":services:gateway-service" to setOf("com.lifeos.gateway.routing.GatewayControllerTest"),
    ":services:identity-service" to setOf(
        "com.lifeos.identity.account.UserAccountControllerIntegrationTest",
        "com.lifeos.identity.auth.JwtValidationControllerTest"
    ),
    ":services:task-goal-service" to setOf("com.lifeos.taskgoal.goal.GoalControllerTest")
)

/*
 * All Java modules participate in compilation, unit testing, formatting, and static analysis.
 * The richer named suites and deployable-artifact checks below stay Spring-Boot-service-specific.
 * That split keeps contract/library modules in the baseline gates without requiring them to pretend
 * to be independently deployable services.
 */
allprojects {
    pluginManager.withPlugin("java") {
        apply(plugin = "checkstyle")
        apply(plugin = "com.diffplug.spotless")

        // Gradle 9 no longer supplies this runtime launcher implicitly for a plain Java project.
        // Keep JUnit Platform execution working for contract/library modules as well as Boot apps.
        dependencies {
            testRuntimeOnly("org.junit.platform:junit-platform-launcher:1.12.2")
        }

        java {
            toolchain {
                languageVersion = JavaLanguageVersion.of(25)
            }
        }

        tasks.withType<Test> {
            useJUnitPlatform()
            reports.junitXml.required.set(true)
            reports.html.required.set(true)
        }

        extensions.configure<SpotlessExtension> {
            java {
                target("src/**/*.java")
                trimTrailingWhitespace()
                endWithNewline()
            }
            format("moduleWhitespace") {
                target("build.gradle.kts", "src/**/*.yml", "src/**/*.yaml", "src/**/*.properties")
                trimTrailingWhitespace()
                endWithNewline()
            }
        }

        extensions.configure<CheckstyleExtension> {
            toolVersion = "10.26.1"
            configFile = rootProject.file("config/checkstyle/checkstyle.xml")
            isIgnoreFailures = false
            isShowViolations = true
        }

        tasks.withType<Checkstyle>().configureEach {
            reports.xml.required.set(true)
            reports.html.required.set(true)
        }
    }
}

allprojects {
    pluginManager.withPlugin("org.springframework.boot") {
        apply(plugin = "java")
        apply(plugin = "io.spring.dependency-management")
        apply(plugin = "info.solidsoft.pitest")

        dependencies {
            implementation(project(":contracts:observability"))
        }

        val testSourceSet = extensions.getByType<SourceSetContainer>().getByName("test")
        val configuredIntegrationTests = defaultIntegrationTestPatterns +
            supplementalIntegrationTestClasses.getOrDefault(path, emptySet())
        val configuredContractTests = defaultContractTestPatterns +
            supplementalContractTestClasses.getOrDefault(path, emptySet())

        tasks.register<Test>("integrationTest") {
            description = "Runs service-to-database, container, and Spring HTTP integration tests."
            group = "verification"
            testClassesDirs = testSourceSet.output.classesDirs
            classpath = testSourceSet.runtimeClasspath
            shouldRunAfter(tasks.named("test"))
            useJUnitPlatform()
            filter {
                isFailOnNoMatchingTests = true
                configuredIntegrationTests.forEach(::includeTestsMatching)
            }
        }

        tasks.register<Test>("contractTest") {
            description = "Runs executable public and internal service-boundary contract tests."
            group = "verification"
            testClassesDirs = testSourceSet.output.classesDirs
            classpath = testSourceSet.runtimeClasspath
            shouldRunAfter(tasks.named("integrationTest"))
            useJUnitPlatform()
            filter {
                isFailOnNoMatchingTests = true
                configuredContractTests.forEach(::includeTestsMatching)
            }
        }

        tasks.named("check") {
            dependsOn("integrationTest", "contractTest")
        }

        extensions.configure<PitestPluginExtension> {
            // PIT 1.25.8 includes Java 25 support; the JUnit 5 adapter is a matching current release.
            pitestVersion.set("1.25.8")
            junit5PluginVersion.set("1.2.3")
            targetClasses.set(setOf("com.lifeos.*"))
            targetTests.set(setOf("com.lifeos.*"))
            outputFormats.set(setOf("XML", "HTML"))
            timestampedReports.set(false)
            threads.set(2)
            timeoutConstInMillis.set(10_000)
            jvmPath.set(javaToolchains.launcherFor {
                languageVersion.set(JavaLanguageVersion.of(25))
            }.get().executablePath)
        }
    }
}

/*
 * Resolve task paths lazily after every child build script has applied its plugins. This keeps the
 * quality pipeline extensible: Java modules join baseline gates automatically, while Spring Boot
 * modules also join service-specific integration, contract, mutation, and packaging aggregates.
 */
fun javaSubprojectTaskPaths(taskName: String) = providers.provider {
    allprojects
        .filter { it != rootProject && it.pluginManager.hasPlugin("java") }
        .map { "${it.path}:$taskName" }
}

fun serviceTaskPaths(taskName: String) = providers.provider {
    allprojects
        .filter { it.pluginManager.hasPlugin("org.springframework.boot") }
        .map { "${it.path}:$taskName" }
}

tasks.register("formatCheck") {
    description = "Runs deterministic formatting checks for repository and Java-module sources."
    group = "verification"
    dependsOn("spotlessCheck")
    dependsOn(javaSubprojectTaskPaths("spotlessCheck"))
}

tasks.register("staticAnalysis") {
    description = "Runs Checkstyle static analysis for every Java module's production and test code."
    group = "verification"
    dependsOn(javaSubprojectTaskPaths("checkstyleMain"))
    dependsOn(javaSubprojectTaskPaths("checkstyleTest"))
}

tasks.register("mutationTest") {
    description = "Runs PIT mutation analysis for every service and emits XML/HTML reports."
    group = "verification"
    dependsOn(serviceTaskPaths("pitest"))
}

tasks.register("compileServices") {
    description = "Compiles production Java sources for every independently deployable service."
    group = "build"
    dependsOn(serviceTaskPaths("compileJava"))
}

tasks.register("compileProject") {
    description = "Compiles production Java sources for every non-root Java module."
    group = "build"
    dependsOn(javaSubprojectTaskPaths("compileJava"))
}

tasks.register("packageServices") {
    description = "Builds executable Spring Boot jars for every independently deployable service."
    group = "build"
    dependsOn(serviceTaskPaths("bootJar"))
}

tasks.register<Exec>("architectureTest") {
    description = "Verifies deployable-service boundaries, ownership, and runtime-image invariants."
    group = "verification"
    commandLine("bash", rootProject.file("scripts/verify-architecture.sh").absolutePath)
}

tasks.register<Exec>("endToEndTest") {
    description = "Runs the bounded live Gateway-to-Identity end-to-end smoke test."
    group = "verification"
    commandLine("bash", rootProject.file("scripts/end-to-end-smoke-test.sh").absolutePath)
}

tasks.register<Exec>("performanceTest") {
    description = "Runs the bounded k6 readiness performance smoke test against an enabled environment."
    group = "verification"
    commandLine("bash", rootProject.file("scripts/performance-smoke-test.sh").absolutePath)
}

tasks.register<Exec>("chaosTest") {
    description = "Runs the approved external dependency-isolation chaos experiment."
    group = "verification"
    commandLine("bash", rootProject.file("scripts/run-chaos-experiment.sh").absolutePath)
}

tasks.named("check") {
    // Gradle does not aggregate child `check` tasks by default. Keep the local entry point useful
    // without making it a mutation-test command; PIT remains the explicit `mutationTest` task.
    dependsOn("formatCheck", "staticAnalysis")
    dependsOn(javaSubprojectTaskPaths("check"))
    dependsOn("architectureTest")
}
