package com.jarvis.avatar

import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min
import kotlin.math.pow
import kotlin.math.sin
import kotlin.random.Random

// Jarvis's animation engine, ported from Jarvis2.0 desktop/src/jarvis/engine.ts
// (itself based on BotEngine, MIT). Same tweens, springs and timings.
//
// Watch-specific additions: a floating bob, an idle look-around (the desktop
// eyes follow the mouse), and eye/mouth shape swaps that squash out and back
// in so a change of expression is never a hard cut.
//
// Drive it with update(nowSeconds) once per frame, then pose(r, out).
// Steady-state frames allocate nothing; triggering an action allocates its keyframes.

enum class ParticleType { HEART, STAR, SPARK, SWEAT, Z, NOTE, BOLT }

class Particle {
    var alive = false
    var type = ParticleType.SPARK
    var x = 0f; var y = 0f; var vx = 0f; var vy = 0f
    var age = 0f; var life = 0f; var rot = 0f; var size = 0f
}

class JarvisEngine {
    // ── Public, readable state ──────────────────────────────────────────────

    var state = BotState.IDLE
        private set

    /** 0 = wide awake … 1 = late night: heavier lids and a dimmer face while idle. */
    var drowsy = 0f

    /** Shows the envelope badge (unread notifications) whenever the state has no badge of its own. */
    var mail = false

    /** Music is playing: notes float out of his head and he sways to it. */
    var music = false

    /** Lets the eyes drift around on their own. */
    var wander = true

    val particles = Array(28) { Particle() }

    // ── Animated values ─────────────────────────────────────────────────────

    private val v = FloatArray(Prop.entries.size).also {
        it[Prop.OPEN.ordinal] = 1f; it[Prop.SX.ordinal] = 1f; it[Prop.SY.ordinal] = 1f; it[Prop.ES.ordinal] = 1f
    }
    private val tweens = arrayOfNulls<Tween>(Prop.entries.size)

    private var tgYaw = 0f; private var tgPitch = 0f; private var tgTilt = 0f
    private var tgSx = 1f; private var tgSy = 1f
    var tgEs = 1f

    private var col = FloatArray(3).also { rgb(BotState.IDLE.color, it) }
    private val colT = FloatArray(3).also { rgb(BotState.IDLE.color, it) }

    private var antenna = 0f
    private var antennaVel = 0f

    private var floatPhase = 0f
    private var floatSpeed = BotState.IDLE.floatSpeed
    private var floatAmp = BotState.IDLE.floatAmp
    private var lids = 1f
    private var dimS = 1f
    private var blushBase = 0f

    private var eyeOverride: Eye? = null
    private var eyeOverrideUntil = 0f
    private var emote: Emote? = null
    private var emoteT0 = 0f
    private var emoteUntil = 0f

    private var badge = BadgeKind.NONE
    private var badgeColor = Palette.MINT
    private var badgeToken = 0
    private var nextNote = 0f
    private var nextBolt = 0f
    private var groove = 0f
    private var mailS = 0f
    private var mailV = 0f

    // Shape swaps: what is drawn vs. what is wanted.
    private var shownEye = Eye.OPEN
    private var wantEye = Eye.OPEN
    private var eyeSwap = 1f
    private var eyeSwapPhase = 0 // 0 idle, 1 closing, 2 opening
    private var eyeSwapU = 0f
    private var shownMouth = Mouth.SMILE
    private var wantMouth = Mouth.SMILE
    private var mouthSwap = 1f
    private var mouthSwapPhase = 0
    private var mouthSwapU = 0f
    private var mouthOpen = 0f

    private var lookTx = 0f; private var lookTy = 0f
    private var lookNext = 0f
    private var lookingAway = false

    // ── Clock ───────────────────────────────────────────────────────────────

    private var n = 0f // now, seconds
    private var t0 = -Random.nextFloat() * 5f
    private var nextBlink = 1.5f + Random.nextFloat() * 2f
    private var lastAmbient = 0f
    private var started = false

    private val timers = arrayOfNulls<Timer>(16)

    // ── Public API ──────────────────────────────────────────────────────────

    fun setState(next: BotState, force: Boolean = false) {
        if (state == next && !force) return
        val prev = state
        state = next
        rgb(next.color, colT)
        setBadge(next.badge, next.color)
        when (next) {
            BotState.FINISHED, BotState.CHARGING -> {
                doRoll(0.95f, 1)
                later(0.5f) { emit(ParticleType.SPARK, 5) }
            }
            BotState.ERROR -> anim(Prop.OX, K(0.08f, 50, Ease.OUT), K(-0.08f, 70), K(0.05f, 70), K(0f, 90, Ease.OUT))
            BotState.APPROVAL, BotState.NOTIFY -> anim(Prop.OY, K(-0.2f, 150, Ease.OUT), K(0f, 300, Ease.BACK))
            BotState.DIZZY -> doRoll(1.3f, 2)
            BotState.QUESTION -> blink()
            BotState.RATELIMIT -> emit(ParticleType.SWEAT, 1)
            BotState.WORRIED -> {
                emit(ParticleType.SWEAT, 1)
                anim(Prop.OX, K(0.04f, 60, Ease.OUT), K(-0.04f, 80), K(0.03f, 80), K(0f, 100, Ease.OUT))
            }
            BotState.BORED -> sigh()
            BotState.ANNOYED -> { blink(); shake(0.04f) }
            BotState.ANGRY -> { shake(0.09f); antennaVel += 9f }
            BotState.FULL -> {
                // Celebration: a spin with sparks, then star eyes and stars.
                doRoll(0.95f, 1)
                later(0.5f) { emit(ParticleType.SPARK, 6) }
                later(1.0f) { triggerEmote(Emote.PROUD, 2.2f) }
            }
            else -> if (prev != BotState.IDLE || next != BotState.IDLE) blink()
        }
    }

    fun blink() {
        if (tweens[Prop.OPEN.ordinal] != null) return
        anim(Prop.OPEN, K(0.06f, 70), K(1f, 130, Ease.OUT))
    }

    /** A squashy hop — the desktop "slap" reaction; used for taps. */
    fun squash() {
        antennaVel += 7f
        anim(Prop.SY, K(0.78f, 70, Ease.OUT), K(1.1f, 130, Ease.OUT), K(1f, 170))
        anim(Prop.SX, K(1.16f, 70, Ease.OUT), K(0.95f, 130, Ease.OUT), K(1f, 170))
    }

    /** A side-to-side shake; [amp] in x R. */
    fun shake(amp: Float = 0.06f) {
        anim(Prop.OX, K(amp, 50, Ease.OUT), K(-amp, 70), K(amp * 0.6f, 70), K(-amp * 0.3f, 60), K(0f, 90, Ease.OUT))
    }

    /** A slow sigh: stretch up, sink, settle. Used while bored. */
    fun sigh() {
        anim(Prop.SY, K(1.07f, 700), K(0.95f, 600), K(1f, 500))
        anim(Prop.SX, K(0.96f, 700), K(1.04f, 600), K(1f, 500))
        antennaVel -= 2f
    }

    fun triggerEmote(e: Emote, duration: Float = 1.8f) {
        eyeOverride = e.eye
        eyeOverrideUntil = n + duration
        emote = e
        emoteT0 = n
        emoteUntil = n + duration
        val holdMs = ((duration - 0.6f) * 1000).toInt().coerceAtLeast(0)
        when (e) {
            Emote.LOVE -> {
                anim(Prop.BLUSH, K(1f, 300, Ease.OUT), K(1f, holdMs, Ease.LIN), K(0f, 300))
                emit(ParticleType.HEART, 4)
                anim(Prop.OY, K(-0.1f, 160, Ease.OUT), K(0f, 300, Ease.BACK))
            }
            Emote.SURPRISED -> {
                anim(Prop.OY, K(-0.3f, 140, Ease.OUT), K(0f, 380, Ease.BACK))
                anim(Prop.ES, K(1.25f, 120, Ease.OUT), K(1f, 500))
            }
            Emote.PROUD -> {
                emit(ParticleType.STAR, 5)
                anim(Prop.TILT, K(-0.14f, 220, Ease.OUT), K(-0.14f, holdMs + 100, Ease.LIN), K(0f, 280))
                anim(Prop.BLUSH, K(0.7f, 250, Ease.OUT), K(0.7f, holdMs + 100, Ease.LIN), K(0f, 300))
            }
            Emote.WINK -> anim(Prop.TILT, K(0.12f, 160, Ease.OUT), K(0.12f, holdMs + 200, Ease.LIN), K(0f, 240))
            Emote.YAWN -> {
                anim(Prop.SY, K(1.12f, 500), K(1f, 500))
                anim(Prop.SX, K(0.94f, 500), K(1f, 500))
                later(0.7f) { eyeOverride = Eye.CLOSED; emit(ParticleType.Z, 2) }
            }
            Emote.HAPPY -> anim(Prop.BLUSH, K(0.6f, 200, Ease.OUT), K(0f, 600))
            Emote.ANNOYED -> eyeOverrideUntil = n + 0.8f
        }
    }

    fun emit(type: ParticleType, count: Int) {
        var made = 0
        for (p in particles) {
            if (made >= count) break
            if (p.alive) continue
            val isZ = type == ParticleType.Z
            val isNote = type == ParticleType.NOTE || type == ParticleType.BOLT
            p.alive = true
            p.type = type
            p.x = (Random.nextFloat() - 0.5f) * 0.9f + (if (isZ) 0.55f else 0f)
            p.y = -0.7f - Random.nextFloat() * 0.2f
            p.vx = (Random.nextFloat() - 0.5f) * (if (isNote) 0.7f else 0.35f) + (if (isZ) 0.18f else 0f)
            p.vy = -(0.45f + Random.nextFloat() * 0.35f)
            p.age = -made * 0.14f
            p.life = 1.3f + Random.nextFloat() * 0.5f
            p.rot = Random.nextFloat() * PI.toFloat() * 2f
            p.size = 0.15f + Random.nextFloat() * 0.08f
            if (isNote) p.rot = Random.nextFloat() * 3f // picks colour + note shape
            made++
        }
    }

    /** Drops in-flight one-shots, e.g. after the screen was off for a while. */
    fun settle() {
        for (p in particles) p.alive = false
        for (i in tweens.indices) tweens[i] = null
        for (i in timers.indices) timers[i] = null
        v[Prop.ROLL.ordinal] = 0f; v[Prop.OY.ordinal] = 0f; v[Prop.OX.ordinal] = 0f
        v[Prop.OPEN.ordinal] = 1f
        badgeToken++
        badge = state.badge
        badgeColor = state.color
        v[Prop.BADGE_S.ordinal] = if (badge == BadgeKind.NONE) 0f else 1f
        mailS = if (mail) 1f else 0f; mailV = 0f
        eyeOverride = null; emote = null
    }

    /**
     * Settles and restarts the clock, so the caller can feed small times again.
     * Float seconds lose precision after a few days of uptime.
     */
    fun restartClock() {
        settle()
        started = false
        t0 = -Random.nextFloat() * 5f
        nextBlink = 1.5f + Random.nextFloat() * 2f
        lookNext = 0f
    }

    // ── Update ──────────────────────────────────────────────────────────────

    fun update(nowSec: Float) {
        if (!started) { started = true; n = nowSec; t0 += nowSec; nextBlink += nowSec; lastAmbient = nowSec; lookNext = nowSec }
        // Long gaps (screen was off) are clamped so springs never explode.
        val dt = min(0.05f, max(0f, nowSec - n))
        n = nowSec
        val cfg = state

        runTimers()
        runTweens()

        val t = n - t0

        // Gaze: wander on its own, or the state's fixed look.
        var ty: Float
        var tp: Float
        if (wander && n > lookNext) {
            if (lookingAway) {
                lookTx = 0f; lookTy = 0f
                lookNext = n + 2.5f + Random.nextFloat() * 4.5f
            } else {
                lookTx = -0.88f + Random.nextFloat() * 1.76f
                lookTy = -0.55f + Random.nextFloat() * 1.0f
                lookNext = n + 0.8f + Random.nextFloat() * 1.4f
            }
            lookingAway = !lookingAway
        }
        ty = if (wander) lookTx * 0.62f else 0f
        tp = if (wander) lookTy * 0.5f else 0f
        if (!cfg.lookX.isNaN()) {
            ty = ty * 0.35f + cfg.lookX * 0.55f
            tp = tp * 0.3f + cfg.lookY * 0.5f
        }
        if (cfg.scans) { ty = sin(t * 2.6f) * 0.6f; tp = -0.06f }
        if (cfg == BotState.SLEEPING) { ty = 0f; tp = -0.14f }
        if (cfg == BotState.DIZZY) ty = sin(t * 9f) * 0.25f
        tgYaw = ty
        tgPitch = tp
        tgTilt = cfg.tilt

        // Music: a gentle sway (~100 bpm) that fades in and out, notes every ~0.8 s.
        groove += ((if (music) 1f else 0f) - groove) * (1f - 0.05f.pow(dt))
        if (groove > 0.01f) tgTilt += sin(t * 5.2f) * 0.07f * groove
        if (cfg == BotState.CHARGING && n > nextBolt) {
            emit(ParticleType.BOLT, 1)
            nextBolt = n + 0.7f + Random.nextFloat() * 0.6f
        }
        if (music && n > nextNote && cfg != BotState.SLEEPING) {
            emit(ParticleType.NOTE, 1)
            nextNote = n + 0.6f + Random.nextFloat() * 0.5f
        }

        val kGen = 1f - 0.0008f.pow(dt)
        val bounce = if (cfg.bounces) -abs(sin(t * 5.2f)) * 0.07f else 0f
        if (tweens[Prop.OY.ordinal] == null) v[Prop.OY.ordinal] += (bounce - v[Prop.OY.ordinal]) * kGen

        if (cfg.breathes) {
            tgSy = 1f + sin(t * 1.8f) * 0.035f
            tgSx = 1f - sin(t * 1.8f) * 0.035f * 0.57f
        } else {
            tgSy = 1f; tgSx = 1f
        }

        // Float speed/amplitude, lids, dim and blush glide to the state's values.
        floatSpeed += (cfg.floatSpeed - floatSpeed) * kGen
        floatAmp += (cfg.floatAmp - floatAmp) * kGen
        floatPhase += dt * floatSpeed
        lids += (cfg.lids - lids) * kGen
        dimS += (cfg.dim - dimS) * kGen
        blushBase += (cfg.blush - blushBase) * kGen

        val kLook = 1f - 0.0025f.pow(dt)
        ease(Prop.YAW, tgYaw, kLook)
        ease(Prop.PITCH, tgPitch, kLook)
        ease(Prop.TILT, tgTilt, kGen)
        ease(Prop.SY, tgSy, kGen)
        ease(Prop.SX, tgSx, kGen)
        ease(Prop.ES, tgEs, kGen)

        val kc = 1f - 0.002f.pow(dt)
        for (i in 0..2) col[i] += (colT[i] - col[i]) * kc

        if (n > nextBlink) {
            if (cfg != BotState.SLEEPING && cfg != BotState.DIZZY) {
                blink()
                if (Random.nextFloat() < 0.22f) later(0.23f) { blink() }
            }
            nextBlink = n + 2.2f + Random.nextFloat() * 3.2f
        }

        if (eyeOverride != null && n > eyeOverrideUntil) eyeOverride = null

        if (n - lastAmbient > 1.3f) {
            lastAmbient = n
            if (cfg.zz) emit(ParticleType.Z, 1)
            if (cfg.sweat && Random.nextFloat() < 0.5f) emit(ParticleType.SWEAT, 1)
            if (cfg == BotState.FULL && Random.nextFloat() < 0.25f) emit(ParticleType.SPARK, 1)
            if (cfg == BotState.BORED && Random.nextFloat() < 0.12f) {
                if (Random.nextFloat() < 0.3f) triggerEmote(Emote.YAWN, 2.2f) else sigh()
            }
        }

        for (p in particles) {
            if (!p.alive) continue
            p.age += dt
            if (p.age >= p.life) p.alive = false
        }

        // Antenna: a loose spring that sways, leans against the tilt, droops when tired.
        var antT = sin(t * 1.3f) * 0.08f - v[Prop.TILT.ordinal] * 0.5f
        if (!cfg.antennaRest.isNaN()) antT = cfg.antennaRest + sin(t * 0.9f) * 0.05f
        antennaVel += (-90f * (antenna - antT) - 5f * antennaVel) * dt
        antenna += antennaVel * dt

        if (emote != null && n > emoteUntil) emote = null

        // Envelope badge: a springy pop in and out.
        val mailT = if (mail) 1f else 0f
        mailV += (220f * (mailT - mailS) - 18f * mailV) * dt
        mailS = max(0f, mailS + mailV * dt)

        resolveFace(t)
        stepSwaps(dt)
    }

    /** The pose the renderer needs, derived from the animated state. */
    fun pose(r: Float, out: JarvisPose): JarvisPose {
        val lt = n - t0
        val st = state
        var pulse = 0.5f + 0.5f * sin(lt * 2f)
        when (st) {
            BotState.APPROVAL, BotState.NOTIFY -> pulse = if (sin(lt * 6f) > 0) 1f else 0.2f
            BotState.ERROR -> pulse = if (sin(lt * 10f) > 0) 1f else 0.3f
            BotState.LISTENING -> pulse = 0.5f + 0.5f * sin(lt * 8f)
            BotState.SPEAKING -> pulse = mouthOpen
            BotState.WORKING -> pulse = 0.5f + 0.5f * sin(lt * 12f)
            BotState.SLEEPING -> pulse = 0.15f
            BotState.WORRIED -> pulse = if (sin(lt * 3f) > 0) 0.9f else 0.25f
            BotState.BORED -> pulse = 0.25f + 0.15f * sin(lt)
            BotState.CHARGING -> pulse = 0.5f + 0.5f * sin(lt * 2.5f)
            BotState.FULL -> pulse = 0.85f
            BotState.ANGRY -> pulse = if (sin(lt * 9f) > 0) 1f else 0.35f
            else -> Unit
        }
        val open = v[Prop.OPEN.ordinal] * lids
        out.r = r
        out.sx = v[Prop.SX.ordinal]
        out.sy = v[Prop.SY.ordinal]
        out.tilt = v[Prop.TILT.ordinal] + v[Prop.ROLL.ordinal]
        out.lookX = clamp(v[Prop.YAW.ordinal] / 0.62f, -1f, 1f)
        out.lookY = clamp(-v[Prop.PITCH.ordinal] / 0.5f, -1f, 1f)
        out.eyes = shownEye
        out.mouth = shownMouth
        out.mouthOpen = mouthOpen
        out.open = if (st == BotState.IDLE) open * (1f - 0.3f * drowsy) else open
        out.es = v[Prop.ES.ordinal]
        out.eyeSwap = eyeSwap
        out.mouthSwap = mouthSwap
        out.blush = max(v[Prop.BLUSH.ordinal], blushBase)
        out.dim = dimS * (if (st == BotState.IDLE) 1f - 0.14f * drowsy else 1f)
        out.bulb = argb(col)
        out.pulse = pulse
        out.antenna = antenna
        out.badge = badge
        out.badgeColor = badgeColor
        out.badgeScale = v[Prop.BADGE_S.ordinal]
        if (badge == BadgeKind.NONE && mailS > 0.01f) {
            out.badge = BadgeKind.MAIL
            out.badgeColor = MAIL_COLOR
            out.badgeScale = mailS
        }
        out.listen = st == BotState.LISTENING
        out.scan = st.scans
        out.dizzy = st == BotState.DIZZY
        return out
    }

    /**
     * True when nothing but the idle loop (float, blink, sway) is running: no tweens,
     * particles, emotes or shape swaps. The renderer drops the frame rate then.
     */
    val calm: Boolean
        get() {
            if (emote != null || eyeSwapPhase != 0 || mouthSwapPhase != 0) return false
            if (abs(antennaVel) > 0.3f || groove > 0.01f) return false
            if (abs(mailS - (if (mail) 1f else 0f)) > 0.02f) return false
            for (tw in tweens) if (tw != null && tw !== tweens[Prop.OPEN.ordinal]) return false
            for (p in particles) if (p.alive) return false
            return !state.bounces && !state.scans && state != BotState.DIZZY && state != BotState.ANGRY
        }

    /** Body offset from the layout centre, in × R (float bob + hops + shakes). */
    val offsetX: Float get() = v[Prop.OX.ordinal]
    val offsetY: Float get() = v[Prop.OY.ordinal] + sin(floatPhase) * floatAmp

    // ── Internals ───────────────────────────────────────────────────────────

    private fun resolveFace(lt: Float) {
        val eye = eyeOverride ?: state.eye
        var mouth = state.mouth
        val e = emote
        val eyeMouth = if (eyeOverride != null) mouthForEye(eye) else null
        if (e != null) mouth = e.mouth else if (eyeMouth != null) mouth = eyeMouth

        mouthOpen = when {
            e == Emote.YAWN -> sin(min(1f, (n - emoteT0) / (emoteUntil - emoteT0)) * PI.toFloat()) * 0.95f
            mouth == Mouth.O -> 0.7f
            mouth == Mouth.TALK -> syntheticVoice(lt)
            else -> 0f
        }
        wantEye = eye
        wantMouth = mouth
    }

    /** Squash the old shape out (80 ms), swap, spring the new one in (160 ms). */
    private fun stepSwaps(dt: Float) {
        if (eyeSwapPhase == 0 && wantEye != shownEye) { eyeSwapPhase = 1; eyeSwapU = 0f }
        if (eyeSwapPhase == 1) {
            eyeSwapU += dt / 0.08f
            eyeSwap = 1f - Ease.IN_OUT.apply(min(1f, eyeSwapU))
            if (eyeSwapU >= 1f) { shownEye = wantEye; eyeSwapPhase = 2; eyeSwapU = 0f }
        } else if (eyeSwapPhase == 2) {
            eyeSwapU += dt / 0.16f
            eyeSwap = Ease.BACK.apply(min(1f, eyeSwapU))
            if (eyeSwapU >= 1f) { eyeSwap = 1f; eyeSwapPhase = 0 }
        }
        if (mouthSwapPhase == 0 && wantMouth != shownMouth) { mouthSwapPhase = 1; mouthSwapU = 0f }
        if (mouthSwapPhase == 1) {
            mouthSwapU += dt / 0.08f
            mouthSwap = 1f - Ease.IN_OUT.apply(min(1f, mouthSwapU))
            if (mouthSwapU >= 1f) { shownMouth = wantMouth; mouthSwapPhase = 2; mouthSwapU = 0f }
        } else if (mouthSwapPhase == 2) {
            mouthSwapU += dt / 0.16f
            mouthSwap = Ease.BACK.apply(min(1f, mouthSwapU))
            if (mouthSwapU >= 1f) { mouthSwap = 1f; mouthSwapPhase = 0 }
        }
    }

    private fun setBadge(kind: BadgeKind, color: Int) {
        if (kind == badge && color == badgeColor) return
        val tok = ++badgeToken
        anim(Prop.BADGE_S, K(0f, 90))
        later(0.1f) {
            if (tok != badgeToken) return@later
            badge = kind
            badgeColor = color
            if (kind != BadgeKind.NONE) anim(Prop.BADGE_S, K(1f, 280, Ease.BACK))
        }
    }

    private fun doRoll(seconds: Float, turns: Int) {
        v[Prop.ROLL.ordinal] = 0f
        anim(Prop.ROLL, K(PI.toFloat() * 2f * turns, (seconds * 1000).toInt())) { v[Prop.ROLL.ordinal] = 0f }
    }

    private fun ease(p: Prop, target: Float, k: Float) {
        if (tweens[p.ordinal] == null) v[p.ordinal] += (target - v[p.ordinal]) * k
    }

    private fun anim(p: Prop, vararg keys: K, onComplete: (() -> Unit)? = null) {
        tweens[p.ordinal] = Tween(keys, v[p.ordinal], n, onComplete)
    }

    private fun runTweens() {
        for (i in tweens.indices) {
            val tw = tweens[i] ?: continue
            val k = tw.keys[tw.index]
            val p = if (k.sec <= 0f) 1f else clamp((n - tw.start) / k.sec, 0f, 1f)
            v[i] = tw.from + (k.target - tw.from) * k.ease.apply(p)
            if (p >= 1f) {
                tw.from = k.target
                tw.index++
                tw.start = n
                if (tw.index >= tw.keys.size) {
                    tweens[i] = null
                    tw.onComplete?.invoke()
                }
            }
        }
    }

    private fun later(delay: Float, fn: () -> Unit) {
        for (i in timers.indices) {
            if (timers[i] == null) { timers[i] = Timer(n + delay, fn); return }
        }
    }

    private fun runTimers() {
        for (i in timers.indices) {
            val tm = timers[i] ?: continue
            if (n >= tm.at) { timers[i] = null; tm.fn() }
        }
    }

    private enum class Prop { YAW, PITCH, ROLL, TILT, OPEN, SX, SY, OY, OX, BLUSH, ES, BADGE_S }

    /** One keyframe: target, duration in ms, easing (in-out by default, as on desktop). */
    private class K(val target: Float, ms: Int, val ease: Ease = Ease.IN_OUT) {
        val sec = ms / 1000f
    }

    private class Tween(val keys: Array<out K>, var from: Float, var start: Float, val onComplete: (() -> Unit)?) {
        var index = 0
    }

    private class Timer(val at: Float, val fn: () -> Unit)

    private companion object {
        const val MAIL_COLOR = 0xFFFFB547.toInt()

        fun mouthForEye(e: Eye): Mouth? = when (e) {
            Eye.HAPPY, Eye.STAR -> Mouth.GRIN
            Eye.HEART, Eye.WINK -> Mouth.SMILE
            Eye.ANNOYED, Eye.LINE, Eye.TIRED -> Mouth.FLAT
            Eye.X -> Mouth.FROWN
            Eye.SPIRAL -> Mouth.WAVY
            Eye.DOT -> Mouth.O
            else -> null
        }

        /** A believable talking rhythm for when no live voice level is available. */
        fun syntheticVoice(t: Float): Float {
            val syl = max(0f, sin(t * 11f)) * (0.55f + 0.45f * sin(t * 2.3f))
            val gap = if (sin(t * 1.1f) > -0.6f) 1f else 0f
            return min(1f, syl * gap * 1.15f)
        }

        fun rgb(c: Int, out: FloatArray) {
            out[0] = ((c shr 16) and 255) / 255f
            out[1] = ((c shr 8) and 255) / 255f
            out[2] = (c and 255) / 255f
        }

        fun argb(c: FloatArray): Int =
            (0xFF shl 24) or ((c[0] * 255f).toInt() shl 16) or ((c[1] * 255f).toInt() shl 8) or (c[2] * 255f).toInt()
    }
}
