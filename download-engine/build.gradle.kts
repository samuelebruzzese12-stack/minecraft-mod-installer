import org.jetbrains.kotlin.gradle.dsl.JvmTarget

// Motore dei download (Fase 2): coda, ripresa con HTTP Range, verifica SHA, solo HTTPS e siti consentiti.
// Modulo Kotlin puro (nessuna dipendenza da Android): si testa sulla JVM con `./gradlew :download-engine:test`,
// con un piccolo server HTTP locale nei test. Usa l'archivio centrale e le utilità di :core.
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
