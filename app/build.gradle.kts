plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

android {
    namespace = "com.dawnpatrol.game"
    compileSdk = 34

    defaultConfig {
        applicationId = "com.dawnpatrol.game"
        minSdk = 26
        targetSdk = 34
        versionCode = 1
        versionName = "0.1"
    }

    buildTypes {
        release {
            isMinifyEnabled = false
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    kotlinOptions {
        jvmTarget = "17"
    }

    // No XML layouts beyond the manifest's activity declaration, no image
    // or audio assets - see README.md for why.
    buildFeatures {
        viewBinding = false
    }
}

// No dependencies - single Activity + single custom View, everything else
// (sprites, sound, world generation) is plain Kotlin/Android SDK.
dependencies {
}
