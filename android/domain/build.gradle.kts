plugins {
    alias(libs.plugins.kotlin.multiplatform)
    // AGP 9 forbids kotlin-multiplatform together with com.android.library.
    // This plugin is the replacement. See ADR-0015.
    alias(libs.plugins.android.kotlin.multiplatform.library)
    alias(libs.plugins.kotlin.serialization)
}

kotlin {
    // Target 1 - Android. Produces the AAR that :capture, :server and :app use.
    // Note `android { }` inside `kotlin { }`, not a top-level `android { }` block
    // and not `androidTarget()`, which AGP 9 removed (ADR-0015).
    android {
        namespace = "com.scenaristo.camera.domain"
        compileSdk = libs.versions.compileSdk.get().toInt()
        minSdk = libs.versions.minSdk.get().toInt()
    }

    // Target 2 - plain JVM. Its job is to make commonMain genuinely common.
    // With a single target the Kotlin compiler still lets common code reach
    // platform symbols (java.*, JVM-only stdlib); the second target is what
    // turns that enforcement on, which is the whole premise of ADR-0015.
    // It also gives a fast `jvmTest` with no Android host-test machinery.
    jvm()

    jvmToolchain(17)

    sourceSets {
        commonMain.dependencies {
            implementation(libs.kotlinx.serialization.json)
        }
        commonTest.dependencies {
            implementation(kotlin("test"))
        }
    }
}

// The golden protocol fixtures live outside the Gradle tree, in
// docs/protocol/fixtures/, because they are a cross-platform artifact rather
// than an Android one (ADR-0013). Resolve the path at configuration time and
// hand it to the tests, so nothing depends on the test working directory.
val protocolFixtures: String =
    rootProject.layout.projectDirectory.dir("../docs/protocol/fixtures").asFile.absolutePath

tasks.withType<Test>().configureEach {
    systemProperty("scenaristo.protocol.fixtures", protocolFixtures)
}
