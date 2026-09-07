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
//
// The half that was missing until now is the one that *builds* `web/dist`.
// Copying it when it happened to exist meant CI, a fresh clone, and anyone who
// had not run pnpm by hand all produced an APK that served no UI at all, and
// said so only in a lifecycle log nobody reads. That is the shape of failure
// this repository has already paid for once (#56): everything green, the
// feature absent. So pnpm is now on the critical path of the Android build,
// which is exactly the cost ADR-0014 deferred to Phase 2 and no longer.

/** Where `web/` lives, resolved at configuration time: no Project access at execution (ADR-0014). */
val webDir: Directory = rootProject.layout.projectDirectory.dir("../web")

/**
 * Which pnpm to run.
 *
 * Overridable through `scenaristo.pnpm` because a Gradle daemon does not always
 * inherit a shell's PATH -- an IDE-launched one usually does not -- and
 * "pnpm: command not found" from inside a Gradle task is a worse error message
 * than a wrong path in `gradle.properties`.
 */
val pnpm: String = providers.gradleProperty("scenaristo.pnpm").getOrElse("pnpm")

val installWebDependencies by tasks.registering(Exec::class) {
    description = "Installs web/ dependencies with pnpm (ADR-0014)."
    workingDir = webDir.asFile
    // --frozen-lockfile is pnpm's `npm ci`: it fails rather than silently
    // rewriting pnpm-lock.yaml when package.json has drifted from it.
    commandLine(pnpm, "install", "--frozen-lockfile")
    inputs.file(webDir.file("package.json"))
    inputs.file(webDir.file("pnpm-lock.yaml"))
    // The marker pnpm writes, rather than `node_modules` itself: declaring a
    // directory of tens of thousands of files as an output would make the
    // up-to-date check cost more than the install it is avoiding.
    outputs.file(webDir.file("node_modules/.modules.yaml"))
}

val buildWebBundle by tasks.registering(Exec::class) {
    description = "Builds the static web bundle into web/dist (ADR-0009)."
    dependsOn(installWebDependencies)
    workingDir = webDir.asFile
    commandLine(pnpm, "run", "build")
    // `pnpm run build` is `tsc -b && vite build`, so a type error in the remote
    // control now fails the Android build. That is intended: the bundle is part
    // of the app, and an app whose UI does not compile is not a working app.
    inputs.dir(webDir.dir("src")).withPathSensitivity(PathSensitivity.RELATIVE)
    inputs.files(
        webDir.file("index.html"),
        webDir.file("package.json"),
        webDir.file("pnpm-lock.yaml"),
        webDir.file("vite.config.ts"),
        webDir.file("tsconfig.json"),
        webDir.file("tsconfig.app.json"),
        webDir.file("tsconfig.node.json"),
    )
    outputs.dir(webDir.dir("dist"))
    outputs.cacheIf { true }
}
/**
 * Puts the bundle under a `web/` directory, which is the resource path
 * `ControlServer`'s `staticResources("/", "web")` reads from.
 *
 * The prefix is the whole reason this is a task rather than a source directory
 * pointed straight at `web/dist`: a resources source directory contributes its
 * contents at the classpath root, so the bundle's `index.html` would sit beside
 * `META-INF/`, one common name away from colliding with a dependency.
 */
abstract class SyncWebBundle : DefaultTask() {
    @get:InputDirectory
    @get:PathSensitive(PathSensitivity.RELATIVE)
    abstract val bundle: DirectoryProperty

    @get:OutputDirectory
    abstract val destination: DirectoryProperty

    @get:Inject
    abstract val fs: FileSystemOperations

    @TaskAction
    fun sync() {
        // Sync, not copy: a file dropped from the bundle has to disappear from
        // the APK too, or the phone keeps serving something the source no longer
        // contains -- the silent staleness ADR-0009 exists to prevent.
        fs.sync {
            from(bundle)
            into(destination.dir("web"))
        }
    }
}

androidComponents {
    onVariants { variant ->
        // One task per variant, each with the output directory AGP assigns it.
        // Sharing a single task between debug and release registers one
        // directory in two variants, and AGP merges it twice into each: "more
        // than one file was found with OS independent path 'web/index.html'",
        // which it warns will become an error. `buildWebBundle` is still shared,
        // so pnpm runs once however many variants ask for the bundle.
        val name = variant.name.replaceFirstChar { it.uppercase() }
        val sync = tasks.register<SyncWebBundle>("sync${name}WebBundle") {
            description = "Copies web/dist into :server $name resources so the phone can serve the UI."
            bundle.set(buildWebBundle.map { webDir.dir("dist") })
        }
        variant.sources.resources?.addGeneratedSourceDirectory(sync, SyncWebBundle::destination)
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
