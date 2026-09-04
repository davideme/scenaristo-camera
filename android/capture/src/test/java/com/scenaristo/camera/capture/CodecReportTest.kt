package com.scenaristo.camera.capture

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The device query itself needs a device; the verdict it feeds does not, and the
 * verdict is what #21 and ADR-0002 action item 3 actually record.
 */
class CodecReportTest {

    private fun report(profile: String?, hevc: List<CodecReport.Encoder>) =
        CodecReport.Report(
            profileCodec = profile,
            profileResolution = "3840x2160",
            hevcEncoders = hevc,
            h264Encoders = emptyList(),
        )

    private fun hevc(hardware: Boolean) =
        CodecReport.Encoder("c2.test.hevc.encoder", "video/hevc", hardware)

    /** The Pixel 10 case, measured 2026-09-04: hardware HEVC exists, AVC is chosen. */
    @Test
    fun `hardware hevc that the profile did not pick is the finding`() {
        assertTrue(report("video/avc", listOf(hevc(hardware = true))).hevcAvailableButUnused)
    }

    @Test
    fun `a profile already on hevc is not a gap`() {
        assertFalse(report("video/hevc", listOf(hevc(hardware = true))).hevcAvailableButUnused)
    }

    /**
     * Software HEVC is not an alternative: PRD 6.1 wants 4K30 sustained, and a
     * software encoder cannot hold it. Reporting it as an unused option would
     * send #27 after an encoder the device cannot actually use.
     */
    @Test
    fun `software-only hevc is not an unused option`() {
        assertFalse(report("video/avc", listOf(hevc(hardware = false))).hevcAvailableButUnused)
    }

    @Test
    fun `a device with no hevc encoder at all is not a gap`() {
        assertFalse(report("video/avc", emptyList()).hevcAvailableButUnused)
    }

    /** An unreadable profile must not read as "already HEVC". */
    @Test
    fun `an absent profile codec still counts as not hevc`() {
        assertTrue(report(null, listOf(hevc(hardware = true))).hevcAvailableButUnused)
    }

    @Test
    fun `the markdown names the encoder and the verdict`() {
        val text = CodecReport.markdown(report("video/avc", listOf(hevc(hardware = true))))
        assertTrue("video/avc" in text)
        assertTrue("c2.test.hevc.encoder" in text)
        assertTrue("did not choose it" in text)
    }
}
