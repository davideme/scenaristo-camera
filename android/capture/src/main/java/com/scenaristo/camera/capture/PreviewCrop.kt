package com.scenaristo.camera.capture

/**
 * Cropping the tapped preview frame down to what is actually being recorded
 * (ADR-0018 action item 2).
 *
 * Measured on the Pixel 10 (#20): the preview stream is 1600x1200, the recording
 * is 3840x2160, and on this sensor the 4:3 stream is the *wider* field of view.
 * Sent to the browser uncropped, it would show the operator more than the take
 * contains -- someone framing themselves on a laptop would be cropped tighter in
 * the recording, and ADR-0008's rule-of-thirds and eye-line overlays would sit
 * over the wrong part of the image.
 *
 * This is pure arithmetic so it can be tested without a GPU. The GL pass applies
 * the result; it does not decide it.
 */
object PreviewCrop {

    /**
     * A centred crop expressed in normalised texture coordinates: `scale` is the
     * fraction of the source kept along each axis, `offset` the lower-left corner
     * of the kept region. Both are ready to fold into a texture matrix.
     */
    data class Region(
        val scaleX: Float,
        val scaleY: Float,
        val offsetX: Float,
        val offsetY: Float,
    ) {
        /** True when the whole source is kept, i.e. the aspect ratios already agree. */
        val isIdentity: Boolean
            get() = scaleX == 1f && scaleY == 1f && offsetX == 0f && offsetY == 0f
    }

    /**
     * The centred crop that makes [sourceWidth] x [sourceHeight] match the aspect
     * ratio of [targetWidth] x [targetHeight].
     *
     * Only ever crops: whichever axis is proportionally longer gets trimmed, the
     * other is kept whole. Upscaling to fill would invent field of view the
     * recording does not have, which is the same lie in the other direction.
     */
    fun centredCrop(
        sourceWidth: Int,
        sourceHeight: Int,
        targetWidth: Int,
        targetHeight: Int,
    ): Region {
        require(sourceWidth > 0 && sourceHeight > 0) { "source must be non-empty" }
        require(targetWidth > 0 && targetHeight > 0) { "target must be non-empty" }

        val source = sourceWidth.toDouble() / sourceHeight
        val target = targetWidth.toDouble() / targetHeight

        return when {
            // Same shape already: 1600x1200 into 800x600 crops nothing.
            aspectsAgree(source, target) -> Region(1f, 1f, 0f, 0f)

            // Source is wider than the target wants: trim the sides.
            source > target -> {
                val scaleX = (target / source).toFloat()
                Region(scaleX, 1f, (1f - scaleX) / 2f, 0f)
            }

            // Source is taller: trim top and bottom. This is the Pixel 10 case --
            // a 4:3 preview against a 16:9 recording.
            else -> {
                val scaleY = (source / target).toFloat()
                Region(1f, scaleY, 0f, (1f - scaleY) / 2f)
            }
        }
    }

    /**
     * Within a hair of equal. Resolutions do not always divide cleanly -- 1600x1200
     * is exactly 4:3 but a 1080x1920 preview against a 1080x1920 recording should
     * also crop nothing -- and a crop of 0.999 would cost a row of pixels and a
     * texture fetch for no reason.
     */
    private fun aspectsAgree(a: Double, b: Double): Boolean = kotlin.math.abs(a - b) < 0.001

    /**
     * The size the cropped frame occupies, for sizing the reader the JPEG encoder
     * and the meter read from. Rounded down so it never claims pixels the crop
     * does not cover.
     */
    fun croppedSize(sourceWidth: Int, sourceHeight: Int, region: Region): Pair<Int, Int> =
        (sourceWidth * region.scaleX).toInt() to (sourceHeight * region.scaleY).toInt()
}
