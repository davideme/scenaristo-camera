package com.scenaristo.camera.capture

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Verdict rules for issue #20 (ADR-0002 action item 2). Everything here runs on
 * the host; what needs the Pixel 10 is producing the [KeyEcho] values, not
 * judging them.
 */
class ManualKeyEchoTest {

    private val oneFiftieth = 20_000_000L // 1/50 s in nanoseconds
    private val thirtyFps = 33_333_333L // 1/30 s in nanoseconds
    private val off = 0L // CONTROL_AE_MODE_OFF, CONTROL_AWB_MODE_OFF, OIS OFF

    // ADR-0002 action 2: the requested value echoing back is the pass condition.
    @Test
    fun `an exact echo is honoured`() {
        val echo = KeyEcho(ManualKey.SENSOR_EXPOSURE_TIME, oneFiftieth, oneFiftieth)
        assertEquals(EchoVerdict.EXACT, echo.verdict)
        assertEquals(0.0, echo.deviation!!, 0.0)
    }

    // PRD 6.2: what the criterion protects is a band-free frame, not an integer.
    // 200 us off 1/50 s is 2 % of a 50 Hz half-cycle, so the sensor rounding to
    // its own step still satisfies the product promise.
    @Test
    fun `PRD 6_2 - sensor quantisation inside the flicker margin still counts as honoured`() {
        val quantised = KeyEcho(ManualKey.SENSOR_EXPOSURE_TIME, oneFiftieth, oneFiftieth + 150_000L)
        assertEquals(EchoVerdict.QUANTISED, quantised.verdict)

        val tooFar = KeyEcho(ManualKey.SENSOR_EXPOSURE_TIME, oneFiftieth, oneFiftieth + 400_000L)
        assertEquals(EchoVerdict.MISMATCH, tooFar.verdict)
    }

    // PRD 6.1: "metadata shows 3840x2160 at 30.00 fps constant". A 1 % drift on
    // frame duration reads as 29.7 fps, so this key is held tighter than exposure.
    @Test
    fun `PRD 6_1 - frame duration is held to 30_00 fps, not to one percent`() {
        val oneTenthOfAPercent = KeyEcho(ManualKey.SENSOR_FRAME_DURATION, thirtyFps, thirtyFps + 20_000L)
        assertEquals(EchoVerdict.QUANTISED, oneTenthOfAPercent.verdict)

        // The same absolute drift that passes for exposure time fails here.
        val onePercent = KeyEcho(ManualKey.SENSOR_FRAME_DURATION, thirtyFps, thirtyFps + 333_333L)
        assertEquals(EchoVerdict.MISMATCH, onePercent.verdict)
    }

    // PRD 6.3 / 6.4: a mode is an enum. "Nearly OFF" is not a state a camera has,
    // so no tolerance may apply to these keys.
    @Test
    fun `PRD 6_3 - mode keys admit no tolerance`() {
        assertEquals(EchoVerdict.EXACT, KeyEcho(ManualKey.CONTROL_AE_MODE, off, off).verdict)
        // 1 = CONTROL_AE_MODE_ON: the device took exposure back.
        assertEquals(EchoVerdict.MISMATCH, KeyEcho(ManualKey.CONTROL_AE_MODE, off, 1L).verdict)
        assertEquals(EchoVerdict.MISMATCH, KeyEcho(ManualKey.CONTROL_AWB_MODE, off, 1L).verdict)
        assertTrue(ManualKey.entries.filter { it.isMode }.all { it.tolerance == null })
    }

    // A key the capture result never carried is a different failure from a key
    // the camera overrode: the request probably never reached the sensor.
    @Test
    fun `a key missing from the capture result is ABSENT, not MISMATCH`() {
        val echo = KeyEcho(ManualKey.SENSOR_SENSITIVITY, 100L, null)
        assertEquals(EchoVerdict.ABSENT, echo.verdict)
        assertEquals(null, echo.deviation)
    }

    @Test
    fun `a lens passes only when every key ADR-0002 lists was honoured`() {
        val report = LensEchoReport("0", "Rear main (wide)", allKeysHonoured())
        assertTrue(report.honoured)
        assertTrue(report.failures.isEmpty())
        assertTrue(report.missingKeys.isEmpty())
    }

    // The trap this guards: a run that only looked at three keys reporting green.
    @Test
    fun `a run that skipped a key is not a pass`() {
        val partial = allKeysHonoured().filterNot { it.key == ManualKey.LENS_OPTICAL_STABILIZATION_MODE }
        val report = LensEchoReport("0", "Rear main (wide)", partial)
        assertFalse(report.honoured)
        assertEquals(listOf(ManualKey.LENS_OPTICAL_STABILIZATION_MODE), report.missingKeys)
        // Nothing measured failed; the report still must not read as a pass.
        assertTrue(report.failures.isEmpty())
    }

    // ADR-0017: a Pixel is the most permissive device in the fleet, so a failure
    // here is the informative outcome and has to survive into the write-up.
    @Test
    fun `the markdown paste names the failing key and its deviation`() {
        val echoes = allKeysHonoured().map {
            if (it.key == ManualKey.SENSOR_EXPOSURE_TIME) {
                KeyEcho(it.key, oneFiftieth, oneFiftieth + 2_000_000L) // +10 %
            } else {
                it
            }
        }
        val md = LensEchoReport("0", "Rear main (wide)", echoes).markdown()

        assertTrue(md.contains("**not honoured**"))
        assertTrue(md.contains("SENSOR_EXPOSURE_TIME"))
        assertTrue(md.contains("MISMATCH"))
        assertTrue(md.contains("+100000 ppm"))
    }

    @Test
    fun `the markdown paste marks an unmeasured key rather than omitting it`() {
        val partial = allKeysHonoured().filterNot { it.key == ManualKey.SENSOR_FRAME_DURATION }
        val md = LensEchoReport("0", "Rear main (wide)", partial).markdown()
        assertTrue(md.contains("SENSOR_FRAME_DURATION"))
        assertTrue(md.contains("NOT MEASURED"))
    }

    private fun allKeysHonoured(): List<KeyEcho> = listOf(
        KeyEcho(ManualKey.SENSOR_EXPOSURE_TIME, oneFiftieth, oneFiftieth),
        KeyEcho(ManualKey.SENSOR_SENSITIVITY, 100L, 100L),
        KeyEcho(ManualKey.SENSOR_FRAME_DURATION, thirtyFps, thirtyFps),
        KeyEcho(ManualKey.CONTROL_AE_MODE, off, off),
        KeyEcho(ManualKey.CONTROL_AWB_MODE, off, off),
        KeyEcho(ManualKey.LENS_OPTICAL_STABILIZATION_MODE, off, off),
    )
}
