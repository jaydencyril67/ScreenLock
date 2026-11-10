plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

android {
    namespace = "com.jaydencyril67.screenlock"
    compileSdk = 36

    defaultConfig {
        applicationId = "com.jaydencyril67.screenlock"
        minSdk = 30
        targetSdk = 30
        versionCode = 1
        versionName = "1.0.0"
    }

    buildFeatures { buildConfig = true }

    flavorDimensions += "distribution"
    productFlavors {
        create("sideload") { dimension = "distribution" }
        create("play") { dimension = "distribution" }
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

    lint { disable += "ExpiredTargetSdkVersion" }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    kotlinOptions { jvmTarget = "17" }
}

dependencies {
    implementation(project(":launcher-foundation"))
    implementation("io.github.tribalfs:oneui-design:0.9.4+oneui8")
    implementation("io.github.oneuiproject:icons:1.1.0")
}
