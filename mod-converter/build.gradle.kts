import org.jetbrains.kotlin.gradle.dsl.JvmTarget

// JAVA TO BEDROCK (Fase 7): legge le mod Java (.jar) senza eseguirle e ne porta in un add-on Bedrock (.mcaddon) il contenuto
// che Bedrock sa rappresentare: oggetti, blocchi, texture, nomi, ricette, bottini dei blocchi, minerali, armature e, quando la
// mod li ha in formato GeckoLib, i modelli delle creature. Il comportamento scritto in codice Java non si converte e il
// rapporto lo dice mod per mod. Kotlin puro (si prova sulla JVM).
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
    // I test che leggono le mod vere (MMI_REAL_MODS) e quelli con i file della prova su emulatore (MMI_SAMPLES).
    environment("MMI_REAL_MODS", System.getenv("MMI_REAL_MODS") ?: "")
    testLogging {
        events("passed", "skipped", "failed")
        showStandardStreams = false
    }
}
