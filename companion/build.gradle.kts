import java.util.Properties

plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.plugin.compose")
}

// Release signing key (kept out of version control). Falls back to the debug key if missing.
val releaseKeys = Properties().apply {
    rootProject.file("keystore.properties").takeIf { it.exists() }?.inputStream()?.use { load(it) }
}

android {
    signingConfigs {
        if (!releaseKeys.isEmpty) create("release") {
            storeFile = rootProject.file(releaseKeys.getProperty("storeFile"))
            storePassword = releaseKeys.getProperty("storePassword")
            keyAlias = releaseKeys.getProperty("keyAlias")
            keyPassword = releaseKeys.getProperty("keyPassword")
            enableV1Signing = true
            enableV2Signing = true
            enableV3Signing = true
        }
    }

    namespace = "com.ohm.companion"
    compileSdk = 37

    defaultConfig {
        applicationId = "com.ohm.companion"
        minSdk = 26
        targetSdk = 36
        versionCode = 5
        versionName = "0.3.0"
    }
    buildTypes {
        release {
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"))
            signingConfig = signingConfigs.findByName("release") ?: signingConfigs.getByName("debug")
        }
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    buildFeatures { compose = true }
    testOptions { unitTests.isReturnDefaultValues = true }
}

dependencies {
    implementation(platform("androidx.compose:compose-bom:2026.09.00"))
    implementation("androidx.core:core-ktx:1.19.1")
    implementation("androidx.activity:activity-compose:1.13.0")
    implementation("androidx.lifecycle:lifecycle-runtime-compose:2.11.0")
    implementation("androidx.compose.ui:ui")
    implementation("androidx.compose.foundation:foundation")
    implementation("androidx.compose.material3:material3")
    testImplementation("junit:junit:4.13.2")
}
