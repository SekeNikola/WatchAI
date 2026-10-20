package com.jarvis.avatar

// State table — ported from Jarvis2.0 desktop/src/jarvis/engine.ts `BOT_STATES`.
// An emotion is just a row of parameters; the engine eases between rows.
//
// To add an emotion: add an enum entry below with its parameters. If it needs a
// one-shot entrance (a hop, a spin), add a case in JarvisEngine.setState.

enum class BotState(
    /** Antenna bulb colour (the status light). */
    val color: Int,
    val eye: Eye,
    val mouth: Mouth,
    val badge: BadgeKind = BadgeKind.NONE,
    val bounces: Boolean = false,
    val scans: Boolean = false,
    val breathes: Boolean = false,
    val zz: Boolean = false,
    val sweat: Boolean = false,
    /** Fixed gaze (x, y in -1…1; +y is up, as in the desktop table), or NaN to let the eyes wander. */
    val lookX: Float = Float.NaN,
    val lookY: Float = Float.NaN,
    val tilt: Float = 0f,
    /** Floating bob: radians per second and amplitude in × R. */
    val floatSpeed: Float = 1.7f,
    val floatAmp: Float = 0.035f,
    /** Eyelid multiplier, 1 = fully open. */
    val lids: Float = 1f,
    /** Face brightness. */
    val dim: Float = 1f,
    val blush: Float = 0f,
    /** Antenna rest angle, NaN = the normal sway. */
    val antennaRest: Float = Float.NaN,
) {
    // ── Original Jarvis states ──────────────────────────────────────────────
    IDLE(MINT, Eye.OPEN, Mouth.SMILE),
    LISTENING(MINT, Eye.WIDE, Mouth.NONE, floatSpeed = 2.4f),
    SPEAKING(0xFF7FD8FF.toInt(), Eye.OPEN, Mouth.TALK),
    WORKING(0xFF3B9EFF.toInt(), Eye.FOCUS, Mouth.FLAT, BadgeKind.DOTS, lookX = 0.25f, lookY = -0.6f),
    THINKING(0xFFA78BFA.toInt(), Eye.OPEN, Mouth.SMALL, BadgeKind.DOTS, lookX = 0.55f, lookY = 0.6f, tilt = 0.1f),
    SEARCHING(0xFF2DD4BF.toInt(), Eye.OPEN, Mouth.SMALL, BadgeKind.DOTS, scans = true),
    APPROVAL(0xFFFFB547.toInt(), Eye.WIDE, Mouth.SMALL, BadgeKind.BANG, bounces = true),
    QUESTION(0xFFFFD166.toInt(), Eye.QUESTION, Mouth.SMALL, BadgeKind.QUESTION, tilt = -0.15f),
    ERROR(0xFFFF6B6B.toInt(), Eye.X, Mouth.FROWN, BadgeKind.DOT),
    FINISHED(0xFF6EE7A0.toInt(), Eye.HAPPY, Mouth.GRIN, BadgeKind.DOT),
    RATELIMIT(0xFFFF9F5A.toInt(), Eye.TIRED, Mouth.FLAT, BadgeKind.DOT, sweat = true, tilt = 0.06f, antennaRest = 0.45f),
    SLEEPING(
        0xFF6B7299.toInt(), Eye.CLOSED, Mouth.NONE, breathes = true, zz = true,
        floatSpeed = 0.9f, floatAmp = 0.02f, dim = 0.55f, antennaRest = 0.95f,
    ),
    DIZZY(0xFFFF8FB8.toInt(), Eye.SPIRAL, Mouth.WAVY),

    // ── Watch additions: proposed designs, built only from the parts above ──

    /** No interaction for a while: half-lidded, glancing aside, slow sighs, droopy antenna. */
    BORED(
        0xFF7FA8A0.toInt(), Eye.FOCUS, Mouth.FLAT, lookX = -0.55f, lookY = -0.35f, tilt = 0.07f,
        floatSpeed = 0.9f, floatAmp = 0.02f, lids = 0.75f, dim = 0.9f, antennaRest = 0.3f,
    ),

    /** Low battery: wide eyes looking up, wavy mouth, sweat drops, a nervous quick float. */
    WORRIED(
        0xFFFF9F5A.toInt(), Eye.WIDE, Mouth.WAVY, BadgeKind.DOT, sweat = true, lookX = 0f, lookY = 0.55f,
        tilt = -0.05f, floatSpeed = 3.4f, floatAmp = 0.018f,
    ),

    /** Poked too often: flat eyes under a slanted brow, flat mouth, amber light. */
    ANNOYED(0xFFFF9F5A.toInt(), Eye.ANNOYED, Mouth.FLAT, tilt = -0.05f, floatSpeed = 2.2f, floatAmp = 0.02f),

    /** Poked far too often: slanted brows, frown, flashing red light, a jittery float. */
    ANGRY(0xFFFF6B6B.toInt(), Eye.ANNOYED, Mouth.FROWN, BadgeKind.BANG, blush = 1f, floatSpeed = 4.5f, floatAmp = 0.012f, antennaRest = -0.15f),

    /** A notification arrived: the approval look with a letter in the bubble. */
    NOTIFY(0xFFFFB547.toInt(), Eye.WIDE, Mouth.SMALL, BadgeKind.MAIL, bounces = true),

    /** Fully charged: proud and content, steady green light, an occasional sparkle. */
    FULL(0xFF6EE7A0.toInt(), Eye.HAPPY, Mouth.GRIN, breathes = true, blush = 0.4f, floatSpeed = 1.2f, floatAmp = 0.03f),

    /** On the charger: happy eyes, grin, green light, breathing, blushing. */
    CHARGING(0xFF6EE7A0.toInt(), Eye.HAPPY, Mouth.GRIN, breathes = true, blush = 0.55f, floatSpeed = 1.4f),
}

private const val MINT = Palette.MINT

enum class Emote(val eye: Eye, val mouth: Mouth) {
    LOVE(Eye.HEART, Mouth.SMILE),
    SURPRISED(Eye.WIDE, Mouth.O),
    PROUD(Eye.STAR, Mouth.GRIN),
    WINK(Eye.WINK, Mouth.SMILE),
    YAWN(Eye.CLOSED, Mouth.GULP),
    HAPPY(Eye.HAPPY, Mouth.GRIN),
    ANNOYED(Eye.LINE, Mouth.FLAT),
}
