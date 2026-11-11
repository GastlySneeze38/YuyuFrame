import java.util.Properties

plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
    id("rust")
}

val tauriProperties = Properties().apply {
    val propFile = file("tauri.properties")
    if (propFile.exists()) {
        propFile.inputStream().use { load(it) }
    }
}

android {
    compileSdk = 36
    namespace = "fr.yuyuframe.launcher"
    defaultConfig {
        manifestPlaceholders["usesCleartextTraffic"] = "false"
        applicationId = "fr.yuyuframe.launcher"
        // Android 8.0 : le plancher du moteur Horizon (AAudio), voir tauri.android.conf.json.
        minSdk = 26
        targetSdk = 36
        versionCode = tauriProperties.getProperty("tauri.android.versionCode", "1").toInt()
        versionName = tauriProperties.getProperty("tauri.android.versionName", "1.0")
        // Le moteur Horizon n'existe qu'en 64 bits ARM.
        ndk { abiFilters += "arm64-v8a" }
    }
    // Bibliothèques compressées dans l'APK puis extraites à l'installation :
    // les petits lanceurs Java qui préparent les fichiers du jeu sont des
    // programmes, et Android n'exécute un programme que depuis le dossier des
    // bibliothèques installées.
    packaging {
        jniLibs.useLegacyPackaging = true
    }
    // Clé de publication, donnée par l'environnement (la CI la sort des secrets
    // du dépôt, docs/launcher/android.md § Publier). Sans elle, la version
    // release sort non signée et rien ne change pour un build local. Magasin
    // PKCS12 : un seul mot de passe, pour le magasin et pour la clé.
    val releaseKeystore = System.getenv("YUYU_ANDROID_KEYSTORE")?.takeIf { it.isNotBlank() }
    if (releaseKeystore != null) {
        signingConfigs {
            create("release") {
                storeFile = file(releaseKeystore)
                storePassword = System.getenv("YUYU_ANDROID_KEY_PASSWORD")
                keyAlias = System.getenv("YUYU_ANDROID_KEY_ALIAS")
                keyPassword = System.getenv("YUYU_ANDROID_KEY_PASSWORD")
            }
        }
    }
    buildTypes {
        getByName("debug") {
            manifestPlaceholders["usesCleartextTraffic"] = "true"
            isDebuggable = true
            isJniDebuggable = true
            isMinifyEnabled = false
            packaging {                jniLibs.keepDebugSymbols.add("*/arm64-v8a/*.so")
                jniLibs.keepDebugSymbols.add("*/armeabi-v7a/*.so")
                jniLibs.keepDebugSymbols.add("*/x86/*.so")
                jniLibs.keepDebugSymbols.add("*/x86_64/*.so")
            }
        }
        getByName("release") {
            if (releaseKeystore != null) signingConfig = signingConfigs.getByName("release")
            isMinifyEnabled = true
            proguardFiles(
                *fileTree(".") { include("**/*.pro") }
                    .plus(getDefaultProguardFile("proguard-android-optimize.txt"))
                    .toList().toTypedArray()
            )
        }
    }
    kotlinOptions {
        jvmTarget = "1.8"
    }
    buildFeatures {
        buildConfig = true
    }
}

rust {
    rootDirRel = "../../../"
}

dependencies {
    // Le moteur Horizon et son écran de jeu.
    implementation(project(":starling"))
    implementation("androidx.webkit:webkit:1.14.0")
    implementation("androidx.appcompat:appcompat:1.7.1")
    implementation("androidx.activity:activity-ktx:1.10.1")
    implementation("com.google.android.material:material:1.12.0")
    implementation("androidx.lifecycle:lifecycle-process:2.10.0")
    testImplementation("junit:junit:4.13.2")
    androidTestImplementation("androidx.test.ext:junit:1.1.4")
    androidTestImplementation("androidx.test.espresso:espresso-core:3.5.0")
}

apply(from = "tauri.build.gradle.kts")