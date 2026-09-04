package com.scenaristo.camera.domain.exposure

/**
 * Where the mains frequency in use came from (PRD 6.2).
 *
 * The UI needs this and not just the answer: PRD 6.2 requires that "while the
 * override is active, the UI shows that the grid was set manually rather than
 * detected". A user in a mixed-grid country who has already corrected the app
 * once needs to see that their correction survived, and a user who has never
 * touched it needs to see that the app worked it out — those are different
 * sentences, and only the source tells them apart.
 */
enum class GridSource {
    /** The user said so, on the phone or in the browser. Beats every detection. */
    MANUAL_OVERRIDE,

    /** The SIM's country, which is PRD 6.2's first choice: it says where the phone *is*. */
    SIM,

    /** The device's region setting. Says where the owner is from, which is usually the same. */
    DEVICE_REGION,

    /** The timezone's country. Last and weakest, and often a whole continent wide. */
    TIMEZONE,

    /** Nothing resolved. The app still has to pick a shutter, so it picks one and says so. */
    FALLBACK,
}

/**
 * The grid frequency in use, and how it was arrived at.
 *
 * [region] is the ISO 3166-1 alpha-2 code that decided it, or null for a manual
 * override or a fallback — nothing decided those.
 */
data class GridSetting(
    val grid: GridFrequency,
    val source: GridSource,
    val region: String? = null,
) {
    /** PRD 6.2: the UI says "50 Hz" versus "50 Hz (set manually)". */
    val detected: Boolean get() = source != GridSource.MANUAL_OVERRIDE

    /**
     * PRD 6.2: mixed-grid countries "default to the majority frequency and
     * surface a prominent 'Grid: 50 Hz / 60 Hz' toggle" — the toggle exists
     * everywhere, but here it is the difference between a correct take and a
     * banded one, so it is not buried in a settings sheet.
     *
     * Still true once the user has overridden: they are still in Japan, and the
     * control they just used is the one they may need to use again.
     */
    val prominentToggle: Boolean get() = region in MIXED_GRID_REGIONS
}

/**
 * PRD 6.2's detection chain: "SIM country code (MCC), then device region
 * setting, then timezone", with a manual override in front of all of it.
 *
 * Each source is an ISO 3166-1 alpha-2 region code that the platform has already
 * resolved — Android reads the SIM's directly from `TelephonyManager`, so no MCC
 * table is needed here, and neither platform offers a timezone-to-country
 * mapping, which stays the caller's problem rather than becoming an invented
 * table in `:domain`.
 *
 * A source that is present but *unrecognised* falls through to the next one
 * rather than stopping the chain. A traveller with a foreign SIM in a country
 * the table does not list should still get their device region's answer, rather
 * than the fallback.
 *
 * [fallback] is what happens when nothing resolves at all — no SIM, an unlisted
 * region, an unmapped timezone. It is deliberately a parameter with a stated
 * default rather than a constant buried in the chain, because which frequency to
 * guess for an unknown country is a product call and not an arithmetic one.
 */
fun resolveGrid(
    override: GridFrequency? = null,
    simRegion: String? = null,
    deviceRegion: String? = null,
    timezoneRegion: String? = null,
    fallback: GridFrequency = DEFAULT_GRID,
): GridSetting {
    if (override != null) {
        // The region still matters after an override: it is what decides whether
        // the toggle stays prominent, and the user has not moved country by
        // pressing it.
        return GridSetting(
            grid = override,
            source = GridSource.MANUAL_OVERRIDE,
            region = firstKnownRegion(simRegion, deviceRegion, timezoneRegion),
        )
    }

    detect(simRegion, GridSource.SIM)?.let { return it }
    detect(deviceRegion, GridSource.DEVICE_REGION)?.let { return it }
    detect(timezoneRegion, GridSource.TIMEZONE)?.let { return it }
    return GridSetting(grid = fallback, source = GridSource.FALLBACK, region = null)
}

private fun detect(region: String?, source: GridSource): GridSetting? {
    val code = region?.takeIf { it.isNotBlank() } ?: return null
    val grid = gridFrequencyForRegion(code) ?: return null
    return GridSetting(grid = grid, source = source, region = code.uppercase())
}

/** The first region the table recognises, for a setting whose grid came from elsewhere. */
private fun firstKnownRegion(vararg candidates: String?): String? = candidates
    .asSequence()
    .filterNotNull()
    .map { it.uppercase() }
    .firstOrNull { gridFrequencyForRegion(it) != null }

/**
 * What to assume when no source resolves.
 *
 * 50 Hz, on the single ground that more of the world runs on it. The failure is
 * symmetric -- 1/50 s under 60 Hz light bands, and so does 1/60 s under 50 Hz,
 * because neither exposure is a whole multiple of the other grid's half-period
 * -- so there is no safe guess here, only a less often wrong one. What actually
 * protects the user is the toggle PRD 6.2 puts in front of them, not this.
 *
 * The PRD does not state this default; it describes a chain and stops. Recorded
 * here as a choice rather than left implicit.
 */
private val DEFAULT_GRID = GridFrequency.HZ_50
