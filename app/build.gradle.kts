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

    namespace = "com.ohm.launcher"
    compileSdk = 37

    defaultConfig {
        applicationId = "com.ohm.launcher"
        minSdk = 29
        targetSdk = 36
        versionCode = 14
        versionName = "0.6.1"
        // Head units are ARM; x86_64 is only for the PC emulator.
        ndk { abiFilters += listOf("arm64-v8a", "armeabi-v7a", "x86_64") }
    }

    buildTypes {
        release {
            // Shrunk build for the head unit, signed with the project's own release key.
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
            signingConfig = signingConfigs.findByName("release") ?: signingConfigs.getByName("debug")
        }
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    buildFeatures {
        compose = true
    }
    testOptions { unitTests.isReturnDefaultValues = true }
    // librespot ships as an executable named lib*.so; it must be extracted to nativeLibraryDir to run.
    packaging { jniLibs { useLegacyPackaging = true } }
}

dependencies {
    val bom = platform("androidx.compose:compose-bom:2026.09.00")
    implementation(bom)
    implementation("androidx.core:core-ktx:1.19.1")
    implementation("androidx.activity:activity-compose:1.13.0")
    implementation("androidx.lifecycle:lifecycle-runtime-compose:2.11.0")
    implementation("androidx.compose.ui:ui")
    implementation("androidx.compose.foundation:foundation")
    implementation("androidx.compose.material3:material3")
    implementation("androidx.compose.material:material-icons-extended:1.7.8")
    implementation("org.maplibre.gl:android-sdk:13.7.1")
    // Spotify integration: HTTP, images, sign-in tab
    implementation("com.squareup.okhttp3:okhttp:5.5.1")
    implementation("io.coil-kt.coil3:coil-compose:3.6.3")
    implementation("io.coil-kt.coil3:coil-network-okhttp:3.6.3")
    implementation("androidx.browser:browser:1.10.0")
    testImplementation("junit:junit:4.13.2")
    testImplementation("org.json:json:20240303")
}
