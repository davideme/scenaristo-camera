package com.scenaristo.camera.capture

import android.util.Log
import androidx.camera.core.CameraEffect
import java.util.concurrent.Executors

/**
 * Attaches [PreviewTapProcessor] to the preview stream (ADR-0018).
 *
 * Targets `PREVIEW` only, because the recording must stay untouched by anything
 * we draw.
 *
 * **The size #20 could not explain is explained.** That note read: "the
 * shared-target variant reports a different orientation (2160x3840 where
 * preview-only reports 3840x2160)". Measured on the reference Pixel 10 in
 * landscape on 2026-09-08, with the target changed to `PREVIEW or VIDEO_CAPTURE`
 * on a branch kept off `main`:
 *
 *  - CameraX supplies **one** `SurfaceOutput`, not two: `targets=3`, i.e. both
 *    targets sharing a surface.
 *  - Its size is **4000x3000**, and the effect's *input* changes from 1600x1200
 *    to 4000x3000 with it. That is the camera's own 4:3 stream at full sensor
 *    resolution -- neither surface is the recording's 3840x2160.
 *  - The transform CameraX asks for is a plain vertical flip. No rotation, no
 *    crop: sizing the recording is CameraX's job downstream of the pass.
 *
 * So the number was never a transposed recording. A shared target reports the
 * *stream's* size in the sensor's own orientation, and 2160x3840 is what that
 * looks like when the shorter axis leads. Not confirmed in portrait, because
 * the app is landscape-only until PRD 6.11.
 *
 * The consequence for anything that later wants to draw into the take: the pass
 * would render **12 MP per frame, 44% more than the 8.3 MP recording**, which is
 * the opposite of the intuition that targeting the recording costs the
 * recording's pixels. A 43 s take through it produced a correct 3840x2160 file,
 * 1284 frames in 42.814 s (29.99 fps) at 36.6 Mbit/s, with no dropped frames --
 * a smoke test, not a budget. Sustained cost and thermals are deliberately
 * unmeasured (decision 2026-09-08, Davide): #23 saw `MODERATE` at eight minutes
 * with no pass on this path at all, and take length is a product constraint to
 * be set rather than a number this comment should pretend to know.
 */
class PreviewTapEffect(processor: PreviewTapProcessor) : CameraEffect(
    PREVIEW,
    Executors.newSingleThreadExecutor(),
    processor,
    { error -> Log.e("PreviewTapEffect", "effect failed", error) },
)
