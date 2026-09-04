package com.scenaristo.camera.domain

import com.scenaristo.camera.domain.exposure.ExposureLoop
import com.scenaristo.camera.domain.exposure.FaceWeightedMeter
import com.scenaristo.camera.domain.exposure.FrameRect
import com.scenaristo.camera.domain.exposure.GridFrequency
import com.scenaristo.camera.domain.exposure.IsoRange
import com.scenaristo.camera.domain.exposure.LumaFrame
import com.scenaristo.camera.domain.exposure.LumaScale
import com.scenaristo.camera.domain.exposure.MeteringConfig
import com.scenaristo.camera.domain.exposure.yPlaneSampler
import kotlin.math.abs
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * ADR-0005's metering half: the number [ExposureLoop] acts on.
 *
 * PRD 6.3 states the promise these serve rather than the mechanism — "meter a
 * face-weighted luminance from the analysis stream" — so the tests that carry a
 * PRD number are in `ExposureLoopTest`, and these check the property that makes
 * that number mean anything: the meter answers about the speaker's face, not
 * about the window behind them.
 */
class FaceWeightedMeterTest {

    private val meter = FaceWeightedMeter()

    // Nothing in the PRD says this; everything else here would be meaningless
    // without it. A flat frame has one answer and the meter has to give it.
    @Test
    fun `a uniform frame meters as its own value`() {
        val grey = meter.meter(yFrame { _, _ -> 128 })
        assertTrue(abs(grey - LumaScale.studioRange(128)) < 0.001, "flat 128 metered as $grey")
    }

    // ADR-0005: "Compute a face-weighted log-luminance ... Target is a mid-tone
    // on the face". The scene this is built for is PRD 6.4's "natural light
    // present": a speaker with a window behind them.
    @Test
    fun `ADR-0005 - a backlit face is metered for the face and not for the window`() {
        val face = FrameRect(0.3, 0.2, 0.7, 0.8)
        val backlit = yFrame { x, y -> if (face.covers(x, y)) DIM_FACE else BLOWN_WINDOW }

        val metered = meter.meter(backlit, faces = listOf(face))

        val faceValue = LumaScale.studioRange(DIM_FACE)
        val frameMean = FACE_AREA * faceValue + (1 - FACE_AREA) * LumaScale.studioRange(BLOWN_WINDOW)
        assertTrue(
            abs(metered - faceValue) < abs(metered - frameMean),
            "metered $metered is nearer the frame mean $frameMean than the face $faceValue",
        )
    }

    // ADR-0005: face rectangles "when available and a centre-weighted window
    // otherwise". A device that reports no faces with auto-exposure off must
    // still expose for the person, who is in the middle of the frame.
    @Test
    fun `ADR-0005 - with no faces reported the centre window carries the meter`() {
        val centre = MeteringConfig().centreWindow
        val backlit = yFrame { x, y -> if (centre.covers(x, y)) DIM_FACE else BLOWN_WINDOW }

        val withFace = meter.meter(backlit, faces = listOf(centre))
        val withoutFace = meter.meter(backlit, faces = emptyList())

        assertTrue(abs(withFace - withoutFace) < 0.001, "$withFace vs $withoutFace")
    }

    // Not a PRD criterion, a device fact: camera planes are padded to an
    // alignment. Metering `width` bytes per row of a padded plane reads a
    // shearing diagonal of the picture, and it looks plausible while doing it.
    @Test
    fun `a padded row stride does not leak into the meter`() {
        val padded = LumaFrame(
            width = SIZE,
            height = SIZE,
            sampler = yPlaneSampler(
                y = ByteArray(SIZE * PADDED_STRIDE) { index ->
                    if (index % PADDED_STRIDE < SIZE) 128.toByte() else BLOWN_WINDOW.toByte()
                },
                rowStride = PADDED_STRIDE,
            ),
        )

        val metered = meter.meter(padded)
        assertTrue(abs(metered - LumaScale.studioRange(128)) < 0.001, "padding reached the meter: $metered")
    }

    // Two ranges, two platforms, one arithmetic slip away from every exposure
    // decision being a tenth of a stop out. A camera Y plane is studio-range
    // (16 is black, 235 white); the GL tap's RGBA frames are full-range.
    @Test
    fun `luma scales cover both the studio and full range conventions`() {
        assertTrue(LumaScale.studioRange(16) <= LumaScale.BLACK_FLOOR)
        assertEquals(1.0, LumaScale.studioRange(235), absoluteTolerance = 0.001)
        assertEquals(1.0, LumaScale.fullRange(255), absoluteTolerance = 0.001)
        assertTrue(
            abs(LumaScale.studioRange(128) - LumaScale.fullRange(128)) > 0.005,
            "if these agreed there would be nothing to get wrong",
        )
    }

    // PRD 6.1 fixes the colour space at Rec.709, which fixes the coefficients.
    // Green carries most of the luma, blue almost none -- a mistake here reads
    // as the app exposing differently for a blue shirt than a green one.
    @Test
    fun `PRD 6_1 - Rec_709 weights the channels as the colour space says`() {
        assertEquals(1.0, LumaScale.rec709(255, 255, 255), absoluteTolerance = 0.001)
        assertEquals(0.2126, LumaScale.rec709(255, 0, 0), absoluteTolerance = 0.001)
        assertEquals(0.7152, LumaScale.rec709(0, 255, 0), absoluteTolerance = 0.001)
        assertEquals(0.0722, LumaScale.rec709(0, 0, 255), absoluteTolerance = 0.001)
    }

    // PRD 6.3's whole point, end to end: the loop must open up for a backlit
    // face. Exposing for the frame instead would close down -- which is the
    // silhouette every laptop webcam produces, and the reason the app meters
    // this way at all.
    @Test
    fun `PRD 6_3 - metering the face raises ISO where metering the frame would lower it`() {
        val loop = ExposureLoop(IsoRange(50, 6_400))
        val face = FrameRect(0.3, 0.2, 0.7, 0.8)
        val backlit = yFrame { x, y -> if (face.covers(x, y)) DIM_FACE else BLOWN_WINDOW }
        val start = loop.start(GridFrequency.HZ_50).copy(iso = 400)

        val exposedForTheFace = loop.onFrame(start, meter.meter(backlit, listOf(face)), nowMs = 33)

        val frameMean =
            FACE_AREA * LumaScale.studioRange(DIM_FACE) +
                (1 - FACE_AREA) * LumaScale.studioRange(BLOWN_WINDOW)
        val exposedForTheFrame = loop.onFrame(start, frameMean, nowMs = 33)

        assertTrue(exposedForTheFace.iso > 400, "the face stayed dark at ISO ${exposedForTheFace.iso}")
        assertTrue(exposedForTheFrame.iso < 400, "control: metering the frame should have closed down")
    }

    private fun FrameRect.covers(x: Int, y: Int): Boolean =
        contains((x + 0.5) / SIZE, (y + 0.5) / SIZE)

    private fun yFrame(pixel: (x: Int, y: Int) -> Int): LumaFrame = LumaFrame(
        width = SIZE,
        height = SIZE,
        sampler = yPlaneSampler(
            y = ByteArray(SIZE * SIZE) { index -> pixel(index % SIZE, index / SIZE).toByte() },
            rowStride = SIZE,
        ),
    )

    private companion object {
        const val SIZE = 100
        const val PADDED_STRIDE = 128

        /** A face two and a half stops under the target: an unlit speaker against a window. */
        const val DIM_FACE = 60

        /** Studio-range white. */
        const val BLOWN_WINDOW = 235

        /** The centre window is 40 % of the width by 60 % of the height. */
        const val FACE_AREA = 0.24
    }
}
