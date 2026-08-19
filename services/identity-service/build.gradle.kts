plugins {
    java
    id("org.springframework.boot")
    id("io.spring.dependency-management")
}

// Spring Boot 3.5.16 manages Netty 4.1.135.Final through Lettuce. Override the
// BOM property until the next Spring Boot BOM carries the fixed 4.1.136.Final
// release (CVE-2026-59901); keep every Netty component on one compatible line.
extra["netty.version"] = "4.1.136.Final"

dependencies {
    implementation(project(":contracts:event-contracts"))
    implementation("org.springframework.boot:spring-boot-starter-web")
    implementation("org.springframework.boot:spring-boot-starter-actuator")
    implementation("org.springframework.boot:spring-boot-starter-data-jpa")
    implementation("org.springframework.boot:spring-boot-starter-data-redis")
    implementation("org.springframework.kafka:spring-kafka")
    implementation("org.springframework.boot:spring-boot-starter-validation")
    implementation("org.flywaydb:flyway-core")
    implementation("org.springframework.security:spring-security-crypto")
    implementation("org.springframework.security:spring-security-oauth2-jose")
    implementation("com.yubico:webauthn-server-core:2.9.0")
    implementation("org.bouncycastle:bcprov-jdk18on:1.85.2")
    implementation("io.micrometer:micrometer-registry-prometheus")
    implementation("io.micrometer:micrometer-tracing-bridge-otel")
    implementation("io.opentelemetry:opentelemetry-exporter-otlp")
    // These direct fixed versions intentionally override Spring Boot's current managed line.
    // Yubico permits HttpCore 5.4.3 and it remediates CVE-2026-54399/CVE-2026-54428.
    implementation("org.apache.httpcomponents.core5:httpcore5:5.4.3")
    implementation("org.apache.httpcomponents.core5:httpcore5-h2:5.4.3")
    runtimeOnly("org.postgresql:postgresql:42.7.12")
    runtimeOnly("org.flywaydb:flyway-database-postgresql")

    testImplementation("org.springframework.boot:spring-boot-starter-test")
    testRuntimeOnly("com.h2database:h2")
}
