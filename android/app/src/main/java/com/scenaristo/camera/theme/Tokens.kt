package com.scenaristo.camera.theme

import androidx.compose.ui.graphics.Color

/**
 * UI-11's tokens, converted once.
 *
 * The spec states four of these in oklch, which Compose has no type for, so they
 * are converted to sRGB here rather than at each use — and the oklch source is
 * kept in the comment, because that is the value the browser half of the same
 * design system will use verbatim (UI-9). If these two surfaces ever disagree
 * about what amber is, this is the line to check.
 */
object Tokens {
    /** The preview sits on this; almost nothing else does. */
    val Ground = Color(0xFF0A0B0C)

    /** Sheets and the translucent strips over the preview. */
    val Panel = Color(0xFF131519)

    val Text = Color(0xFFF2F0ED)

    /** UI-1: reported values, at 58 %. Their dimness is what says "not a button". */
    val Dim = Color(0xFFF2F0ED).copy(alpha = 0.58f)

    /** Labels and captions, at 34 %. */
    val Dimmer = Color(0xFFF2F0ED).copy(alpha = 0.34f)

    /** oklch(.80 .14 82) — "you can change this", and nothing else. */
    val Amber = Color(0xFFEAB444)

    /** oklch(.74 .165 48) — warning, always with an icon (UI-5). */
    val Orange = Color(0xFFFC8642)

    /** oklch(.63 .21 26) — recording, and nowhere else in the interface (UI-6). */
    val Red = Color(0xFFED403F)

    /** oklch(.76 .13 155) — nominal, and the audio meter's safe range. */
    val Green = Color(0xFF65C98C)
}
