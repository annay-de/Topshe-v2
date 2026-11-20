plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

android {
    namespace = "org.annay.topshe"
    compileSdk = 34

    defaultConfig {
        applicationId = "org.annay.topshe.v2"
        minSdk = 28
        targetSdk = 34
        versionCode = (System.getenv("GITHUB_RUN_NUMBER") ?: "1").toInt()
        versionName = "2.0." + (System.getenv("GITHUB_RUN_NUMBER") ?: "0")
        ndk { abiFilters += listOf("arm64-v8a") }
        externalNativeBuild {
            cmake {
                arguments += listOf("-DCMAKE_BUILD_TYPE=Release")
            }
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = false
            // Personal sideloaded app: sign with the standard debug key so the APK installs directly
            signingConfig = signingConfigs.getByName("debug")
        }
    }

    externalNativeBuild {
        cmake {
            path = file("src/main/cpp/CMakeLists.txt")
            version = "3.22.1"
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions { jvmTarget = "17" }

    packaging {
        jniLibs {
            pickFirsts += listOf("**/libc++_shared.so", "**/libjnidispatch.so")
        }
    }
}

dependencies {
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.8.1")
    // Wake word ("Topshe") and speaker check (only Annay's voice)
    implementation("ai.picovoice:porcupine-android:3.+")
    implementation("ai.picovoice:eagle-android:+")
    // Offline speech-to-text
    implementation("com.alphacephei:vosk-android:0.3.47@aar")
    implementation("net.java.dev.jna:jna:5.13.0@aar")
}
