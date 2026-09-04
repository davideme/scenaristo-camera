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

// ADR-0009: the web UI ships as one static bundle inside the app, so the remote
// is genuinely zero-install -- the laptop types an IP and gets the page, with
// nothing to download. The bundle is built by `pnpm run build` (ADR-0014) and
// added to this module's Java resources, where Ktor serves it from the
// classpath.
//
// Wired through AGP 9's androidComponents Sources API rather than the old
// sourceSets DSL, which AGP 9 removed -- the same API the ROADMAP names for this
// job.
abstract class SyncWebBundle : DefaultTask() {
    @get:InputDirectory
    @get:Optional
    @get:PathSensitive(PathSensitivity.RELATIVE)
    abstract val bundle: DirectoryProperty

    @get:OutputDirectory
    abstract val destination: DirectoryProperty

    @TaskAction
    fun sync() {
        val target = destination.get().asFile.resolve("web")
        target.deleteRecursively()
        target.mkdirs()
        val source = bundle.orNull?.asFile
        if (source == null || !source.isDirectory) {
            // Not a build failure: a device build without the web bundle is a
            // valid thing to want. The server answers 404 for the UI and the
            // control socket still works, which is a legible failure.
            logger.lifecycle("web/dist not built; the phone will serve no UI")
            return
        }
        source.copyRecursively(target, overwrite = true)
    }
}

val syncWebBundle by tasks.registering(SyncWebBundle::class) {
    description = "Copies web/dist into :server resources so the phone can serve the UI."
    bundle.set(rootProject.layout.projectDirectory.dir("../web/dist"))
    destination.set(layout.buildDirectory.dir("generated/webResources"))
}

androidComponents {
    onVariants { variant ->
        variant.sources.resources?.addGeneratedSourceDirectory(syncWebBundle, SyncWebBundle::destination)
    }
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
