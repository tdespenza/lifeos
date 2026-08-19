plugins {
    java
    application
}

application {
    mainClass = "com.lifeos.labs.blockchain.BlockchainLab"
}

dependencies {
    implementation(project(":contracts:trust-ledger"))
    testImplementation("org.junit.jupiter:junit-jupiter:5.12.2")
}
