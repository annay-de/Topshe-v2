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

    // Permanent signing key (from GitHub Secrets) so each new build installs over the last one.
    // Without the secrets, it falls back to a temporary debug key.
    val ksFile = System.getenv("TOPSHE_KEYSTORE")?.let { file(it) }?.takeIf { it.exists() }
    signingConfigs {
        if (ksFile != null) create("topshe") {
            storeFile = ksFile
            storePassword = System.getenv("TOPSHE_KEYSTORE_PASS")
            keyAlias = "topshe"
            keyPassword = System.getenv("TOPSHE_KEYSTORE_PASS")
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = false
            signingConfig = if (ksFile != null) signingConfigs.getByName("topshe") else signingConfigs.getByName("debug")
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
    // Wake word ("Topshe") + voice check (only Annay's voice): open source, no account
    // (sherpa-onnx Kotlin API is vendored in src/main/kotlin/com/k2fsa; native libs come from CI)
    // Unpacks the wake-word model download (.tar.bz2)
    implementation("org.apache.commons:commons-compress:1.26.2")
    // Offline speech-to-text
    implementation("com.alphacephei:vosk-android:0.3.47@aar")
    implementation("net.java.dev.jna:jna:5.13.0@aar")
}
