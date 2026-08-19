plugins {
    java
    application
}

application {
    mainClass = "com.lifeos.labs.performance.PerformanceLab"
}

dependencies {
    runtimeOnly("org.postgresql:postgresql:42.7.12")
    testImplementation("org.junit.jupiter:junit-jupiter:5.12.2")
}

tasks.register<JavaExec>("runPostgresQueryPlan") {
    group = "benchmark"
    description = "Runs the opt-in bounded PostgreSQL query-plan probe against identity-service."
    classpath = sourceSets.main.get().runtimeClasspath
    mainClass.set("com.lifeos.labs.performance.PostgresQueryPlanMain")
}
