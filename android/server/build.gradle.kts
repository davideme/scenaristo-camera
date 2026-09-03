plugins {
    alias(libs.plugins.android.library)
}

android {
    namespace = "com.scenaristo.camera.server"
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

    packaging {
        resources {
            excludes += "/META-INF/{AL2.0,LGPL2.1}"
        }
    }
}

kotlin {
    jvmToolchain(17)
}

dependencies {
    api(project(":domain"))

    // Ktor CIO, plain HTTP, LAN-bound (ADR-0006); one WebSocket (ADR-0007).
    implementation(libs.ktor.server.core)
    implementation(libs.ktor.server.cio)
    implementation(libs.ktor.server.websockets)
    implementation(libs.androidx.lifecycle.service)
    implementation(libs.zxing.core)

    testImplementation(libs.junit)
}
