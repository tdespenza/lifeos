plugins {
    `java-library`
}

dependencies {
    api("org.springframework.boot:spring-boot-autoconfigure:3.5.16")
    api("org.springframework:spring-web:6.2.19")
    api("io.opentelemetry:opentelemetry-api:1.49.0")

    // The root JUnit BOM is the sole version authority for this plain Java module; the Spring Boot
    // starter contributes test utilities but this module does not apply Boot dependency management.
    testImplementation("org.springframework.boot:spring-boot-starter-test:3.5.16")
    testImplementation("io.opentelemetry:opentelemetry-sdk-trace:1.49.0")
}
