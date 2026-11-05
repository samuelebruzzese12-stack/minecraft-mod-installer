import org.jetbrains.kotlin.gradle.dsl.JvmTarget

// Catalogo online (Fase 3d): ricerca su Modrinth e scelta dei file da scaricare, con le loro dipendenze. Kotlin puro
// (si prova sulla JVM). Non scarica i file (lo fa :download-engine) e non esegue mai codice delle mod.
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
    // Le prove di catena completa (ricerca → download con verifica → lettura del .jar → controllo sul profilo) usano
    // i motori veri degli altri moduli.
    testImplementation(project(":download-engine"))
    testImplementation(project(":jar-analyzer"))
    testImplementation(project(":mod-manager"))
}

tasks.test {
    useJUnit()
    testLogging {
        events("passed", "skipped", "failed")
        showStandardStreams = false
    }
}
