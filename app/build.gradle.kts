import org.jetbrains.kotlin.gradle.dsl.JvmTarget

plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.android)
    alias(libs.plugins.kotlin.compose)
}

// La chiave di firma è nel progetto ma protetta da una password che NON è nel codice: su GitHub sta nel segreto
// MMI_SIGNING_PASSWORD (vedi README). Senza password l'APK si firma con la chiave di prova (debug).
val signingKeystore = rootProject.file("signing/release.keystore")
val signingPassword: String? = (System.getenv("MMI_SIGNING_PASSWORD") ?: (findProperty("mmiSigningPassword") as String?))?.takeIf { it.isNotBlank() }

android {
    namespace = "com.mmi.modinstaller"
    compileSdk = 35

    defaultConfig {
        applicationId = "com.mmi.modinstaller"
        minSdk = 26
        targetSdk = 35
        // Su GitHub Actions il numero di versione cresce a ogni esecuzione, così gli aggiornamenti si installano.
        versionCode = (System.getenv("GITHUB_RUN_NUMBER") ?: "1").toIntOrNull() ?: 1
        versionName = "1.0.0"
    }

    signingConfigs {
        if (signingKeystore.exists() && signingPassword != null) {
            create("mmi") {
                storeFile = signingKeystore
                storePassword = signingPassword
                keyAlias = "mmi"
                keyPassword = signingPassword
            }
        }
    }

    buildTypes {
        debug {
            signingConfig = signingConfigs.findByName("mmi") ?: signingConfigs.getByName("debug")
        }
        release {
            isMinifyEnabled = false
            isShrinkResources = false
            signingConfig = signingConfigs.findByName("mmi") ?: signingConfigs.getByName("debug")
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    buildFeatures {
        compose = true
        buildConfig = true
    }

    packaging {
        resources {
            excludes += setOf(
                "/META-INF/{AL2.0,LGPL2.1}",
                "/META-INF/LICENSE*",
                "/META-INF/NOTICE*",
                "/META-INF/DEPENDENCIES",
                "/META-INF/INDEX.LIST",
                "/META-INF/versions/**",
                "/META-INF/maven/**"
            )
        }
    }

    lint {
        abortOnError = false
        checkReleaseBuilds = false
    }
}

kotlin {
    compilerOptions {
        jvmTarget.set(JvmTarget.JVM_17)
    }
}

dependencies {
    implementation(project(":core"))
    implementation(project(":download-engine"))
    implementation(project(":jar-analyzer"))
    implementation(project(":mod-manager"))
    implementation(project(":catalog"))
    implementation(project(":modpack-engine"))
    implementation(project(":world-engine"))
    implementation(project(":mod-converter"))

    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.activity.compose)
    implementation(libs.androidx.lifecycle.viewmodel.ktx)
    implementation(libs.androidx.lifecycle.runtime.ktx)
    implementation(libs.kotlinx.coroutines.android)

    implementation(platform(libs.androidx.compose.bom))
    implementation(libs.androidx.compose.ui)
    implementation(libs.androidx.compose.foundation)
    implementation(libs.androidx.compose.material3)
    implementation(libs.androidx.compose.material.icons.core)

    // Lettura di 7z e RAR (RAR4: la libreria non legge il formato RAR5, l'app lo segnala).
    implementation(libs.commons.compress)
    implementation(libs.xz)
    implementation(libs.junrar)
    implementation(libs.slf4j.nop)

    testImplementation(libs.junit)
}
