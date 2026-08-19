plugins {
    `java-library`
}

dependencies {
    testImplementation("org.junit.jupiter:junit-jupiter:5.12.2")
}

tasks.register<JavaExec>("runAlgorithmBenchmarks") {
    group = "verification"
    description = "Runs bounded correctness-checked algorithm microbenchmarks and writes a local JSON report."
    classpath = sourceSets["main"].runtimeClasspath
    mainClass.set("com.lifeos.algorithms.benchmark.AlgorithmBenchmarkMain")
    args(layout.buildDirectory.file("reports/benchmarks/algorithm-engine.json").get().asFile.absolutePath)
}
