package com.scenaristo.camera.capture

import android.media.CamcorderProfile
import android.media.MediaCodecList
import com.scenaristo.camera.domain.protocol.Encoding
import com.scenaristo.camera.domain.protocol.VideoCodec

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
        /** The profile's own dimensions, or zero when there is no profile. */
        val profileWidth: Int = 0,
        val profileHeight: Int = 0,
    ) {
        /**
         * The finding this issue exists for: the device can do HEVC in hardware,
         * and the profile chose not to.
         */
        val hevcAvailableButUnused: Boolean
            get() = hevcEncoders.any { it.hardwareAccelerated } &&
                profileCodec?.contains("hevc", ignoreCase = true) != true

        /**
         * The codec, named rather than as a media-type string.
         *
         * The browser is shared with iOS and the two platforms spell the same
         * codec differently (ADR-0013). Anything that is neither HEVC nor AVC
         * becomes [VideoCodec.UNKNOWN] rather than being passed through: a codec
         * the app does not recognise is one PRD 6.7 does not promise, and naming
         * it in the interface would imply otherwise.
         */
        fun codec(): VideoCodec = when {
            profileCodec == null -> VideoCodec.UNKNOWN
            profileCodec.contains("hevc", ignoreCase = true) -> VideoCodec.HEVC
            profileCodec.contains("avc", ignoreCase = true) -> VideoCodec.H264
            else -> VideoCodec.UNKNOWN
        }

        /**
         * The report as the protocol carries it, for PRD 6.7's "codec in use is
         * displayed on phone and web before recording".
         *
         * **[frameRate] and [bitrate] are the caller's, and deliberately not the
         * profile's.** A `CamcorderProfile` describes the fastest mode it
         * supports, which is not the mode this app records in: on the reference
         * device the UHD profile declares 60 fps at 72 Mbit/s, while the session
         * pins `Range(30, 30)` (PRD 6.1) and #21 measured the resulting file at
         * 33.4 Mbit/s. Passing the profile's numbers through would put two
         * confident, wrong figures on the remote control's transport row, right
         * beside a minutes-remaining readout computed from the real one.
         *
         * The dimensions do come from the profile, because the session requires
         * `UHD_RECORDING` and fails the bind rather than falling back, so the
         * two cannot disagree.
         */
        fun encoding(frameRate: Int, bitrate: Int): Encoding = Encoding(
            codec = codec(),
            widthPx = profileWidth,
            heightPx = profileHeight,
            frameRate = frameRate,
            bitrate = bitrate,
        )
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
            profileWidth = video?.width ?: 0,
            profileHeight = video?.height ?: 0,
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
