plugins {
    application
}

repositories {
    mavenCentral()
}

val javafxPlatform = when {
    System.getProperty("os.name").startsWith("Mac") && System.getProperty("os.arch") == "aarch64" -> "mac-aarch64"
    System.getProperty("os.name").startsWith("Mac") -> "mac"
    System.getProperty("os.name").startsWith("Linux") && System.getProperty("os.arch") == "aarch64" -> "linux-aarch64"
    System.getProperty("os.name").startsWith("Linux") -> "linux"
    else -> "win"
}

dependencies {
    implementation("org.openjfx:javafx-controls:21.0.7:$javafxPlatform")
    implementation("org.openjfx:javafx-graphics:21.0.7:$javafxPlatform")
    implementation("org.openjfx:javafx-base:21.0.7:$javafxPlatform")
    testImplementation("org.junit.jupiter:junit-jupiter:5.12.2")
}

application {
    mainClass = "com.lifeos.desktop.LifeOsDesktopApplication"
}

tasks.test {
    useJUnitPlatform()
}
