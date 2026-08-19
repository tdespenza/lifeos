plugins {
    java
    application
}

application {
    mainClass.set("com.lifeos.cli.LifeOsCli")
}

dependencies {
    testImplementation("org.junit.jupiter:junit-jupiter:5.12.2")
}
