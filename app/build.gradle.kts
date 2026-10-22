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
    // Release signing, read from a keystore.properties that is NOT in the
    // repo (and must never be). Without it the release build still
    // configures and simply stays unsigned, so a checkout with no secrets
    // is not a broken checkout.
    signingConfigs {
        create("release") {
            val props = java.util.Properties()
            val f = rootProject.file("keystore.properties")
            if (f.exists()) {
                f.inputStream().use { props.load(it) }
                storeFile = rootProject.file(props.getProperty("storeFile"))
                storePassword = props.getProperty("storePassword")
                keyAlias = props.getProperty("keyAlias")
                keyPassword = props.getProperty("keyPassword")
            }
        }
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
