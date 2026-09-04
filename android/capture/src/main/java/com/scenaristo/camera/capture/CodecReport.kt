package com.scenaristo.camera.capture

import android.media.CamcorderProfile
import android.media.MediaCodecList

/**
 * What codec the device profile picks for UHD, and what the device could have
 * picked (#21, ADR-0002 action item 3).
 *
 * The interesting number is the **delta**. CameraX 1.6.2 has no SDR codec
 * selector, so the codec follows the device's encoder profile and PRD 6.7 only
 * promises to *show* which one will be used. A device that ships a hardware HEVC
 * encoder whose profile nonetheless selects AVC is exactly what CameraX 1.7's
 * `setVideoMimeType` enforcement fixes, and counting those devices is what sizes
 * that work (#27).
 */
object CodecReport {

    data class Encoder(
        val name: String,
        val mimeType: String,
        /** False for software encoders, which cannot sustain 4K30 alongside anything else. */
        val hardwareAccelerated: Boolean,
    )

    data class Report(
        /** What `Recorder` will actually use for UHD, from the device profile. */
        val profileCodec: String?,
        val profileResolution: String?,
        /** Every hardware encoder the device advertises for HEVC. */
        val hevcEncoders: List<Encoder>,
        val h264Encoders: List<Encoder>,
    ) {
        /**
         * The finding this issue exists for: the device can do HEVC in hardware,
         * and the profile chose not to.
         */
        val hevcAvailableButUnused: Boolean
            get() = hevcEncoders.any { it.hardwareAccelerated } &&
                profileCodec?.contains("hevc", ignoreCase = true) != true
    }

    /**
     * [cameraId] is the Camera2 id of the lens, from the capability probe
     * (ADR-0011) — profiles are per camera, and the front and rear cameras of one
     * device routinely differ.
     *
     * The profile is read from `CamcorderProfile.getAll` rather than from
     * CameraX: `VideoCapabilities.getProfiles` is `@RestrictTo` and lint fails
     * the build on it, and this is the same source CameraX itself consults.
     */
    fun of(cameraId: String): Report {
        val video = runCatching {
            CamcorderProfile.getAll(cameraId, CamcorderProfile.QUALITY_2160P)
                ?.videoProfiles
                ?.firstOrNull { it != null }
        }.getOrNull()

        val encoders = encoders()
        return Report(
            profileCodec = video?.mediaType,
            profileResolution = video?.let { "${it.width}x${it.height}" },
            hevcEncoders = encoders.filter { it.mimeType.equals("video/hevc", ignoreCase = true) },
            h264Encoders = encoders.filter { it.mimeType.equals("video/avc", ignoreCase = true) },
        )
    }

    private fun encoders(): List<Encoder> =
        MediaCodecList(MediaCodecList.REGULAR_CODECS).codecInfos
            .filter { it.isEncoder }
            .flatMap { info ->
                info.supportedTypes
                    .filter { it.startsWith("video/", ignoreCase = true) }
                    .map { type ->
                        Encoder(
                            name = info.name,
                            mimeType = type,
                            hardwareAccelerated = info.isHardwareAccelerated,
                        )
                    }
            }

    /** For pasting into #21 and ADR-0002, per the ROADMAP's rule about where a spike finishes. */
    fun markdown(report: Report): String = buildString {
        appendLine("UHD profile codec: `${report.profileCodec ?: "none"}` at ${report.profileResolution ?: "?"}")
        appendLine()
        appendLine("| Encoder | Type | Hardware |")
        appendLine("|---|---|---|")
        for (e in report.hevcEncoders + report.h264Encoders) {
            appendLine("| `${e.name}` | ${e.mimeType} | ${if (e.hardwareAccelerated) "yes" else "no"} |")
        }
        appendLine()
        appendLine(
            if (report.hevcAvailableButUnused) {
                "**Hardware HEVC exists and the profile did not choose it.** This is the gap " +
                    "CameraX 1.7's setVideoMimeType closes (#27)."
            } else {
                "No unused hardware HEVC encoder: the profile's choice is the best available."
            },
        )
    }
}
