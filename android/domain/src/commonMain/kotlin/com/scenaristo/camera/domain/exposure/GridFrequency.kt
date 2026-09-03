package com.scenaristo.camera.domain.exposure

import kotlinx.serialization.Serializable

/** Mains frequency, which fixes the flicker-free shutter ladder (PRD 6.2). */
@Serializable
enum class GridFrequency(val hz: Int) {
    HZ_50(50),
    HZ_60(60),
}

/**
 * The flicker-free shutter ladder, as reciprocal seconds (1/50 s is `50`).
 *
 * Rung 0 is the default for the grid. Rung 1 is the single step ADR-0005 allows
 * when the scene is overexposed at base ISO, and is also band-free; there is no
 * rung 2, because anything faster would band. Past rung 1 the app warns instead
 * of stepping again.
 */
fun shutterLadder(grid: GridFrequency): List<Int> = when (grid) {
    GridFrequency.HZ_50 -> listOf(50, 100)
    GridFrequency.HZ_60 -> listOf(60, 120)
}

/**
 * Countries whose grid is mixed. PRD 6.2 requires a prominent grid toggle for
 * these rather than trusting the majority default.
 */
val MIXED_GRID_REGIONS: Set<String> = setOf("JP", "BR", "SA")

/**
 * ISO 3166-1 alpha-2 region code to mains frequency, majority where mixed
 * (PRD 6.2). Returns null when the region is unknown, so the caller can fall
 * back through the MCC / region / timezone chain rather than guess.
 */
fun gridFrequencyForRegion(regionCode: String): GridFrequency? =
    when (regionCode.uppercase()) {
        "DE", "FR", "GB", "IT", "ES", "NL", "SE", "NO", "PL", "AU", "NZ", "IN", "CN", "ZA" ->
            GridFrequency.HZ_50
        "US", "CA", "MX", "PH", "KR", "TW", "CO", "VE" ->
            GridFrequency.HZ_60
        "JP" -> GridFrequency.HZ_50 // mixed 50/60; majority by population
        "BR" -> GridFrequency.HZ_60 // mixed historically; national standard is 60 Hz
        "SA" -> GridFrequency.HZ_60 // mixed; majority 60 Hz
        else -> null
    }
