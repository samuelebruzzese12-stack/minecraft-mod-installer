import org.jetbrains.kotlin.gradle.dsl.JvmTarget

// Mondi Java e Bedrock (Fase 5): riconosce i mondi dentro un archivio (level.dat, regioni, dimensioni, giocatori, data pack)
// senza estrarli, e importa un mondo Java nella cartella «saves» di un profilo un file alla volta (niente mondo in memoria),
// con verifica di ogni voce, ripresa dopo un'interruzione e pulizia se si annulla. Kotlin puro (si prova sulla JVM).
plugins {
    alias(libs.plugins.kotlin.jvm)
}

java {
    sourceCompatibility = JavaVersion.VERSION_1_8
    targetCompatibility = JavaVersion.VERSION_1_8
}

kotlin {
    compilerOptions {
        jvmTarget.set(JvmTarget.JVM_1_8)
        // Gira anche su Android 8 (API 26): in compilazione sono visibili solo le API di Java 8.
        freeCompilerArgs.add("-Xjdk-release=8")
    }
}

dependencies {
    implementation(project(":core"))

    testImplementation(libs.junit)
}

tasks.test {
    useJUnit()
    testLogging {
        events("passed", "skipped", "failed")
        showStandardStreams = false
    }
}
