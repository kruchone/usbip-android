plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
    id("org.jetbrains.kotlin.plugin.compose")
}

android {
    namespace = "dev.zk.usbip"
    compileSdk = 35

    defaultConfig {
        applicationId = "dev.zk.usbip"
        minSdk = 26
        targetSdk = 35
        versionCode = 2
        versionName = "0.1.0-beta.2"
    }

    // Release key lives outside the repo (see ~/.gradle/gradle.properties: USBIP_KEYSTORE, USBIP_KEYSTORE_PASSWORD,
    // USBIP_KEY_ALIAS). Without it, release builds fall back to the debug key.
    val keystore = providers.gradleProperty("USBIP_KEYSTORE").orNull
    signingConfigs {
        if (keystore != null) {
            create("release") {
                storeFile = file(keystore)
                storePassword = providers.gradleProperty("USBIP_KEYSTORE_PASSWORD").get()
                keyAlias = providers.gradleProperty("USBIP_KEY_ALIAS").getOrElse("usbip")
                keyPassword = providers.gradleProperty("USBIP_KEYSTORE_PASSWORD").get()
            }
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = false
            signingConfig = signingConfigs.findByName("release") ?: signingConfigs.getByName("debug")
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions {
        jvmTarget = "17"
    }
    buildFeatures {
        compose = true
    }
}

dependencies {
    implementation(project(":protocol"))

    implementation("androidx.core:core-ktx:1.15.0")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.9.0")
    implementation("androidx.activity:activity-compose:1.9.3")
    implementation("androidx.lifecycle:lifecycle-runtime-compose:2.8.7")
    implementation(platform("androidx.compose:compose-bom:2024.12.01"))
    implementation("androidx.compose.ui:ui")
    implementation("androidx.compose.foundation:foundation")
    implementation("androidx.compose.material3:material3")
}
