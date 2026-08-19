plugins {
    java
    application
}

application {
    mainClass = "com.lifeos.labs.concurrency.ConcurrencyLab"
}

dependencies {
    testImplementation("org.junit.jupiter:junit-jupiter:5.12.2")
}

tasks.withType<JavaExec>().configureEach {
    jvmArgs("--enable-preview")
}

tasks.withType<JavaCompile>().configureEach {
    options.compilerArgs.add("--enable-preview")
}

tasks.withType<Test>().configureEach {
    jvmArgs("--enable-preview")
}
