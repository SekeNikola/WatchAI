package com.jarvis.avatar

import kotlin.math.pow

/** Easing curves, ported 1:1 from Jarvis2.0 desktop/src/core/anim.ts `Ease`. */
enum class Ease {
    OUT, IN_OUT, BACK, LIN, IN;

    fun apply(t: Float): Float = when (this) {
        OUT -> 1f - (1f - t).pow(3)
        IN_OUT -> if (t < 0.5f) 4f * t * t * t else 1f - (-2f * t + 2f).pow(3) / 2f
        BACK -> {
            val c1 = 1.7f
            val c3 = c1 + 1f
            1f + c3 * (t - 1f).pow(3) + c1 * (t - 1f).pow(2)
        }
        LIN -> t
        IN -> t * t * t
    }
}

internal fun lerp(a: Float, b: Float, t: Float) = a + (b - a) * t
internal fun clamp(v: Float, lo: Float, hi: Float) = if (v < lo) lo else if (v > hi) hi else v
