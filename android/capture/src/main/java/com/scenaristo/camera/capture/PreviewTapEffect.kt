package com.scenaristo.camera.capture

import android.util.Log
import androidx.camera.core.CameraEffect
import java.util.concurrent.Executors

/**
 * Attaches [PreviewTapProcessor] to the preview stream (ADR-0018).
 *
 * Targets `PREVIEW` only. Adding `VIDEO_CAPTURE` would route the recording
 * through the same pass, which is not wanted: the recording must stay untouched
 * by anything we draw, and #20 measured that the shared-target variant reports a
 * different orientation (2160x3840 where preview-only reports 3840x2160), which
 * nobody has explained yet.
 */
class PreviewTapEffect(processor: PreviewTapProcessor) : CameraEffect(
    PREVIEW,
    Executors.newSingleThreadExecutor(),
    processor,
    { error -> Log.e("PreviewTapEffect", "effect failed", error) },
)
