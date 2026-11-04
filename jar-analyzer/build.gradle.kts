import org.jetbrains.kotlin.gradle.dsl.JvmTarget

// Analisi statica dei file Java (Fase 3): .jar di mod, pacchetti di risorse, shader, datapack, .mrpack e modpack CurseForge.
// Legge solo metadati e struttura dell'archivio: NON carica né esegue mai codice Java. Modulo Kotlin puro (si testa sulla JVM).
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
