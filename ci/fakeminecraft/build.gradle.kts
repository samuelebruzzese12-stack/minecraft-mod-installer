import org.jetbrains.kotlin.gradle.dsl.JvmTarget

// FINTO «Minecraft», usato SOLO nella prova su emulatore in CI. Ha lo stesso nome di pacchetto di Minecraft e accetta lo
// stesso Intent che l'app invia, ma non importa niente: salva il file ricevuto così la prova può controllarne il
// contenuto byte per byte. Non fa parte dell'app consegnata e non viene mai pubblicato nelle Release.
plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.android)
}

android {
    namespace = "com.mojang.minecraftpe"
    compileSdk = 35

    defaultConfig {
        applicationId = "com.mojang.minecraftpe"
        minSdk = 26
        targetSdk = 35
        versionCode = 1
        versionName = "1.21.50.7"
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    lint {
        abortOnError = false
    }
}

kotlin {
    compilerOptions {
        jvmTarget.set(JvmTarget.JVM_17)
    }
}
