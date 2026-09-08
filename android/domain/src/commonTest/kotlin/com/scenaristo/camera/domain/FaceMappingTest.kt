package com.scenaristo.camera.domain

import com.scenaristo.camera.domain.exposure.FaceMapping
import com.scenaristo.camera.domain.exposure.FrameRect
import com.scenaristo.camera.domain.exposure.SensorRect
import com.scenaristo.camera.domain.exposure.TapGeometry
import kotlin.math.roundToInt
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * The numbers here are the reference Pixel 10's, read off the device on
 * 2026-09-08 rather than invented, because a coordinate conversion tested
 * against its own algebra cannot catch the algebra being wrong — the same
 * argument `MountLevelTest` makes for the accelerometer.
 *
 * From `dumpsys media.camera` and a running session (camera id 0):
 *
 *   sensor active array          4000 x 3000
 *   SCALER_CROP_REGION at 1x     Rect(0, 0 - 4000, 3000)
 *   availableFaceDetectModes     [0, 1, 2]  (OFF, SIMPLE, FULL)
 *   STATISTICS_FACE_DETECT_MODE  echoes 2 while CONTROL_AE_MODE echoes 0
 *   preview stream               1600 x 1200      (ADR-0018, #20)
 *   recording                    3840 x 2160
 *
 * That last pair is why [FaceMapping.boundsOf] clips: the preview is 4:3 and the
 * recording is 16:9, so a face near the top of what the sensor sees can be
 * outside the take entirely.
 */
class FaceMappingTest {

    // PRD 6.3: "meter a face-weighted luminance from the analysis stream".
    // The face has to arrive in the meter's own coordinates for that to mean
    // anything, and the middle of the sensor is the middle of the frame.
    @Test
    fun `PRD 6_3 - a face at the centre of the sensor normalises to the centre of the frame`() {
        val rect = FaceMapping.normalisedInCrop(
            faceLeft = 1800, faceTop = 1350, faceRight = 2200, faceBottom = 1650,
            cropLeft = 0, cropTop = 0, cropRight = 4000, cropBottom = 3000,
        )

        assertNotNull(rect, "a face squarely inside the read region was dropped")
        assertEquals(0.45, rect.left, 1e-9, "the left edge moved")
        assertEquals(0.55, rect.right, 1e-9, "the right edge moved")
        assertEquals(0.45, rect.top, 1e-9, "the top edge moved")
        assertEquals(0.55, rect.bottom, 1e-9, "the bottom edge moved")
    }

    /**
     * The failure that looks like it works: right units, right size, wrong place.
     *
     * At 2x the crop region is the middle quarter of the array, and the same face
     * fills twice as much of it. Dividing by the active array instead would put
     * this face at 0.45..0.55 — plausible, stable, and off by the zoom ratio.
     */
    @Test
    fun `ADR-0018 - the crop region is the divisor, not the active array`() {
        // 2x on a 4000x3000 array: a 2000x1500 window, centred.
        val zoomed = FaceMapping.normalisedInCrop(
            faceLeft = 1800, faceTop = 1350, faceRight = 2200, faceBottom = 1650,
            cropLeft = 1000, cropTop = 750, cropRight = 3000, cropBottom = 2250,
        )

        assertNotNull(zoomed, "a centred face was dropped at 2x")
        assertEquals(0.40, zoomed.left, 1e-9, "the face did not widen with the crop")
        assertEquals(0.60, zoomed.right, 1e-9, "the face did not widen with the crop")
        assertTrue(
            zoomed.right - zoomed.left > 0.15,
            "at 2x the face should fill twice the frame it filled at 1x, not the same fraction",
        )
    }

    @Test
    fun `a face running off the edge of the read region is clipped, not dropped`() {
        val rect = FaceMapping.normalisedInCrop(
            faceLeft = -200, faceTop = 1350, faceRight = 400, faceBottom = 1650,
            cropLeft = 0, cropTop = 0, cropRight = 4000, cropBottom = 3000,
        )

        assertNotNull(rect, "a face half out of frame is still a face")
        assertEquals(0.0, rect.left, 1e-9, "the clip let the rectangle start off-frame")
        assertEquals(0.1, rect.right, 1e-9, "the visible part of the face was resized")
    }

    @Test
    fun `a face entirely outside the read region falls back rather than weighting an edge`() {
        val rect = FaceMapping.normalisedInCrop(
            faceLeft = -800, faceTop = 1350, faceRight = -400, faceBottom = 1650,
            cropLeft = 0, cropTop = 0, cropRight = 4000, cropBottom = 3000,
        )

        assertNull(rect, "a face outside the crop clamped to a sliver instead of being refused")
    }

    @Test
    fun `a degenerate crop region is refused rather than divided by`() {
        assertNull(
            FaceMapping.normalisedInCrop(
                faceLeft = 100, faceTop = 100, faceRight = 200, faceBottom = 200,
                cropLeft = 0, cropTop = 0, cropRight = 0, cropBottom = 0,
            ),
            "an empty crop region produced a rectangle instead of nothing",
        )
    }

    /**
     * ADR-0018's tap turns the buffer upright with CameraX's own matrix, so a
     * quarter turn is the normal case and not an exotic one. All four corners are
     * carried through it because the diagonal of a rotated rectangle is not the
     * rotation of its diagonal — two corners survive a portrait test and fail in
     * landscape.
     */
    @Test
    fun `ADR-0018 - a quarter turn is recovered exactly from all four corners`() {
        val face = FrameRect(left = 0.10, top = 0.20, right = 0.30, bottom = 0.50)

        // What the tap's matrix does to a point on a 90° turn: (x, y) -> (1 - y, x).
        val turned = FaceMapping.cornersOf(face).map { FaceMapping.Corner(1.0 - it.y, it.x) }
        val bounds = FaceMapping.boundsOf(turned)

        assertNotNull(bounds, "a face inside the frame was lost in the turn")
        assertEquals(0.50, bounds.left, 1e-9, "the turned face is in the wrong place")
        assertEquals(0.80, bounds.right, 1e-9, "the turned face is in the wrong place")
        assertEquals(0.10, bounds.top, 1e-9, "the turned face is in the wrong place")
        assertEquals(0.30, bounds.bottom, 1e-9, "the turned face is in the wrong place")

        val width = bounds.right - bounds.left
        val height = bounds.bottom - bounds.top
        assertEquals(face.bottom - face.top, width, 1e-9, "the turn did not swap the axes")
        assertEquals(face.right - face.left, height, 1e-9, "the turn did not swap the axes")
    }

    @Test
    fun `mirroring moves the face across the frame without resizing it`() {
        val face = FrameRect(left = 0.10, top = 0.20, right = 0.30, bottom = 0.50)

        val mirrored = FaceMapping.cornersOf(face).map { FaceMapping.Corner(1.0 - it.x, it.y) }
        val bounds = FaceMapping.boundsOf(mirrored)

        assertNotNull(bounds, "a mirrored face was lost")
        assertEquals(0.70, bounds.left, 1e-9, "the mirrored face is in the wrong place")
        assertEquals(0.90, bounds.right, 1e-9, "the mirrored face is in the wrong place")
        assertEquals(0.20, bounds.top, 1e-9, "mirroring moved the face vertically")
        assertEquals(0.50, bounds.bottom, 1e-9, "mirroring moved the face vertically")
    }

    /**
     * The Pixel 10 case: a 4:3 preview against a 16:9 recording keeps the middle
     * 0.75 of the height, so 0.125 is trimmed off the top and the bottom. A face
     * above that line is not in the take, and metering it would expose for
     * somebody the viewer cannot see.
     */
    @Test
    fun `ADR-0018 - a face outside the recorded 16 by 9 is refused, not clamped to its edge`() {
        val aboveTheCrop = listOf(
            FaceMapping.Corner(0.40, -0.20),
            FaceMapping.Corner(0.60, -0.20),
            FaceMapping.Corner(0.60, -0.05),
            FaceMapping.Corner(0.40, -0.05),
        )

        assertNull(
            FaceMapping.boundsOf(aboveTheCrop),
            "a face cropped out of the recording was metered against the top edge instead",
        )
    }

    /**
     * The measured case, end to end, and the one that was checked by eye against
     * a frame pulled off the MJPEG stream on 2026-09-08.
     *
     *   tap reader 960x540, rotation=0,
     *   crop=Region(scaleX=1.0, scaleY=0.75, offsetX=0.0, offsetY=0.125)
     *   face  Rect(1903, 1258 - 2716, 2071)  of a 4000x3000 array
     *
     * With the subject square to the lens, the mapped eye line landed on the
     * pupils. These are the numbers that produced that overlay.
     */
    @Test
    fun `PRD 6_3 - the measured Pixel 10 face lands where the overlay put it`() {
        val inCrop = FaceMapping.normalisedInCrop(
            faceLeft = 1903, faceTop = 1258, faceRight = 2716, faceBottom = 2071,
            cropLeft = 0, cropTop = 0, cropRight = 4000, cropBottom = 3000,
        )
        assertNotNull(inCrop, "the measured face was refused")

        val frame = FaceMapping.toFrame(
            rect = inCrop,
            rotationDegrees = 0,
            cropScaleX = 1.0, cropScaleY = 0.75,
            cropOffsetX = 0.0, cropOffsetY = 0.125,
        )
        assertNotNull(frame, "the measured face fell outside the recorded frame")

        // 1903/4000 and 2716/4000; then (1258/3000 - 0.125)/0.75 and the same for 2071.
        assertEquals(0.47575, frame.left, 1e-5, "the face moved horizontally")
        assertEquals(0.67900, frame.right, 1e-5, "the face moved horizontally")
        assertEquals(0.39244, frame.top, 1e-5, "the face moved vertically")
        assertEquals(0.75378, frame.bottom, 1e-5, "the face moved vertically")

        // 960x540: the overlay drew (457, 212)-(652, 407).
        assertEquals(457, (frame.left * 960).roundToInt(), "x disagrees with the verified overlay")
        assertEquals(212, (frame.top * 540).roundToInt(), "y disagrees with the verified overlay")
    }

    /**
     * The crop is computed against the *upright* buffer, so the turn comes first.
     * Doing it the other way round takes the right amount off the wrong edge —
     * the same mistake `PreviewTapProcessor.ensureReader` documents for itself.
     */
    @Test
    fun `ADR-0018 - the turn is applied before the crop, not after`() {
        val face = FrameRect(left = 0.30, top = 0.10, right = 0.50, bottom = 0.30)

        val turnedThenCropped = FaceMapping.toFrame(
            rect = face,
            rotationDegrees = 90,
            cropScaleX = 1.0, cropScaleY = 0.75,
            cropOffsetX = 0.0, cropOffsetY = 0.125,
        )
        assertNotNull(turnedThenCropped, "a face inside the frame was lost")

        // The turn sends (x, y) to (1 - y, x): x becomes 0.70..0.90, y becomes
        // 0.30..0.50. Only then does the crop stretch y.
        assertEquals(0.70, turnedThenCropped.left, 1e-9, "the turn did not run first")
        assertEquals(0.90, turnedThenCropped.right, 1e-9, "the turn did not run first")
        assertEquals((0.30 - 0.125) / 0.75, turnedThenCropped.top, 1e-9, "the crop was applied to the wrong axis")
        assertEquals((0.50 - 0.125) / 0.75, turnedThenCropped.bottom, 1e-9, "the crop was applied to the wrong axis")
    }

    @Test
    fun `an unmirrored frame keeps the face on the side the sensor saw it`() {
        val face = FrameRect(left = 0.10, top = 0.40, right = 0.30, bottom = 0.60)
        val straight = FaceMapping.toFrame(face, 0, 1.0, 1.0, 0.0, 0.0, mirrored = false)
        val flipped = FaceMapping.toFrame(face, 0, 1.0, 1.0, 0.0, 0.0, mirrored = true)

        assertNotNull(straight, "an unmirrored face was lost")
        assertNotNull(flipped, "a mirrored face was lost")
        assertEquals(0.10, straight.left, 1e-9, "the unmirrored face moved")
        assertEquals(0.70, flipped.left, 1e-9, "the mirrored face is not on the opposite side")
    }

    @Test
    fun `a degenerate crop scale is refused rather than divided by`() {
        assertNull(
            FaceMapping.toFrame(
                rect = FrameRect(0.4, 0.4, 0.6, 0.6),
                rotationDegrees = 0,
                cropScaleX = 1.0, cropScaleY = 0.0,
                cropOffsetX = 0.0, cropOffsetY = 0.0,
            ),
            "a zero crop scale produced a rectangle instead of nothing",
        )
    }

    // --- the composed steps ------------------------------------------------

    private val array = SensorRect(0, 0, 4000, 3000)

    /** The Pixel 10's tap in landscape: no turn, 4:3 cropped to 16:9. */
    private val landscape = TapGeometry(
        rotationDegrees = 0,
        cropScaleX = 1.0,
        cropScaleY = 0.75,
        cropOffsetX = 0.0,
        cropOffsetY = 0.125,
    )

    @Test
    fun `ADR-0018 - the crop region wins over the active array as the divisor`() {
        val face = SensorRect(1800, 1350, 2200, 1650)
        val zoomed = SensorRect(1000, 750, 3000, 2250)

        val atOneX = FaceMapping.facesInCrop(listOf(face), cropRegion = array, activeArray = array)
        val atTwoX = FaceMapping.facesInCrop(listOf(face), cropRegion = zoomed, activeArray = array)

        assertEquals(0.45, atOneX.single().left, 1e-9, "the 1x divisor changed")
        assertEquals(
            0.40,
            atTwoX.single().left,
            1e-9,
            "the active array was used as the divisor while a crop region was present",
        )
    }

    @Test
    fun `a result with no crop region falls back to the active array`() {
        val face = SensorRect(1800, 1350, 2200, 1650)

        val mapped = FaceMapping.facesInCrop(listOf(face), cropRegion = null, activeArray = array)

        assertEquals(0.45, mapped.single().left, 1e-9, "the active-array fallback did not run")
    }

    @Test
    fun `PRD 6_3 - knowing neither region falls back to the centre window`() {
        val mapped = FaceMapping.facesInCrop(
            listOf(SensorRect(1800, 1350, 2200, 1650)),
            cropRegion = null,
            activeArray = null,
        )

        assertTrue(mapped.isEmpty(), "a face was placed against a region nobody knew")
    }

    /**
     * The tap has not drawn yet, so there is no frame to map into. Assuming the
     * identity would put a 4:3 rectangle into a 16:9 frame — off by the crop, on
     * exactly the frames where the camera has just been bound.
     */
    @Test
    fun `PRD 6_3 - no tap geometry yet falls back to the centre window`() {
        val inCrop = listOf(FrameRect(0.45, 0.45, 0.55, 0.55))

        assertTrue(
            FaceMapping.facesInFrame(inCrop, geometry = null).isEmpty(),
            "a face was mapped through a frame the tap had not described",
        )
    }

    @Test
    fun `both faces survive the two steps when the sensor reports two`() {
        val faces = listOf(
            SensorRect(600, 1350, 1000, 1650),
            SensorRect(2800, 1350, 3200, 1650),
        )

        val framed = FaceMapping.facesInFrame(
            FaceMapping.facesInCrop(faces, cropRegion = array, activeArray = array),
            landscape,
        )

        assertEquals(2, framed.size, "a second face was dropped between the sensor and the meter")
        assertTrue(framed[0].right < framed[1].left, "the two faces were reordered or merged")
    }

    @Test
    fun `a face cropped out of the recording is dropped while the other is kept`() {
        val faces = listOf(
            SensorRect(1800, 1350, 2200, 1650),
            // Inside the 4:3 the sensor sees, above the 16:9 the recording keeps.
            SensorRect(1800, 60, 2200, 300),
        )

        val framed = FaceMapping.facesInFrame(
            FaceMapping.facesInCrop(faces, cropRegion = array, activeArray = array),
            landscape,
        )

        assertEquals(1, framed.size, "a face outside the recorded frame was metered anyway")
        assertEquals(0.45, framed.single().left, 1e-9, "the wrong face survived")
    }

    @Test
    fun `ADR-0018 - a half turn puts the face diagonally opposite`() {
        val face = FrameRect(left = 0.10, top = 0.20, right = 0.30, bottom = 0.40)

        val turned = FaceMapping.toFrame(face, 180, 1.0, 1.0, 0.0, 0.0)

        assertNotNull(turned, "a face inside the frame was lost in the half turn")
        assertEquals(0.70, turned.left, 1e-9, "the half turn is wrong horizontally")
        assertEquals(0.90, turned.right, 1e-9, "the half turn is wrong horizontally")
        assertEquals(0.60, turned.top, 1e-9, "the half turn is wrong vertically")
        assertEquals(0.80, turned.bottom, 1e-9, "the half turn is wrong vertically")
    }

    @Test
    fun `ADR-0018 - three quarter turns are the inverse of one`() {
        val face = FrameRect(left = 0.10, top = 0.20, right = 0.30, bottom = 0.50)

        val once = FaceMapping.toFrame(face, 90, 1.0, 1.0, 0.0, 0.0)
        assertNotNull(once, "a face was lost in a quarter turn")
        val back = FaceMapping.toFrame(once, 270, 1.0, 1.0, 0.0, 0.0)
        assertNotNull(back, "a face was lost turning back")

        assertEquals(face.left, back.left, 1e-9, "turning 90 then 270 did not return the face")
        assertEquals(face.top, back.top, 1e-9, "turning 90 then 270 did not return the face")
        assertEquals(face.right, back.right, 1e-9, "turning 90 then 270 did not return the face")
        assertEquals(face.bottom, back.bottom, 1e-9, "turning 90 then 270 did not return the face")
    }

    @Test
    fun `boundsOf has nothing to report for no corners`() {
        assertNull(FaceMapping.boundsOf(emptyList()), "an absent face produced a rectangle")
    }
}
