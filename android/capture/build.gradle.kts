plugins {
    // Kotlin is built into AGP 9; there is no kotlin-android plugin.
    alias(libs.plugins.android.library)
}

android {
    namespace = "com.scenaristo.camera.capture"
    compileSdk = libs.versions.compileSdk.get().toInt()

    defaultConfig {
        minSdk = libs.versions.minSdk.get().toInt()
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    buildFeatures {
        buildConfig = false
        aidl = false
        shaders = false
    }
}

kotlin {
    jvmToolchain(17)
}

dependencies {
    api(project(":domain"))

    // CameraX pinned at 1.6.2 by ADR-0002.
    implementation(libs.androidx.camera.core)
    implementation(libs.androidx.camera.camera2)
    implementation(libs.androidx.camera.lifecycle)
    implementation(libs.androidx.camera.video)
    implementation(libs.kotlinx.coroutines.android)

    testImplementation(libs.junit)
}
