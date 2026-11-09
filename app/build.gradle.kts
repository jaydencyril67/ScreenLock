plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

android {
    namespace = "com.jaydencyril67.screenlock"
    compileSdk = 35

    defaultConfig {
        applicationId = "com.jaydencyril67.screenlock"
        minSdk = 30
        targetSdk = 30
        versionCode = 1
        versionName = "1.0.0"
    }

    buildFeatures {
        buildConfig = true
    }

    flavorDimensions += "distribution"

    productFlavors {
        create("sideload") {
            dimension = "distribution"
        }
        create("play") {
            dimension = "distribution"
        }
    }

    signingConfigs {
        create("release") {
            storeFile = file(providers.gradleProperty("PHANTOM_KEYSTORE_FILE").orElse("missing.keystore").get())
            storePassword = providers.gradleProperty("PHANTOM_KEYSTORE_PASSWORD").orElse("").get()
            keyAlias = providers.gradleProperty("PHANTOM_KEY_ALIAS").orElse("").get()
            keyPassword = providers.gradleProperty("PHANTOM_KEY_PASSWORD").orElse("").get()
        }
    }

    buildTypes {
        debug {
            applicationIdSuffix = ".debug"
            versionNameSuffix = "-debug"
        }
        release {
            isMinifyEnabled = false
            signingConfig = signingConfigs.getByName("release")
        }
    }

    // This is a sideloaded Android 11+ utility, not a Google Play submission.
    // Keep targetSdk 30 for now and suppress only Play's expired-target lint check.
    lint {
        disable += "ExpiredTargetSdkVersion"
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    kotlinOptions {
        jvmTarget = "17"
    }
}
