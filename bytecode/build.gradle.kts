import org.jetbrains.kotlin.gradle.dsl.JvmTarget

// BLOCKBRIDGE: lettura del bytecode Java delle mod (file .class) e interprete simbolico che segue come la mod registra il suo
// contenuto (oggetti, blocchi, creature, suoni…) e con quali valori. È un'analisi statica: il codice della mod non viene mai
// caricato in una JVM né eseguito. Kotlin puro (si prova sulla JVM).
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
    testImplementation(libs.junit)
}

tasks.test {
    useJUnit()
    testLogging {
        events("passed", "skipped", "failed")
        showStandardStreams = false
    }
}
