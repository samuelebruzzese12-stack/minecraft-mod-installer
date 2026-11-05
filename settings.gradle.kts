pluginManagement {
    repositories {
        google()
        mavenCentral()
        gradlePluginPortal()
    }
}

dependencyResolutionManagement {
    repositoriesMode.set(RepositoriesMode.FAIL_ON_PROJECT_REPOS)
    repositories {
        google()
        mavenCentral()
    }
}

rootProject.name = "MinecraftModInstaller"

include(":core")
include(":download-engine")
include(":jar-analyzer")
include(":mod-manager")
include(":catalog")
include(":modpack-engine")
include(":world-engine")
include(":mod-converter")
include(":app")

// Finto Minecraft usato solo dalla prova su emulatore in CI (vedi ci/emulator_smoke.sh). Si include soltanto con
// -PwithFakeMinecraft, così non può influire sulla compilazione dell'app vera.
if (providers.gradleProperty("withFakeMinecraft").isPresent) {
    include(":fakeminecraft")
    project(":fakeminecraft").projectDir = file("ci/fakeminecraft")
}
