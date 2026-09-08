package com.scenaristo.camera.domain

import com.scenaristo.camera.domain.exposure.GridFrequency
import com.scenaristo.camera.domain.protocol.CaptureSettings
import com.scenaristo.camera.domain.protocol.StudioLook
import com.scenaristo.camera.domain.protocol.SettingsPatch
import kotlinx.serialization.ExperimentalSerializationApi
import kotlinx.serialization.serializer
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals

/**
 * `CaptureSettings.settable` must stay exactly the fields of [SettingsPatch]
 * (ADR-0024).
 *
 * The guard counts changes to the things a client can ask for. A patch field
 * missing from `settable` is a field whose change does not move the guard, so a
 * stale tab could silently undo it — the exact failure the guard exists to
 * prevent, reintroduced by an addition nobody thought about. This test makes
 * that a build failure instead.
 *
 * It reads the serializer's own descriptor rather than a hand-written list,
 * because a hand-written list is the thing being checked.
 */
@OptIn(ExperimentalSerializationApi::class)
class SettableSettingsTest {

    /**
     * Kept in step by hand, deliberately: this is the human-readable half of the
     * assertion, and the descriptor is the half that cannot be forgotten. If
     * they disagree, one of them is a change somebody has to think about.
     */
    private val expected = listOf(
        "grid",
        "whiteBalanceKelvin",
        "lensId",
        "saveToGallery",
        "shutterLock",
        "lockExposureWhileRecording",
        "zoomRatio",
        "studioLook",
    )

    @Test
    fun `settable covers exactly the patchable fields`() {
        val descriptor = serializer<SettingsPatch>().descriptor
        val patchFields = (0 until descriptor.elementsCount).map { descriptor.getElementName(it) }

        assertEquals(
            expected.sorted(),
            patchFields.sorted(),
            "SettingsPatch gained or lost a field. Add it to CaptureSettings.settable " +
                "and to this list, or a change to it will not move settingsRev and a " +
                "stale client will be able to undo it (ADR-0024).",
        )
        assertEquals(expected.size, base().settable.size, "settable must have one entry per patch field")
    }

    /** Each patchable field, changed on its own, has to move `settable`. */
    @Test
    fun `every patchable field is actually observed`() {
        val base = base()
        assertNotEquals(base.settable, base.copy(grid = GridFrequency.HZ_60).settable)
        assertNotEquals(base.settable, base.copy(whiteBalanceKelvin = 6500).settable)
        assertNotEquals(base.settable, base.copy(lensId = "1").settable)
        assertNotEquals(base.settable, base.copy(saveToGallery = true).settable)
        assertNotEquals(base.settable, base.copy(shutterLock = 100).settable)
        assertNotEquals(
            base.settable,
            base.copy(lockExposureWhileRecording = true).settable,
            "ADR-0023's mode is a user choice, so a stale tab must not undo it",
        )
        assertNotEquals(base.settable, base.copy(zoomRatio = 5.0).settable)
        assertNotEquals(
            base.settable,
            base.copy(studioLook = StudioLook.REMBRANDT).settable,
            "choosing a look did not count as a settings change (ADR-0024)",
        )
    }

    /**
     * And the loop's own outputs must not. This is the assertion that would have
     * caught the first version of ADR-0024's fix, which compared the whole of
     * `CaptureSettings` and so moved the guard on every ISO step.
     */
    @Test
    fun `the exposure loop's outputs do not move it`() {
        val base = base()
        assertEquals(base.settable, base.copy(iso = 3200).settable, "ISO is an output (ADR-0005)")
        assertEquals(base.settable, base.copy(shutterHz = 100).settable, "so is the shutter in use")
    }

    private fun base() = CaptureSettings(
        grid = GridFrequency.HZ_50,
        shutterHz = 50,
        iso = 100,
        whiteBalanceKelvin = 5600,
        lensId = "0",
    )
}
