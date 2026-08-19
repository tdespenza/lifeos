plugins {
    id("org.gradle.toolchains.foojay-resolver-convention") version "1.0.0"
}

rootProject.name = "lifeos"

/*
 * A conventional module becomes part of the build as soon as its directory contains a build
 * script. Keeping this discovery deterministic means a newly added service or contract project
 * automatically joins the root compilation, quality, test, packaging, and CI aggregates.
 */
fun includeConventionalModules(parentDirectory: String) {
    file(parentDirectory)
        .listFiles()
        ?.asSequence()
        ?.filter { module ->
            module.isDirectory && !module.name.startsWith(".") && module.resolve("build.gradle.kts").isFile
        }
        ?.sortedBy { module -> module.name }
        ?.forEach { module -> include("${parentDirectory}:${module.name}") }
}

includeConventionalModules("contracts")
includeConventionalModules("services")
includeConventionalModules("labs")
includeConventionalModules("clients")
includeConventionalModules("cli")
