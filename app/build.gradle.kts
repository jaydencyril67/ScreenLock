plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
    id("org.jetbrains.kotlin.plugin.compose")
}

// Keep AndroidX aligned with Phantom's AGP 8.11.1 / compileSdk 36 toolchain.
// Prevent transitive dependencies from upgrading to API 37 / AGP 9-only artifacts.
configurations.configureEach {
    // The SESL fork bundles legacy android.support classes that duplicate AndroidX core.
    // Phantom already uses AndroidX core; keep the One UI components without that conflicting fork.
    exclude(group = "sesl.androidx.core", module = "core")
    exclude(group = "sesl.androidx.core", module = "core-ktx")

    resolutionStrategy.force(
        "androidx.core:core:1.16.0",
        "androidx.core:core-ktx:1.16.0",
        "androidx.lifecycle:lifecycle-runtime:2.9.2",
        "androidx.lifecycle:lifecycle-runtime-ktx:2.9.2",
        "androidx.lifecycle:lifecycle-runtime-compose:2.9.2",
        "androidx.lifecycle:lifecycle-runtime-compose-android:2.9.2"
    )
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

    buildFeatures {
        buildConfig = true
        compose = true
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

    // Phantom targets Android 11+; keep the existing distribution baseline.
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

dependencies {
    // Existing Phantom One UI 8 component library and SESL support.
    implementation("io.github.tribalfs:oneui-design:0.9.4+oneui8")
    implementation("io.github.oneuiproject:icons:1.1.0")

    // Compose launcher foundation imported from SysAdminDoc/one-ui-home-clone (MIT).
    implementation("androidx.core:core-ktx:1.16.0")
    implementation("androidx.activity:activity-compose:1.10.1")
    implementation("androidx.lifecycle:lifecycle-runtime-ktx:2.9.2")
    implementation("androidx.lifecycle:lifecycle-runtime-compose:2.9.2")
    implementation("androidx.datastore:datastore-preferences:1.1.7")

    implementation(platform("androidx.compose:compose-bom:2025.12.00"))
    implementation("androidx.compose.ui:ui")
    implementation("androidx.compose.ui:ui-graphics")
    implementation("androidx.compose.ui:ui-tooling-preview")
    implementation("androidx.compose.foundation:foundation")
    implementation("androidx.compose.animation:animation")
    implementation("androidx.compose.material3:material3")
    implementation("androidx.compose.material:material-icons-extended")

    debugImplementation("androidx.compose.ui:ui-tooling")
}
