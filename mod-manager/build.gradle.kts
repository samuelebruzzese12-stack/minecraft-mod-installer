import org.jetbrains.kotlin.gradle.dsl.JvmTarget

// Gestore delle mod Java (Fase 3): profili indipendenti, installazione con backup e annullamento, controllo di
// dipendenze e conflitti. Modulo Kotlin puro (si testa sulla JVM). Non esegue mai codice delle mod.
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
    implementation(project(":jar-analyzer"))

    testImplementation(libs.junit)
}

tasks.test {
    useJUnit()
    testLogging {
        events("passed", "skipped", "failed")
        showStandardStreams = false
    }
}
