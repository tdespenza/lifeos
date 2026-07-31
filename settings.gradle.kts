plugins {
    id("org.gradle.toolchains.foojay-resolver-convention") version "1.0.0"
}

rootProject.name = "lifeos"

include("services:identity-service")
include("services:task-goal-service")
