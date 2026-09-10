package com.scenaristo.camera

import android.content.Context
import android.hardware.camera2.CameraCharacteristics
import android.hardware.camera2.CameraManager
import android.os.Build
import android.util.Log
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Reads the API 36 capability keys ADR-0033 and ADR-0034 gate on, **through
 * `CameraCharacteristics` rather than through `dumpsys`**.
 *
 * The first reading of these keys (2026-09-09) came from
 * `adb shell dumpsys media.camera`, which prints the camera service's static
 * HAL metadata. Both headline results were *absences* — no
 * `SENSOR_EXPOSURE_TIME_PRIORITY`, no `CCT` — and an absence in the HAL dump is
 * weaker evidence than a presence, because the framework builds an app's
 * characteristics from that metadata and may add, derive or filter keys on the
 * way. This test closes that gap by asking the question the app itself asks.
 *
 * It reports two different things per key, which are not the same question:
 * whether the key appears in [CameraCharacteristics.getKeys], and what
 * [CameraCharacteristics.get] returns. A key absent from `getKeys()` is the
 * device saying it does not support the feature; a key present but null would be
 * a driver bug worth knowing about separately.
 *
 * It asserts nothing. It is a measurement instrument, in the sense ADR-0016 and
 * `docs/ROADMAP.md` use the word: its output is a recorded number, and the
 * number belongs in the ADR that asked for it. CI never runs it — the workflow
 * runs `test`, `lint` and `assembleDebug`, and nothing in CI may call `adb`
 * (ADR-0016).
 *
 * Run it with a phone attached:
 *
 * ```
 * ./gradlew :app:connectedDebugAndroidTest
 * adb logcat -d -s CameraCapabilityRead
 * ```
 *
 * Whatever it prints is a claim about one handset (ADR-0017), and the model is
 * printed first so the result cannot be written down against the wrong phone.
 */
@RunWith(AndroidJUnit4::class)
class CameraCapabilityReadTest {

    @Test
    fun reportCapabilityKeys() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val manager = context.getSystemService(Context.CAMERA_SERVICE) as CameraManager

        line("=== device ===")
        line("model=${Build.MODEL} device=${Build.DEVICE} release=${Build.VERSION.RELEASE} sdk=${Build.VERSION.SDK_INT} build=${Build.ID}")

        manager.cameraIdList.forEach { id ->
            report(manager, id, logical = true)
            manager.getCameraCharacteristics(id).physicalCameraIds.forEach { physical ->
                report(manager, physical, logical = false)
            }
        }
    }

    private fun report(manager: CameraManager, id: String, logical: Boolean) {
        val c = manager.getCameraCharacteristics(id)
        line("=== camera $id (${if (logical) "logical" else "physical"}) ===")

        // The two keys the ADRs gate on, each asked twice: does the app see the
        // key at all, and what does it read back.
        key(c, "CONTROL_AE_AVAILABLE_PRIORITY_MODES", CameraCharacteristics.CONTROL_AE_AVAILABLE_PRIORITY_MODES) {
            it.joinToString(prefix = "[", postfix = "]")
        }
        key(c, "COLOR_CORRECTION_AVAILABLE_MODES", CameraCharacteristics.COLOR_CORRECTION_AVAILABLE_MODES) {
            it.joinToString(prefix = "[", postfix = "]")
        }
        key(c, "COLOR_CORRECTION_COLOR_TEMPERATURE_RANGE", CameraCharacteristics.COLOR_CORRECTION_COLOR_TEMPERATURE_RANGE) {
            it.toString()
        }

        // Supporting reads the same ADRs and issue #151 depend on.
        key(c, "CONTROL_MAX_REGIONS_AE", CameraCharacteristics.CONTROL_MAX_REGIONS_AE) { it.toString() }
        key(c, "CONTROL_MAX_REGIONS_AF", CameraCharacteristics.CONTROL_MAX_REGIONS_AF) { it.toString() }
        key(c, "CONTROL_AE_LOCK_AVAILABLE", CameraCharacteristics.CONTROL_AE_LOCK_AVAILABLE) { it.toString() }
        key(c, "CONTROL_AUTOFRAMING_AVAILABLE", CameraCharacteristics.CONTROL_AUTOFRAMING_AVAILABLE) { it.toString() }
        key(c, "REQUEST_AVAILABLE_CAPABILITIES", CameraCharacteristics.REQUEST_AVAILABLE_CAPABILITIES) {
            it.joinToString(prefix = "[", postfix = "]")
        }
    }

    /**
     * Prints `inKeys` and `value` separately, because "the device does not
     * support this" and "the driver declared the key and returned nothing" are
     * different findings and only the first is expected.
     */
    private fun <T> key(
        c: CameraCharacteristics,
        name: String,
        k: CameraCharacteristics.Key<T>,
        show: (T) -> String,
    ) {
        val inKeys = c.keys.any { it.name == k.name }
        val value = runCatching { c.get(k) }.getOrNull()
        line("$name (${k.name}) inKeys=$inKeys value=${value?.let(show) ?: "null"}")
    }

    private fun line(s: String) {
        Log.i(TAG, s)
        println(s)
    }

    private companion object {
        const val TAG = "CameraCapabilityRead"
    }
}
