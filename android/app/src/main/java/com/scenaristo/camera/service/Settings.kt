package com.scenaristo.camera.service

import android.content.Context
import android.telephony.TelephonyManager
import com.scenaristo.camera.domain.exposure.GridFrequency
import com.scenaristo.camera.domain.exposure.GridSetting
import com.scenaristo.camera.domain.exposure.resolveGrid
import com.scenaristo.camera.domain.whitebalance.DEFAULT_KELVIN

/**
 * The handful of choices that outlive a launch (PRD 6.2, #2, #15).
 *
 * PRD 6.2 requires the mains-frequency override to persist per device, and #2
 * requires the same of white balance: "given a manual override of grid, ISO, or
 * white balance, the override survives an app relaunch". Without this the app
 * greets a user in Japan with the wrong shutter every single time they open it,
 * however many times they have corrected it.
 *
 * `SharedPreferences` rather than a file or a database: three values, read once
 * at start-up, written when the user changes something. Its commit-on-write is
 * exactly the durability this needs — a setting the user changed and the app
 * then crashed on should still be there.
 */
class Settings(context: Context) {

    private val prefs = context.getSharedPreferences("capture", Context.MODE_PRIVATE)
    private val app = context.applicationContext

    /**
     * The user's grid override, or null when they have never set one.
     *
     * Null and "50 Hz" are different states and the difference is load-bearing:
     * a user who has never chosen gets detection, and a user who chose 50 Hz in a
     * 60 Hz country gets 50 Hz. Storing a plain frequency would lose that and
     * silently promote the first launch's guess into a decision.
     */
    var gridOverride: GridFrequency?
        get() = prefs.getString(KEY_GRID, null)?.let { name ->
            GridFrequency.entries.firstOrNull { it.name == name }
        }
        set(value) = prefs.edit().apply {
            if (value == null) remove(KEY_GRID) else putString(KEY_GRID, value.name)
        }.apply()

    var whiteBalanceKelvin: Int
        get() = prefs.getInt(KEY_KELVIN, DEFAULT_KELVIN)
        set(value) = prefs.edit().putInt(KEY_KELVIN, value).apply()

    /**
     * Whether takes go to the shared gallery (PRD 6.7, ADR-0020).
     *
     * Off by default (decision 2026-09-05, Davide): the app writes into its own
     * folder like any other app, and a creator who wants their takes in the
     * gallery asks for it. Filling someone's photo roll with multi-gigabyte
     * files is not a default to choose on their behalf.
     */
    var saveToGallery: Boolean
        get() = prefs.getBoolean(KEY_GALLERY, false)
        set(value) = prefs.edit().putBoolean(KEY_GALLERY, value).apply()

    /**
     * Whether exposure is held for the whole take (ADR-0023).
     *
     * Off by default: the tracking loop is what every take has had so far, and a
     * mode whose failure is an unrecoverable file is not one to opt someone into
     * on their behalf. Stored rather than per-session because the answer is a
     * property of how someone shoots, not of one take.
     */
    var lockExposureWhileRecording: Boolean
        get() = prefs.getBoolean(KEY_EXPOSURE_LOCK, false)
        set(value) = prefs.edit().putBoolean(KEY_EXPOSURE_LOCK, value).apply()

    var lensId: String
        get() = prefs.getString(KEY_LENS, DEFAULT_LENS) ?: DEFAULT_LENS
        set(value) = prefs.edit().putString(KEY_LENS, value).apply()

    /**
     * The framing in use, as a zoom ratio (PRD 6.5, #77).
     *
     * Persisted like every other thing the user chooses. Someone who framed up
     * at 2x and came back to the app would otherwise find themselves at 1x
     * having changed nothing -- and it is the one setting whose loss is
     * invisible until you look at the shot.
     *
     * A float because `SharedPreferences` has no double, widened on the way out.
     * The ratios come from the camera and are floats there too, so nothing is
     * lost. A stored ratio the device no longer offers is caught by `Session`,
     * which validates against the framings the camera reported this time.
     */
    var zoomRatio: Double
        get() = prefs.getFloat(KEY_ZOOM, DEFAULT_ZOOM).toDouble()
        set(value) = prefs.edit().putFloat(KEY_ZOOM, value.toFloat()).apply()

    /**
     * PRD 6.2's detection chain, with the stored override in front of it.
     *
     * This is where `resolveGrid` finally gets called. Until now the grid was the
     * constant `HZ_50`, so a user in the United States recorded at 1/50 s under
     * 60 Hz light and got the banding the whole product exists to prevent —
     * the table and the chain were written, merged and never wired up.
     *
     * The SIM's country comes first because it says where the phone *is*; the
     * device region says where its owner is from, which is usually but not always
     * the same. Timezone is passed as null: neither platform maps a zone to a
     * country, and `:domain` will not invent a table for it.
     */
    fun grid(): GridSetting = resolveGrid(
        override = gridOverride,
        simRegion = simRegion(),
        deviceRegion = app.resources.configuration.locales[0]?.country,
    )

    /**
     * The SIM's country, or null.
     *
     * `getSimCountryIso` is already an ISO 3166-1 code, so no MCC table is needed
     * here — which is exactly why `resolveGrid` takes region codes rather than
     * MCCs. Empty on a phone with no SIM, which is a null rather than a guess.
     */
    private fun simRegion(): String? = runCatching {
        app.getSystemService(TelephonyManager::class.java)?.simCountryIso
    }.getOrNull()?.takeIf { it.isNotBlank() }

    private companion object {
        const val KEY_GRID = "grid-override"
        const val KEY_KELVIN = "white-balance-kelvin"
        const val KEY_LENS = "lens-id"
        const val KEY_GALLERY = "save-to-gallery"
        const val KEY_EXPOSURE_LOCK = "lock-exposure-while-recording"
        const val KEY_ZOOM = "zoom-ratio"

        /** PRD 6.1's default camera, as the platform's own id for it. */
        const val DEFAULT_LENS = "0"

        /** 1x is the base lens, which is where a phone camera starts. */
        const val DEFAULT_ZOOM = 1.0f
    }
}
