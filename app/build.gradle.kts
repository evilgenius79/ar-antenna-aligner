plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.plugin.compose")
}

android {
    namespace = "online.rushcounty.aligner"
    compileSdk = 37

    defaultConfig {
        applicationId = "online.rushcounty.aligner"
        minSdk = 26
        targetSdk = 36
        versionCode = 8
        versionName = "0.6.0"
    }

    buildTypes {
        release {
            isMinifyEnabled = false
            // Sideload-only hobby app: sign release with the debug key so it installs directly.
            signingConfig = signingConfigs.getByName("debug")
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    buildFeatures {
        compose = true
    }
}

dependencies {
    val composeBom = platform("androidx.compose:compose-bom:2026.09.00")
    implementation(composeBom)
    implementation("androidx.compose.ui:ui")
    implementation("androidx.compose.foundation:foundation")
    implementation("androidx.compose.material3:material3")
    implementation("androidx.activity:activity-compose:1.13.0")
    implementation("androidx.core:core-ktx:1.19.1")
    implementation("androidx.lifecycle:lifecycle-viewmodel-compose:2.11.0")
    implementation("androidx.lifecycle:lifecycle-runtime-compose:2.11.0")

    val camerax = "1.6.2"
    implementation("androidx.camera:camera-camera2:$camerax")
    implementation("androidx.camera:camera-lifecycle:$camerax")
    implementation("androidx.camera:camera-view:$camerax")

    implementation("com.google.android.gms:play-services-location:21.4.0")
    // play-services drags in an old fragment; pin a current one (ActivityResult API needs >= 1.3).
    implementation("androidx.fragment:fragment:1.9.1")

    testImplementation("junit:junit:4.13.2")
    // Real org.json for JVM tests (android.jar only has stubs).
    testImplementation("org.json:json:20260814")
}
