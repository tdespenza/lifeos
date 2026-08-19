plugins {
    `java-library`
}

dependencies {
    implementation(project(":contracts:algorithm-engine"))

    testImplementation("org.junit.jupiter:junit-jupiter:5.12.2")
}
