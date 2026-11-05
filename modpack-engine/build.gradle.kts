import org.jetbrains.kotlin.gradle.dsl.JvmTarget

// Installazione dei modpack (Fase 4): legge l'indice di un modpack Modrinth (.mrpack), prepara il piano dei download,
// li fa eseguire dal motore dei download (con verifica dell'impronta), applica gli «overrides» solo dentro il profilo e
// registra i file nel MOD MANAGER. Kotlin puro (si prova sulla JVM); non esegue mai codice delle mod.
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
    implementation(project(":download-engine"))
    implementation(project(":jar-analyzer"))
    implementation(project(":mod-manager"))

    testImplementation(libs.junit)
}

tasks.test {
    useJUnit()
    testLogging {
        events("passed", "skipped", "failed")
        showStandardStreams = false
    }
}
