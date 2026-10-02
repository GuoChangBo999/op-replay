plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
    id("com.chaquo.python")
}

android {
    namespace = "ai.openpilot.replay"
    compileSdk = 34

    defaultConfig {
        applicationId = "ai.openpilot.replay"
        minSdk = 24
        targetSdk = 34
        versionCode = 1
        versionName = "1.0"

        ndk {
            // Chaquopy native modules: keep arm64 (+ emulator x86_64)
            abiFilters += listOf("arm64-v8a", "x86_64")
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = false
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro"
            )
        }
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions { jvmTarget = "17" }

    // do not compress the schema assets so Python can read them directly
    androidResources {
        noCompress += listOf("capnp")
    }
}

chaquopy {
    defaultConfig {
        version = "3.11"
        pip {
            // Only zstandard is needed now: the capnp parsing is done by a
            // self-contained pure-Python decoder (see app/src/main/python/op_parser.py).
            install("zstandard")
        }
    }
}

dependencies {
    implementation("androidx.core:core-ktx:1.13.1")
    implementation("androidx.appcompat:appcompat:1.7.0")
    implementation("com.google.android.material:material:1.12.0")
}