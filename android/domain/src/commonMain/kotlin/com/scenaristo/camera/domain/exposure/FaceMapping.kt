package com.scenaristo.camera.domain.exposure

/**
 * Getting a face rectangle from where the sensor reports it to where the meter
 * reads (PRD 6.3, ADR-0005, ADR-0018).
 *
 * ADR-0005 asks the metering loop for a *face-weighted* luminance, and PRD 6.3
 * promises one. Until now the meter was handed an empty list and always took
 * [MeteringConfig.centreWindow] instead — a fixed rectangle that happens to land
 * near a seated speaker's head and is not a measurement of anybody. The missing
 * piece was never the plumbing; it is this arithmetic.
 *
 * **Two spaces, and they share no convention.** The platform reports faces in
 * the sensor's own active array: whole pixels, origin top-left, unrotated and
 * unmirrored. The meter reads a frame that the preview tap has already turned
 * upright and cropped from the preview's 4:3 down to the recording's 16:9
 * (ADR-0018). [normalisedInCrop] and [toFrame] are those two steps.
 *
 * **Why this is not the GL matrix inverted.** `PreviewTapProcessor` renders
 * through a matrix it borrows from CameraX rather than deriving, because
 * deriving rotation by hand there "leaves the other two behind" — and reaching
 * for the same matrix here is the obvious move. It does not work: that matrix
 * maps output coordinates to *raw external-texture* coordinates with the
 * `SurfaceTexture` transform folded in, so its input space is not the sensor
 * image and its inverse does not take a face rectangle anywhere useful. In image
 * space the difference is a quarter turn and a centred crop, which are two
 * numbers the tap already holds and which are checkable against a device.
 *
 * The mirroring half of that warning still stands, and [toFrame] carries it as a
 * parameter rather than an assumption — see its documentation.
 *
 * Everything here is arithmetic on plain numbers so it is host-tested (ADR-0013),
 * and so the iOS port in Phase 4 reaches the same rectangle from `VNFaceObservation`
 * rather than a second, differently-wrong version of it.
 */
/**
 * A rectangle in the sensor's own whole-pixel coordinates: a reported face, the
 * region being read, or the lens's active array.
 *
 * Plain integers rather than a platform rectangle, so the composition in
 * [FaceMapping] is decided here and merely *fed* by the camera layer — the shape
 * `MountSensor` and `ManualKeyEcho` already use, where the judgement is
 * host-tested and the platform class only reads keys.
 */
data class SensorRect(val left: Int, val top: Int, val right: Int, val bottom: Int)

/** A landmark in the same whole-pixel sensor coordinates as [SensorRect]. */
data class SensorPoint(val x: Int, val y: Int)

/**
 * One reported face: where it is, and the eyes if the device offers them.
 *
 * Measured on the reference Pixel 10 (2026-09-08): `availableFaceDetectModes` is
 * `[0, 1, 2]`, `FULL` is honoured with 3A off, and both eye positions are
 * populated. They are nullable anyway, because `SIMPLE` is a legal answer and a
 * device that gives only bounds must still meter.
 *
 * **`leftEye` is the image's left, not the subject's.** Measured on that device:
 * `leftEye.x` is consistently the smaller of the two on an unmirrored frame. The
 * names follow the platform's own field names rather than being corrected here,
 * because a reader checking against `CaptureResult` should find what they expect
 * -- and because nothing downstream needs to know whose left it is: [splitBetween]
 * wants a midpoint, which is the same point either way round.
 */
data class SensorFace(
    val bounds: SensorRect,
    val leftEye: SensorPoint? = null,
    val rightEye: SensorPoint? = null,
) {
    /**
     * Where the lit and shadow halves divide, in sensor pixels.
     *
     * The midpoint of the eyes when both are known, which tracks a turned head:
     * a face three-quarters to camera does not have its nose in the middle of
     * its own bounding box, and splitting the box there would call part of the
     * lit cheek "shadow". The box centre otherwise, which is right for a face
     * square to the lens and is what a `SIMPLE`-only device gets.
     */
    val splitBetween: Int
        get() {
            val l = leftEye
            val r = rightEye
            return if (l != null && r != null) (l.x + r.x) / 2 else (bounds.left + bounds.right) / 2
        }
}

/**
 * What the preview tap does to the buffer between the sensor and the frame the
 * meter reads (ADR-0018): a quarter turn to upright, then a centred crop to the
 * recording's aspect ratio.
 *
 * In `:domain` rather than beside the GL pass because iOS has the same two facts
 * to state in Phase 4 and must reach the same rectangle from them (ADR-0013).
 */
data class TapGeometry(
    val rotationDegrees: Int,
    val cropScaleX: Double,
    val cropScaleY: Double,
    val cropOffsetX: Double,
    val cropOffsetY: Double,
)

object FaceMapping {

    /**
     * A corner, in normalised 0..1 coordinates of whatever space it belongs to.
     *
     * Deliberately not a [FrameRect]: between [cornersOf] and [boundsOf] these
     * points are mid-transform, and a rotation of 90° turns a left edge into a
     * top one. A type that called them `left` and `top` would be lying for the
     * duration.
     */
    data class Corner(val x: Double, val y: Double)

    /**
     * Where a reported face sits inside the sensor region actually being read,
     * as fractions in 0..1, or null when the answer would be meaningless.
     *
     * **The crop region is the divisor, not the active array.** Faces are
     * reported against `SCALER_CROP_REGION`, which zoom moves and shrinks. Divide
     * by the full array while the user is at 2x and the face lands at half its
     * true offset — a rectangle in the right units, of the right size, in the
     * wrong place, which is the failure mode that looks like it works.
     *
     * Returns null rather than a clamped guess for a degenerate crop or a face
     * with no area: a face the caller cannot place is the centre window's case,
     * and PRD 6.3's fallback is better than a rectangle nobody can defend.
     *
     * **Known limit, stated rather than discovered later.** Which active array a
     * face is reported against depends on `DISTORTION_CORRECTION_MODE`: the
     * corrected array when it is on, the pre-correction one when it is off, and
     * the two differ on lenses with enough barrel distortion to be worth
     * correcting. Only the crop region is used here, so this bites solely on the
     * fallback path -- a result carrying no crop region on a device where the two
     * arrays differ, which would offset every face by the difference. It does not
     * arise on the reference Pixel 10, where the crop region at 1x is reported as
     * the whole 4000x3000 array (measured 2026-09-08), so the fallback is never
     * the one in use. Widening the matrix (#29) is when this needs an answer.
     */
    fun normalisedInCrop(
        faceLeft: Int,
        faceTop: Int,
        faceRight: Int,
        faceBottom: Int,
        cropLeft: Int,
        cropTop: Int,
        cropRight: Int,
        cropBottom: Int,
    ): FrameRect? {
        val cropWidth = (cropRight - cropLeft).toDouble()
        val cropHeight = (cropBottom - cropTop).toDouble()
        if (cropWidth <= 0.0 || cropHeight <= 0.0) return null
        if (faceRight <= faceLeft || faceBottom <= faceTop) return null

        val left = ((faceLeft - cropLeft) / cropWidth).coerceIn(0.0, 1.0)
        val right = ((faceRight - cropLeft) / cropWidth).coerceIn(0.0, 1.0)
        val top = ((faceTop - cropTop) / cropHeight).coerceIn(0.0, 1.0)
        val bottom = ((faceBottom - cropTop) / cropHeight).coerceIn(0.0, 1.0)

        // A face entirely outside the read region clamps to a zero-area sliver.
        // That is not a face, and weighting it would weight one edge of the frame.
        if (right <= left || bottom <= top) return null
        return FrameRect(left = left, top = top, right = right, bottom = bottom)
    }

    /**
     * A face, normalised into the sensor's read region, taken through to the
     * frame the meter actually reads.
     *
     * The tap does two things to the buffer between the sensor and the reader
     * (ADR-0018): it turns it upright by [rotationDegrees], and it crops the
     * result to the recording's aspect ratio. Both are applied here in that
     * order, because that is the order `PreviewTapProcessor` applies them —
     * its crop is computed against the *upright* size, so cropping first would
     * take the right amount off the wrong edge.
     *
     * **This deliberately composes in image space rather than inverting the GL
     * matrix**, and the reason is worth recording because the opposite choice
     * looks obviously better. That matrix maps output coordinates to *raw
     * external-texture* coordinates, with the `SurfaceTexture` transform already
     * folded in; its input space is not the sensor image, so its inverse does not
     * take a face rectangle anywhere useful. Rotation and the crop are the whole
     * of the difference in image space, and they are two numbers the tap already
     * holds.
     *
     * [mirrored] is carried but is false for everything v1 ships: the front
     * camera is PRD 6.11, and the browser's mirror is a client-side CSS
     * transform that never reaches these pixels (UI-19). It is a parameter
     * rather than an assumption so that the day a front lens is selectable, the
     * failure is a compile error at the call site and not a face metered on the
     * wrong cheek.
     */
    fun toFrame(
        rect: FrameRect,
        rotationDegrees: Int,
        cropScaleX: Double,
        cropScaleY: Double,
        cropOffsetX: Double,
        cropOffsetY: Double,
        mirrored: Boolean = false,
    ): FrameRect? {
        if (cropScaleX <= 0.0 || cropScaleY <= 0.0) return null

        val turned = cornersOf(rect).map { rotate(it, rotationDegrees) }
        val faced = if (mirrored) turned.map { Corner(1.0 - it.x, it.y) } else turned
        val cropped = faced.map {
            Corner(
                x = (it.x - cropOffsetX) / cropScaleX,
                y = (it.y - cropOffsetY) / cropScaleY,
            )
        }
        return boundsOf(cropped)
    }

    /**
     * A quarter turn in normalised image space, origin top-left.
     *
     * [rotationDegrees] is what CameraX reports as the turn that makes the
     * buffer upright, clockwise, and only multiples of 90 occur. Anything else
     * is treated as no rotation rather than approximated: a sensor reporting 45°
     * is a device worth hearing about, not a rectangle worth guessing at.
     */
    private fun rotate(corner: Corner, rotationDegrees: Int): Corner =
        when (((rotationDegrees % 360) + 360) % 360) {
            90 -> Corner(1.0 - corner.y, corner.x)
            180 -> Corner(1.0 - corner.x, 1.0 - corner.y)
            270 -> Corner(corner.y, 1.0 - corner.x)
            else -> corner
        }

    /**
     * The four corners of [rect], for handing to a platform transform.
     *
     * All four, not two: the transform may rotate, and the diagonal of a rotated
     * rectangle is not the rotation of its diagonal. Passing the top-left and
     * bottom-right alone is correct for mirroring and wrong for a quarter turn,
     * which is the kind of bug that survives a portrait test and fails in
     * landscape.
     */
    fun cornersOf(rect: FrameRect): List<Corner> = listOf(
        Corner(rect.left, rect.top),
        Corner(rect.right, rect.top),
        Corner(rect.right, rect.bottom),
        Corner(rect.left, rect.bottom),
    )

    /**
     * Every reported face, normalised into the region the sensor is reading.
     *
     * **The divisor, in order:** the crop region the result carried, because that
     * is what faces are reported against and zoom moves it; then the lens's
     * active array, which is the same rectangle whenever the user is at 1x and
     * the honest fallback for a result that carries no crop; then nothing, which
     * is an empty list and the meter's centre window (PRD 6.3).
     *
     * Kept apart from [facesInFrame] because the two are true at different
     * moments: this half depends only on the capture result, while that half is
     * only true for the frame being metered. Collapsing them would let a face
     * reported before the phone was turned be mapped through the orientation
     * after it.
     */
    fun facesInCrop(
        faces: List<SensorFace>,
        cropRegion: SensorRect?,
        activeArray: SensorRect?,
    ): List<FrameRect> {
        if (faces.isEmpty()) return emptyList()
        val region = cropRegion ?: activeArray ?: return emptyList()
        return faces.mapNotNull {
            normalisedInCrop(
                faceLeft = it.bounds.left,
                faceTop = it.bounds.top,
                faceRight = it.bounds.right,
                faceBottom = it.bounds.bottom,
                cropLeft = region.left,
                cropTop = region.top,
                cropRight = region.right,
                cropBottom = region.bottom,
            )
        }
    }

    /**
     * Those faces, in the coordinates of the frame about to be metered.
     *
     * A null [geometry] is the tap not yet having drawn anything, and it returns
     * an empty list rather than assuming the identity: every way of not knowing
     * lands on the centre window, which is the behaviour every take had before
     * faces were wired and is better than a rectangle nobody can defend.
     */
    fun facesInFrame(inCrop: List<FrameRect>, geometry: TapGeometry?): List<FrameRect> {
        if (inCrop.isEmpty()) return emptyList()
        if (geometry == null) return emptyList()
        return inCrop.mapNotNull {
            toFrame(
                rect = it,
                rotationDegrees = geometry.rotationDegrees,
                cropScaleX = geometry.cropScaleX,
                cropScaleY = geometry.cropScaleY,
                cropOffsetX = geometry.cropOffsetX,
                cropOffsetY = geometry.cropOffsetY,
            )
        }
    }

    /**
     * The subject of a lighting read: the first reported face, in frame
     * coordinates, with the line its two halves divide on (PRD 6.11).
     *
     * The first face rather than the largest, deliberately. A talking head is one
     * subject, and picking by size would let the reading hop between two people
     * as they lean -- a number that changes because the *choice* changed is worse
     * than one that is merely about the wrong person.
     *
     * The split is carried through the same turn and crop as the rectangle, not
     * recomputed from the mapped box, so a turned head keeps its nose line.
     */
    fun subjectInFrame(
        faces: List<SensorFace>,
        cropRegion: SensorRect?,
        activeArray: SensorRect?,
        geometry: TapGeometry?,
    ): Subject? {
        val face = faces.firstOrNull() ?: return null
        val region = cropRegion ?: activeArray ?: return null
        if (geometry == null) return null

        val rect = facesInFrame(
            facesInCrop(listOf(face), cropRegion, activeArray),
            geometry,
        ).firstOrNull() ?: return null

        // The split is a point, so it travels as a degenerate rectangle through
        // the same two steps: one pixel wide, so the arithmetic that refuses a
        // zero-area face does not refuse it.
        val splitX = face.splitBetween
        val asRect = normalisedInCrop(
            faceLeft = splitX,
            faceTop = face.bounds.top,
            faceRight = splitX + 1,
            faceBottom = face.bounds.bottom,
            cropLeft = region.left,
            cropTop = region.top,
            cropRight = region.right,
            cropBottom = region.bottom,
        ) ?: return null
        val split = toFrame(
            rect = asRect,
            rotationDegrees = geometry.rotationDegrees,
            cropScaleX = geometry.cropScaleX,
            cropScaleY = geometry.cropScaleY,
            cropOffsetX = geometry.cropOffsetX,
            cropOffsetY = geometry.cropOffsetY,
        ) ?: return null

        return Subject(rect = rect, splitX = (split.left + split.right) / 2.0)
    }

    /**
     * The axis-aligned bounds of transformed [corners], clipped to the frame, or
     * null when nothing of it is left inside.
     *
     * Exact rather than approximate for the transforms that actually occur here:
     * rotations by multiples of 90°, mirroring, and a centred crop all map an
     * axis-aligned rectangle to another one, so its bounds are the rectangle
     * itself. It stays honest for an arbitrary transform too — the bounds are
     * then a rectangle that contains the face, which is what a weighting window
     * wants and what a landmark would not.
     */
    fun boundsOf(corners: List<Corner>): FrameRect? {
        if (corners.isEmpty()) return null

        var left = corners[0].x
        var right = corners[0].x
        var top = corners[0].y
        var bottom = corners[0].y
        for (i in 1 until corners.size) {
            val c = corners[i]
            if (c.x < left) left = c.x
            if (c.x > right) right = c.x
            if (c.y < top) top = c.y
            if (c.y > bottom) bottom = c.y
        }

        val clippedLeft = left.coerceIn(0.0, 1.0)
        val clippedRight = right.coerceIn(0.0, 1.0)
        val clippedTop = top.coerceIn(0.0, 1.0)
        val clippedBottom = bottom.coerceIn(0.0, 1.0)

        // The crop the tap applies is the usual reason for this: a face near the
        // top of a 4:3 preview can be outside the 16:9 the recording keeps, and
        // it is not in the take at all. Metering it would expose for someone the
        // viewer cannot see.
        if (clippedRight <= clippedLeft || clippedBottom <= clippedTop) return null
        return FrameRect(
            left = clippedLeft,
            top = clippedTop,
            right = clippedRight,
            bottom = clippedBottom,
        )
    }
}
