import org.gradle.api.tasks.compile.JavaCompile
import org.gradle.api.tasks.testing.Test
import org.gradle.api.tasks.JavaExec

plugins {
    java
    id("org.springframework.boot")
    id("io.spring.dependency-management")
}

// The dashboard fan-out uses Java 25's preview StructuredTaskScope. Keep preview enabled only for
// this service; other modules opt in independently when they actually use a preview API.
tasks.withType<JavaCompile>().configureEach {
    options.compilerArgs.add("--enable-preview")
}

tasks.withType<Test>().configureEach {
    jvmArgs("--enable-preview")
}

tasks.withType<JavaExec>().configureEach {
    jvmArgs("--enable-preview")
}

// Spring Boot 3.5.16 manages Netty 4.1.135.Final through Lettuce. Override the
// BOM property until the next Spring Boot BOM carries the fixed 4.1.136.Final
// release (CVE-2026-59901); keep every Netty component on one compatible line.
extra["netty.version"] = "4.1.136.Final"

dependencies {
    implementation(project(":contracts:grpc-contracts"))
    implementation("org.springframework.boot:spring-boot-starter-web")
    implementation("org.springframework.boot:spring-boot-starter-graphql")
    implementation("org.springframework.boot:spring-boot-starter-actuator")
    implementation("org.springframework.boot:spring-boot-starter-data-redis")
    implementation("org.springframework.boot:spring-boot-starter-validation")
    implementation("io.micrometer:micrometer-registry-prometheus")
    implementation("io.micrometer:micrometer-tracing-bridge-otel")
    implementation("io.opentelemetry:opentelemetry-exporter-otlp")
    implementation("io.grpc:grpc-netty-shaded:1.83.1")

    testImplementation("org.springframework.boot:spring-boot-starter-test")
    testImplementation("org.testcontainers:junit-jupiter")
    testImplementation("org.testcontainers:testcontainers")
}
