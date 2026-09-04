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
    // The protocol classes are :domain's, but their Json configuration
    // (ProtocolJson) is part of the contract, so :server needs the same library
    // rather than a second encoder. Already in the catalogue; no new decision.
    implementation(libs.kotlinx.serialization.json)
    implementation(libs.androidx.lifecycle.service)
    implementation(libs.zxing.core)

    testImplementation(libs.junit)
}
