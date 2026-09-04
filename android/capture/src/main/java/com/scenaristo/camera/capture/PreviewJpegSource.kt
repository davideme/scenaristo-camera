package com.scenaristo.camera.capture

import android.graphics.Bitmap
import android.graphics.Canvas
import android.media.Image
import java.io.ByteArrayOutputStream
import java.util.concurrent.atomic.AtomicReference

/**
 * Turns the tap's RGBA frames into the JPEGs the browser preview streams
 * (ADR-0008, fed by the tap of ADR-0018).
 *
 * Holds exactly one frame — the newest. ADR-0008 wants newest-wins rather than
 * complete: a viewer on a slow link should see a late picture, never a queue of
 * old ones, and a preview frame is worthless the moment a newer one exists.
 *
 * Every buffer here is reused. At 15 fps a fresh 960x540 bitmap per frame is
 * about 31 MB/s of allocation, which is the kind of pressure that shows up as
 * jank in the viewfinder rather than as an error anywhere.
 */
class PreviewJpegSource(
    /**
     * ADR-0008's ladder starts at 80 and steps 80 → 60 → 40 under bandwidth
     * pressure. The stepping is the server's decision; this holds the value.
     */
    @Volatile var quality: Int = 80,
) {
    private val newest = AtomicReference<ByteArray?>(null)

    /** Sized to the reader's stride, which is usually wider than the image. */
    private var padded: Bitmap? = null

    /** The image without the stride padding — what actually gets encoded. */
    private var cropped: Bitmap? = null
    private var canvas: Canvas? = null

    private val buffer = ByteArrayOutputStream(DEFAULT_JPEG_BYTES)

    /** Frames encoded since construction, for the spike readout and later telemetry. */
    @Volatile var encoded: Long = 0
        private set

    /**
     * Encodes one frame and closes it.
     *
     * Called on the tap's GL thread. Closing is this method's job and not the
     * caller's, because the reader stalls once its buffers are outstanding —
     * forgetting would freeze the preview rather than leak.
     */
    fun accept(image: Image) {
        try {
            val plane = image.planes[0]
            val pixelStride = plane.pixelStride
            val rowStride = plane.rowStride

            // The reader hands back rows padded to its own stride, so a bitmap
            // built at the image's width would shear the picture diagonally.
            // Copy at the padded width, then draw across into the real one.
            val paddedWidth = rowStride / pixelStride
            val source = ensurePadded(paddedWidth, image.height)
            plane.buffer.rewind()
            source.copyPixelsFromBuffer(plane.buffer)

            val target = ensureCropped(image.width, image.height)
            canvas!!.drawBitmap(source, 0f, 0f, null)

            buffer.reset()
            target.compress(Bitmap.CompressFormat.JPEG, quality, buffer)
            newest.set(buffer.toByteArray())
            encoded++
        } finally {
            image.close()
        }
    }

    /** The newest JPEG, or null before the first frame. Safe from any thread. */
    fun latest(): ByteArray? = newest.get()

    private fun ensurePadded(width: Int, height: Int): Bitmap {
        val current = padded
        if (current != null && current.width == width && current.height == height) return current
        current?.recycle()
        return Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888).also { padded = it }
    }

    private fun ensureCropped(width: Int, height: Int): Bitmap {
        val current = cropped
        if (current != null && current.width == width && current.height == height) return current
        current?.recycle()
        return Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888).also {
            cropped = it
            canvas = Canvas(it)
        }
    }

    /** Releases the bitmaps. The tap owns the reader, so there is nothing else to free. */
    fun release() {
        padded?.recycle()
        cropped?.recycle()
        padded = null
        cropped = null
        canvas = null
        newest.set(null)
    }

    private companion object {
        /** A 960x540 JPEG at quality 80 is 50-100 KB (ADR-0008); start there and let it grow once. */
        const val DEFAULT_JPEG_BYTES = 128 * 1024
    }
}
