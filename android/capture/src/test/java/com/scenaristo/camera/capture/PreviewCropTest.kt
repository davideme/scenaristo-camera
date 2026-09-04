package com.scenaristo.camera.capture

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * ADR-0018 action item 2. The failure this guards is silent: an uncropped
 * preview looks perfectly fine on its own, and is only discovered by a user
 * whose head was cut off in the take.
 */
class PreviewCropTest {

    // The measured Pixel 10 case (#20): 1600x1200 preview, 3840x2160 recording.
    @Test
    fun `the Pixel 10 preview is trimmed vertically to match the recording`() {
        val region = PreviewCrop.centredCrop(1600, 1200, 3840, 2160)

        assertEquals(1f, region.scaleX, 0f) // full width kept
        assertEquals(0.75f, region.scaleY, 0.001f) // 4:3 into 16:9 keeps three quarters of the height
        assertEquals(0f, region.offsetX, 0f)
        assertEquals(0.125f, region.offsetY, 0.001f) // centred: an eighth trimmed off each edge
        assertFalse(region.isIdentity)
    }

    // ADR-0018: the tapped frame must end up the same shape as the recording.
    // This is the assertion that would have caught shipping it uncropped.
    @Test
    fun `ADR-0018 - the cropped preview matches the recording aspect ratio`() {
        val previewWidth = 1600
        val previewHeight = 1200
        val region = PreviewCrop.centredCrop(previewWidth, previewHeight, 3840, 2160)
        val (croppedWidth, croppedHeight) = PreviewCrop.croppedSize(previewWidth, previewHeight, region)

        val cropped = croppedWidth.toDouble() / croppedHeight
        val recording = 3840.0 / 2160.0
        assertEquals(recording, cropped, 0.001)
    }

    // A 16:9 preview against a 16:9 recording is the FHD case measured in #20
    // (preview 1080x1920, video 1080x1920). Cropping there would cost pixels and
    // a texture fetch for nothing.
    @Test
    fun `matching aspect ratios crop nothing`() {
        assertTrue(PreviewCrop.centredCrop(1080, 1920, 1080, 1920).isIdentity)
        assertTrue(PreviewCrop.centredCrop(1920, 1080, 3840, 2160).isIdentity)
        // Not exactly equal, but far inside the tolerance: still not worth a crop.
        assertTrue(PreviewCrop.centredCrop(1600, 1200, 800, 600).isIdentity)
    }

    // The mirror case: if a device ever hands us a preview wider than the
    // recording, the sides get trimmed instead. Not seen on the Pixel 10, but the
    // matrix has to be right when the second reference device arrives (#29).
    @Test
    fun `a preview wider than the recording is trimmed horizontally`() {
        val region = PreviewCrop.centredCrop(2000, 1000, 1000, 1000)

        assertEquals(0.5f, region.scaleX, 0.001f)
        assertEquals(1f, region.scaleY, 0f)
        assertEquals(0.25f, region.offsetX, 0.001f)
        assertEquals(0f, region.offsetY, 0f)
    }

    // The crop is centred, so what is kept must sit symmetrically inside the
    // source. Off-centre would tilt every framing guide the product draws.
    @Test
    fun `the kept region is centred on both axes`() {
        for ((w, h) in listOf(1600 to 1200, 2000 to 1000, 1440 to 1080, 3000 to 4000)) {
            val region = PreviewCrop.centredCrop(w, h, 3840, 2160)
            assertEquals(1f - region.scaleX, region.offsetX * 2, 0.001f)
            assertEquals(1f - region.scaleY, region.offsetY * 2, 0.001f)
        }
    }

    // Never enlarge: the crop keeps at most the whole source on each axis.
    // Scaling past 1 would invent field of view the recording does not have.
    @Test
    fun `the crop never claims more than the source`() {
        for ((w, h) in listOf(1600 to 1200, 1920 to 1080, 2000 to 1000, 1080 to 1920)) {
            val region = PreviewCrop.centredCrop(w, h, 3840, 2160)
            assertTrue(region.scaleX in 0f..1f)
            assertTrue(region.scaleY in 0f..1f)
            assertTrue(region.offsetX >= 0f)
            assertTrue(region.offsetY >= 0f)
        }
    }
}
