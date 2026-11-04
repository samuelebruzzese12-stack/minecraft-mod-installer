import org.jetbrains.kotlin.gradle.dsl.JvmTarget

// Modulo Kotlin puro (nessuna dipendenza da Android): analisi, sicurezza, preparazione pacchetti, backup.
// Si testa sulla JVM con `./gradlew :core:test`.
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
        // Il modulo gira anche su Android 8 (API 26): in compilazione sono visibili solo le API di Java 8,
        // così non si può usare per errore un metodo che sul telefono non esiste (es. InputStream.readAllBytes).
        freeCompilerArgs.add("-Xjdk-release=8")
    }
}

dependencies {
    testImplementation(libs.junit)
}

tasks.test {
    useJUnit()
    testLogging {
        events("passed", "skipped", "failed")
        showStandardStreams = false
    }
}
