plugins {
    `java-library`
}

dependencies {
    api("org.springframework.boot:spring-boot-autoconfigure:3.5.16")
    api("org.springframework:spring-web:6.2.19")
    api("io.opentelemetry:opentelemetry-api:1.49.0")

    testImplementation("org.springframework.boot:spring-boot-starter-test:3.5.16")
    testImplementation("org.junit.jupiter:junit-jupiter:5.12.2")
    testImplementation("io.opentelemetry:opentelemetry-sdk-trace:1.49.0")
}
