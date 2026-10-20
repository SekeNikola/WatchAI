package com.jarvis.avatar

// The pose the renderer draws from — same parameters as Jarvis2.0
// desktop/src/jarvis/draw.ts `JarvisPose`, plus `eyeSwap` / `mouthSwap` so a
// shape change squashes out and back in instead of cutting.

enum class Eye {
    OPEN, WIDE, DOT, FOCUS, HAPPY, CLOSED, ANNOYED, LINE, TIRED, X, SPIRAL, HEART, STAR, WINK, QUESTION
}

enum class Mouth { NONE, SMILE, SMALL, GRIN, FLAT, FROWN, WAVY, TALK, O, CHEW, GULP }

enum class BadgeKind { NONE, DOTS, BANG, QUESTION, DOT, MAIL }

/** How much of the character to draw. */
enum class RenderStyle {
    /** Gradients, glow, sparkles. */
    FULL,
    /** Flat fills, no glow — small sizes. */
    LITE,
    /** Outline only on black, dim, burn-in safe. */
    AMBIENT,
}

/** Mutable on purpose: the engine fills one instance per frame, nothing is allocated. */
class JarvisPose {
    var r = 100f
    var sx = 1f
    var sy = 1f
    /** Whole-body rotation in radians. */
    var tilt = 0f
    /** Where the eyes look, -1…1, +y is down. */
    var lookX = 0f
    var lookY = 0f
    var eyes = Eye.OPEN
    var mouth = Mouth.SMILE
    /** 0…1 — how far `TALK`, `O`, `CHEW` and `GULP` mouths are open. */
    var mouthOpen = 0f
    /** Eyelid, 1 = open, ~0 = blink. */
    var open = 1f
    /** Eye scale. */
    var es = 1f
    /** 0…1 vertical squash of the eyes / mouth while their shape is being swapped. */
    var eyeSwap = 1f
    var mouthSwap = 1f
    var blush = 0.25f
    /** Face brightness, 1 = full. */
    var dim = 1f
    /** Antenna bulb colour, ARGB. */
    var bulb = Palette.MINT
    /** 0…1 glow strength of the bulb. */
    var pulse = 0.5f
    /** Antenna angle in radians, 0 = upright. */
    var antenna = 0f
    var badge = BadgeKind.NONE
    var badgeColor = Palette.MINT
    var badgeScale = 0f
    var listen = false
    var scan = false
    var dizzy = false
}

object Palette {
    const val MINT = 0xFF8CF5D2.toInt()
    const val BODY_TOP = 0xFFDCE2FF.toInt()
    const val BODY_MID = 0xFFAEBBFF.toInt()
    const val BODY_BOTTOM = 0xFF8193EE.toInt()
    const val VISOR_TOP = 0xFF1E2142.toInt()
    const val VISOR_BOTTOM = 0xFF0B0D1E.toInt()
    const val PINK = 0xFFFF8FB8.toInt()
    const val GOLD = 0xFFFFE27A.toInt()
    const val SPARK = 0xFF7FD8FF.toInt()
    const val SWEAT = 0xFF7FC8FF.toInt()
    const val ZZZ = 0xFFB9C2FF.toInt()
    const val BADGE_LIGHT = 0xFFF4F6FF.toInt()
    const val BADGE_INK = 0xFF1B1E3A.toInt()
}
