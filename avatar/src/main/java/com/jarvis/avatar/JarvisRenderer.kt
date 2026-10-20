package com.jarvis.avatar

import android.graphics.Canvas
import android.graphics.Color
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RadialGradient
import android.graphics.RectF
import android.graphics.Shader
import android.graphics.Typeface
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sin

// Jarvis — the character renderer, ported from Jarvis2.0 desktop/src/jarvis/draw.ts.
// Visual source of truth: Jarvis2.0 design/character/jarvis-character.html.
//
// Coordinates: the origin is the body centre, R is the body "radius". The dome
// spans y ∈ [-0.92R, 0.8R], the antenna bulb reaches -1.44R, the ears ±1.22R.
// Squash pivots on the bottom of the dome so the bot lands on its base.
//
// Nothing is allocated per frame: paints, paths, rects and shaders are reused,
// shaders are rebuilt only when R changes.

private const val TAU = (PI * 2).toFloat()
private const val RAD2DEG = (180.0 / PI).toFloat()

/** Gulp mouth geometry. */
const val MOUTH_Y = 0.19f

class JarvisRenderer {
    private val fill = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.FILL }
    private val stroke = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeCap = Paint.Cap.ROUND
        strokeJoin = Paint.Join.ROUND
    }
    private val shaded = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.FILL }
    private val text = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        textAlign = Paint.Align.CENTER
        typeface = Typeface.create(Typeface.DEFAULT, Typeface.BOLD)
    }
    private val path = Path()
    private val path2 = Path()
    private val rect = RectF()

    /** Current face glow radius, so shapes that change colour can put it back. */
    private var faceGlow = 0f

    private var shaderR = -1f
    private var bodyShader: Shader? = null
    private var highlightShader: Shader? = null
    private var visorShader: Shader? = null
    private var scanShader: Shader? = null

    /** Set for low-bit ambient screens: no anti-aliasing. */
    var antiAlias = true
        set(v) {
            field = v
            fill.isAntiAlias = v; stroke.isAntiAlias = v; shaded.isAntiAlias = v; text.isAntiAlias = v
        }

    /** Draws Jarvis centred on the canvas's current origin. `t` is a clock in seconds. */
    fun draw(c: Canvas, p: JarvisPose, t: Float, style: RenderStyle = RenderStyle.FULL) {
        val r = p.r
        if (r <= 0.5f) return
        if (style == RenderStyle.AMBIENT) {
            drawAmbient(c, p, t)
            return
        }
        val lite = style == RenderStyle.LITE
        ensureShaders(r)
        val w = r * 1.05f

        c.save()
        c.rotate(p.tilt * RAD2DEG)
        c.translate(0f, r * 0.8f)
        c.scale(p.sx, p.sy)
        c.translate(0f, -r * 0.8f)

        drawEars(c, p, t, r, w, lite)
        drawAntenna(c, p, r, lite)

        bodyPath(path, r, w)
        shaded.shader = bodyShader
        c.drawPath(path, shaded)
        shaded.shader = null
        if (!lite) {
            c.save()
            c.clipPath(path)
            fill.color = Color.argb(46, 40, 40, 120)
            oval(r * 0.2f, r * 0.95f, r * 1.2f, r * 0.45f)
            c.drawOval(rect, fill)
            shaded.shader = highlightShader
            c.drawCircle(-r * 0.45f, -r * 0.55f, r * 0.55f, shaded)
            shaded.shader = null
            c.restore()
        }

        fill.color = Color.argb(a255(0.22f + 0.6f * clamp(p.blush, 0f, 1f)), 255, 140, 180)
        for (s in SIDES) {
            oval(s * r * 0.56f, r * 0.57f, r * 0.14f, r * 0.07f)
            c.drawOval(rect, fill)
        }

        // Visor — the face is light on a dark screen.
        val vx = -r * 0.74f
        val vy = -r * 0.42f
        val vw = r * 1.48f
        val vh = r * 0.84f
        val vr = r * 0.34f
        rect.set(vx, vy, vx + vw, vy + vh)
        shaded.shader = visorShader
        c.drawRoundRect(rect, vr, vr, shaded)
        shaded.shader = null
        stroke.strokeWidth = r * 0.035f
        stroke.color = Color.argb(153, 60, 70, 150)
        c.drawRoundRect(rect, vr, vr, stroke)

        c.save()
        path2.reset()
        path2.addRoundRect(rect, vr, vr, Path.Direction.CW)
        c.clipPath(path2)
        if (p.scan) {
            val sxp = sin(t * 2.4f) * r * 0.6f
            c.save()
            c.translate(sxp, 0f)
            shaded.shader = scanShader
            c.drawRect(vx - sxp, vy, vx + vw - sxp, vy + vh, shaded)
            shaded.shader = null
            c.restore()
        }
        drawFace(c, p, t, r, lite)
        fill.color = Color.argb(15, 255, 255, 255)
        path.reset()
        path.moveTo(vx, vy)
        path.lineTo(vx + vw * 0.55f, vy)
        path.lineTo(vx + vw * 0.25f, vy + vh)
        path.lineTo(vx, vy + vh)
        path.close()
        c.drawPath(path, fill)
        c.restore()

        if (p.dizzy && !lite) {
            fill.color = Palette.GOLD
            for (i in 0 until 3) {
                val a = t * 4f + i * TAU / 3f
                starPath(path, cos(a) * r * 0.85f, -r * 1.08f + sin(a) * r * 0.18f, r * 0.11f, r * 0.05f)
                c.drawPath(path, fill)
            }
        }
        if (p.badge != BadgeKind.NONE) drawBadge(c, p, t, r)
        c.restore()
    }

    // ── Parts ───────────────────────────────────────────────────────────────

    private fun drawEars(c: Canvas, p: JarvisPose, t: Float, r: Float, w: Float, lite: Boolean) {
        val outer = shade(Palette.BODY_MID, 0.78f)
        val inner = shade(Palette.BODY_MID, 0.62f)
        for (s in SIDES) {
            val ex = s * (w - r * 0.02f)
            val ey = r * 0.12f
            if (p.listen && !lite) {
                stroke.strokeWidth = r * 0.045f
                for (i in 0 until 2) {
                    val k = (t * 1.4f + i * 0.5f) % 1f
                    stroke.color = withAlpha(Palette.MINT, 0.6f * (1f - k))
                    val rad = r * (0.26f + k * 0.5f)
                    rect.set(ex - rad, ey - rad, ex + rad, ey + rad)
                    val start = if (s > 0) -0.6f else PI.toFloat() - 0.6f
                    c.drawArc(rect, start * RAD2DEG, 1.2f * RAD2DEG, false, stroke)
                }
            }
            fill.color = outer
            oval(ex, ey, r * 0.15f, r * 0.2f)
            c.drawOval(rect, fill)
            fill.color = if (p.listen) withAlpha(Palette.MINT, 0.65f + 0.35f * sin(t * 8f)) else inner
            oval(ex + s * r * 0.04f, ey, r * 0.06f, r * 0.1f)
            c.drawOval(rect, fill)
        }
    }

    private fun drawAntenna(c: Canvas, p: JarvisPose, r: Float, lite: Boolean) {
        val len = r * 0.4f
        val a = p.antenna
        val by = -r * 0.86f
        val tx = sin(a) * len
        val ty = by - cos(a) * len
        stroke.color = shade(Palette.BODY_MID, 0.78f)
        stroke.strokeWidth = r * 0.075f
        path.reset()
        path.moveTo(0f, by + r * 0.1f)
        path.quadTo(sin(a) * len * 0.2f, by - len * 0.5f, tx, ty)
        c.drawPath(path, stroke)

        fill.color = p.bulb
        if (!lite) fill.setShadowLayer(blur(r * (0.2f + 0.4f * clamp(p.pulse, 0f, 1f))), 0f, 0f, p.bulb)
        c.drawCircle(tx, ty, r * 0.12f, fill)
        fill.clearShadowLayer()
        fill.color = Color.argb(178, 255, 255, 255)
        c.drawCircle(tx - r * 0.04f, ty - r * 0.04f, r * 0.035f, fill)
    }

    private fun drawFace(c: Canvas, p: JarvisPose, t: Float, r: Float, lite: Boolean) {
        val ox = p.lookX * r * 0.13f
        val oy = p.lookY * r * 0.09f
        val ew = r * 0.12f * p.es
        val eh = r * 0.17f * p.es
        val glow = if (lite) 0f else blur(r * 0.22f)
        faceGlow = glow
        val faceColor = withAlpha(Palette.MINT, p.dim)
        fill.color = faceColor
        stroke.color = faceColor
        if (glow > 0f) {
            fill.setShadowLayer(glow, 0f, 0f, faceColor)
            stroke.setShadowLayer(glow, 0f, 0f, faceColor)
        }
        for (side in SIDES) {
            val sz = if (p.eyes == Eye.QUESTION && side > 0) 1.12f else 1f
            val cx = side * r * 0.32f + ox
            val cy = -r * 0.05f + oy
            c.save()
            c.scale(1f, max(0.06f, p.eyeSwap), cx, cy)
            drawEye(c, eyeFor(p.eyes, side), cx, cy, ew * sz, eh * sz, p, t, side, lite, faceColor)
            c.restore()
        }
        val mx = ox * 0.6f
        val my = r * (MOUTH_Y + 0.04f) + oy * 0.5f
        c.save()
        c.scale(max(0.3f, p.mouthSwap), max(0.06f, p.mouthSwap), mx, my)
        drawMouth(c, p, t, r, mx, my, faceColor)
        c.restore()
        fill.clearShadowLayer()
        stroke.clearShadowLayer()
    }

    private fun eyeFor(e: Eye, side: Float): Eye = when (e) {
        Eye.WINK -> if (side < 0) Eye.OPEN else Eye.HAPPY
        Eye.QUESTION -> if (side < 0) Eye.OPEN else Eye.WIDE
        else -> e
    }

    private fun drawEye(
        c: Canvas, shape: Eye, cx: Float, cy: Float, ew: Float, eh: Float,
        p: JarvisPose, t: Float, side: Float, lite: Boolean, faceColor: Int,
    ) {
        stroke.strokeWidth = ew * 0.75f
        when (shape) {
            Eye.OPEN, Eye.FOCUS -> {
                val h = eh * (if (shape == Eye.FOCUS) 0.68f else 1f) * max(0.08f, p.open)
                pill(c, cx, cy, ew, h)
                if (!lite && p.open > 0.5f) sparkle(c, cx + ew * 0.35f, cy - h * 0.45f, ew * 0.3f, p.dim)
            }
            Eye.WIDE, Eye.DOT -> {
                val k = if (shape == Eye.DOT) 0.62f else 1f
                val ry = eh * k * max(0.1f, p.open)
                oval(cx, cy, eh * 0.92f * k, ry)
                c.drawOval(rect, fill)
                if (!lite) sparkle(c, cx + eh * 0.35f * k, cy - ry * 0.4f, eh * 0.28f * k, p.dim)
            }
            Eye.HAPPY -> arc(c, cx, cy + eh * 0.45f, ew * 1.15f, 1.12f, 1.88f)
            Eye.CLOSED -> arc(c, cx, cy - eh * 0.35f, ew * 1.15f, 0.12f, 0.88f)
            Eye.LINE -> c.drawLine(cx - ew, cy, cx + ew, cy, stroke)
            Eye.ANNOYED -> {
                c.drawLine(cx - ew, cy + eh * 0.15f, cx + ew, cy + eh * 0.15f, stroke)
                stroke.strokeWidth = ew * 0.5f
                c.drawLine(cx + side * ew * 1.1f, cy - eh * 0.95f, cx - side * ew * 0.9f, cy - eh * 0.45f, stroke)
            }
            Eye.TIRED -> {
                pill(c, cx, cy + eh * 0.38f, ew, eh * 0.45f)
                stroke.strokeWidth = ew * 0.45f
                c.drawLine(cx - ew * 1.2f, cy - eh * 0.12f, cx + ew * 1.2f, cy - eh * 0.12f, stroke)
            }
            Eye.X -> {
                stroke.strokeWidth = ew * 0.6f
                c.drawLine(cx - ew, cy - ew, cx + ew, cy + ew, stroke)
                c.drawLine(cx + ew, cy - ew, cx - ew, cy + ew, stroke)
            }
            Eye.SPIRAL -> {
                stroke.strokeWidth = ew * 0.4f
                path.reset()
                val turns = TAU * 2.2f
                var a = 0f
                while (a <= turns) {
                    val rr = (a / turns) * eh * 1.05f
                    val px = cx + cos(a + t * 8f * side) * rr
                    val py = cy + sin(a + t * 8f * side) * rr
                    if (a == 0f) path.moveTo(px, py) else path.lineTo(px, py)
                    a += 0.2f
                }
                c.drawPath(path, stroke)
            }
            Eye.HEART -> {
                fill.color = withAlpha(Palette.PINK, p.dim)
                if (!lite) fill.setShadowLayer(blur(eh), 0f, 0f, Palette.PINK)
                heartPath(path, cx, cy, eh * (1f + 0.12f * sin(t * 10f)))
                c.drawPath(path, fill)
                restoreFace(faceColor)
            }
            Eye.STAR -> {
                fill.color = withAlpha(Palette.GOLD, p.dim)
                if (!lite) fill.setShadowLayer(blur(eh), 0f, 0f, Palette.GOLD)
                c.save()
                c.translate(cx, cy)
                c.rotate(t * 1.5f * side * RAD2DEG)
                starPath(path, 0f, 0f, eh * 1.05f, eh * 0.46f)
                c.drawPath(path, fill)
                c.restore()
                restoreFace(faceColor)
            }
            Eye.WINK, Eye.QUESTION -> Unit // resolved by eyeFor
        }
    }

    private fun restoreFace(faceColor: Int) {
        fill.color = faceColor
        if (faceGlow > 0f) fill.setShadowLayer(faceGlow, 0f, 0f, faceColor) else fill.clearShadowLayer()
    }

    private fun drawMouth(c: Canvas, p: JarvisPose, t: Float, r: Float, mx: Float, my: Float, faceColor: Int) {
        val o = p.mouthOpen
        stroke.strokeWidth = r * 0.045f
        when (p.mouth) {
            Mouth.NONE -> Unit
            Mouth.SMILE -> arc(c, mx, my - r * 0.07f, r * 0.09f, 0.2f, 0.8f)
            Mouth.SMALL -> arc(c, mx, my - r * 0.04f, r * 0.05f, 0.2f, 0.8f)
            Mouth.GRIN -> {
                path.reset()
                path.moveTo(mx - r * 0.12f, my - r * 0.04f)
                path.lineTo(mx + r * 0.12f, my - r * 0.04f)
                path.quadTo(mx + r * 0.1f, my + r * 0.12f, mx, my + r * 0.12f)
                path.quadTo(mx - r * 0.1f, my + r * 0.12f, mx - r * 0.12f, my - r * 0.04f)
                path.close()
                c.drawPath(path, fill)
            }
            Mouth.FLAT -> c.drawLine(mx - r * 0.07f, my, mx + r * 0.07f, my, stroke)
            Mouth.FROWN -> arc(c, mx, my + r * 0.08f, r * 0.08f, 1.2f, 1.8f)
            Mouth.WAVY -> {
                stroke.strokeWidth = r * 0.035f
                path.reset()
                for (i in 0..12) {
                    val px = mx - r * 0.13f + (i / 12f) * r * 0.26f
                    val py = my + sin(i * 1.3f + t * 10f) * r * 0.025f
                    if (i == 0) path.moveTo(px, py) else path.lineTo(px, py)
                }
                c.drawPath(path, stroke)
            }
            Mouth.TALK, Mouth.O, Mouth.CHEW -> {
                val rx = r * (if (p.mouth == Mouth.O) 0.06f else 0.08f)
                val ry = max(r * 0.014f, r * 0.014f + r * 0.09f * o)
                oval(mx, my, rx, ry)
                c.drawOval(rect, fill)
            }
            Mouth.GULP -> {
                val gr = r * 0.05f + r * 0.15f * clamp(o, 0f, 1.1f)
                val cy = my - r * 0.04f
                fill.clearShadowLayer()
                fill.color = 0xFF04050D.toInt()
                oval(mx, cy, gr * 1.08f, gr)
                c.drawOval(rect, fill)
                c.save()
                path2.reset()
                path2.addOval(rect, Path.Direction.CW)
                c.clipPath(path2)
                fill.color = Palette.PINK
                oval(mx, cy + gr * 0.7f, gr * 0.6f, gr * 0.38f)
                c.drawOval(rect, fill)
                c.restore()
                fill.color = faceColor
                stroke.strokeWidth = r * 0.035f
                oval(mx, cy, gr * 1.08f, gr)
                c.drawOval(rect, stroke)
            }
        }
    }

    private fun drawBadge(c: Canvas, p: JarvisPose, t: Float, r: Float) {
        if (p.badgeScale <= 0.01f) return
        val bx = r * 0.98f
        val by = -r * 0.98f + sin(t * 5f) * r * 0.04f
        val br = r * 0.27f
        c.save()
        c.translate(bx, by)
        c.scale(p.badgeScale, p.badgeScale)

        if (p.badge == BadgeKind.DOT) {
            fill.color = Palette.VISOR_BOTTOM
            c.drawCircle(0f, 0f, br * 0.62f, fill)
            fill.color = p.badgeColor
            c.drawCircle(0f, 0f, br * 0.42f, fill)
            c.restore()
            return
        }

        fill.color = if (p.badge == BadgeKind.BANG) p.badgeColor else Palette.BADGE_LIGHT
        c.drawCircle(0f, 0f, br, fill)
        path.reset()
        path.moveTo(-br * 0.75f, br * 0.45f)
        path.lineTo(-br * 1.2f, br * 1.05f)
        path.lineTo(-br * 0.2f, br * 0.9f)
        path.close()
        c.drawPath(path, fill)

        if (p.badge == BadgeKind.DOTS) {
            fill.color = shade(p.badgeColor, 0.8f)
            for (i in 0 until 3) {
                c.drawCircle((i - 1) * br * 0.42f, sin(t * 7f - i * 0.8f) * br * 0.18f, br * 0.13f, fill)
            }
        } else {
            text.color = if (p.badge == BadgeKind.BANG) Palette.BADGE_INK else shade(p.badgeColor, 0.75f)
            text.textSize = br * 1.35f
            val baseline = br * 0.1f - (text.ascent() + text.descent()) / 2f
            c.drawText(if (p.badge == BadgeKind.BANG) "!" else "?", 0f, baseline, text)
        }
        c.restore()
    }

    // ── Ambient: outline only, dim, nothing large lit (burn-in safe) ────────

    private fun drawAmbient(c: Canvas, p: JarvisPose, t: Float) {
        val r = p.r
        val w = r * 1.05f
        val line = 0xFF4A5290.toInt()
        c.save()
        c.rotate(p.tilt * RAD2DEG)
        stroke.strokeWidth = r * 0.03f
        stroke.color = line
        for (s in SIDES) {
            oval(s * (w - r * 0.02f), r * 0.12f, r * 0.15f, r * 0.2f)
            c.drawOval(rect, stroke)
        }
        // Antenna
        val len = r * 0.4f
        val by = -r * 0.86f
        val tx = sin(p.antenna) * len
        val ty = by - cos(p.antenna) * len
        path.reset()
        path.moveTo(0f, by + r * 0.1f)
        path.quadTo(sin(p.antenna) * len * 0.2f, by - len * 0.5f, tx, ty)
        c.drawPath(path, stroke)
        stroke.color = withAlpha(p.bulb, 0.7f)
        c.drawCircle(tx, ty, r * 0.1f, stroke)
        // Body (black fill hides the ear outlines behind it) and visor outline
        bodyPath(path, r, w)
        fill.color = Color.BLACK
        c.drawPath(path, fill)
        stroke.color = line
        c.drawPath(path, stroke)
        rect.set(-r * 0.74f, -r * 0.42f, r * 0.74f, r * 0.42f)
        c.drawRoundRect(rect, r * 0.34f, r * 0.34f, stroke)
        // Face: dim mint strokes / small fills only
        faceGlow = 0f
        val face = withAlpha(Palette.MINT, 0.55f * p.dim)
        fill.color = face
        stroke.color = face
        val ox = p.lookX * r * 0.13f
        val oy = p.lookY * r * 0.09f
        val ew = r * 0.12f * p.es
        val eh = r * 0.17f * p.es
        for (side in SIDES) {
            drawEye(c, eyeFor(p.eyes, side), side * r * 0.32f + ox, -r * 0.05f + oy, ew, eh, p, t, side, true, face)
        }
        fill.color = face
        drawMouth(c, p, t, r, ox * 0.6f, r * (MOUTH_Y + 0.04f) + oy * 0.5f, face)
        c.restore()
    }

    // ── Particles ───────────────────────────────────────────────────────────

    /** Draws the engine's live particles around a bot whose face centre is (cx, cy). */
    fun drawParticles(c: Canvas, particles: Array<Particle>, r: Float, cx: Float, cy: Float) {
        for (p in particles) {
            if (!p.alive || p.age <= 0f) continue
            val k = p.age / p.life
            val a = if (k < 0.2f) k / 0.2f else 1f - (k - 0.2f) / 0.8f
            val px = cx + (p.x + p.vx * p.age) * r * 1.3f
            val py = cy + (p.y + p.vy * p.age) * r * 1.3f
            val sz = r * p.size * (1f + k * 0.4f)
            val alpha = clamp(a, 0f, 1f)
            c.save()
            c.translate(px, py)
            when (p.type) {
                ParticleType.HEART -> {
                    c.rotate(sin(p.age * 6f) * 0.3f * RAD2DEG)
                    fill.color = withAlpha(Palette.PINK, alpha)
                    heartPath(path, 0f, 0f, sz)
                    c.drawPath(path, fill)
                }
                ParticleType.STAR -> {
                    c.rotate((p.rot + p.age * 2f) * RAD2DEG)
                    fill.color = withAlpha(Palette.GOLD, alpha)
                    starPath(path, 0f, 0f, sz, sz * 0.45f)
                    c.drawPath(path, fill)
                }
                ParticleType.SPARK -> {
                    c.rotate(p.rot * RAD2DEG)
                    fill.color = withAlpha(Palette.SPARK, alpha)
                    starPath(path, 0f, 0f, sz * 0.8f, sz * 0.18f)
                    c.drawPath(path, fill)
                }
                ParticleType.SWEAT -> {
                    fill.color = withAlpha(Palette.SWEAT, alpha)
                    path.reset()
                    path.moveTo(0f, -sz)
                    path.quadTo(sz * 0.8f, sz * 0.2f, 0f, sz * 0.6f)
                    path.quadTo(-sz * 0.8f, sz * 0.2f, 0f, -sz)
                    c.drawPath(path, fill)
                }
                ParticleType.Z -> {
                    text.color = withAlpha(Palette.ZZZ, alpha)
                    text.textSize = sz * 1.9f
                    c.drawText("z", 0f, -(text.ascent() + text.descent()) / 2f, text)
                }
            }
            c.restore()
        }
    }

    // ── Helpers ─────────────────────────────────────────────────────────────

    private fun ensureShaders(r: Float) {
        if (r == shaderR) return
        shaderR = r
        bodyShader = LinearGradient(
            0f, -r, 0f, r * 0.8f,
            intArrayOf(Palette.BODY_TOP, Palette.BODY_MID, Palette.BODY_BOTTOM), floatArrayOf(0f, 0.5f, 1f),
            Shader.TileMode.CLAMP,
        )
        highlightShader = RadialGradient(
            -r * 0.45f, -r * 0.55f, r * 0.55f,
            Color.argb(191, 255, 255, 255), Color.argb(0, 255, 255, 255), Shader.TileMode.CLAMP,
        )
        visorShader = LinearGradient(
            0f, -r * 0.42f, 0f, r * 0.42f, Palette.VISOR_TOP, Palette.VISOR_BOTTOM, Shader.TileMode.CLAMP,
        )
        val teal0 = Color.argb(0, 45, 212, 191)
        scanShader = LinearGradient(
            -r * 0.16f, 0f, r * 0.16f, 0f,
            intArrayOf(teal0, Color.argb(61, 45, 212, 191), teal0), floatArrayOf(0f, 0.5f, 1f),
            Shader.TileMode.CLAMP,
        )
    }

    private fun bodyPath(p: Path, r: Float, w: Float) {
        p.reset()
        p.moveTo(-w, r * 0.3f)
        p.cubicTo(-w, -r * 0.5f, -r * 0.62f, -r * 0.92f, 0f, -r * 0.92f)
        p.cubicTo(r * 0.62f, -r * 0.92f, w, -r * 0.5f, w, r * 0.3f)
        p.cubicTo(w, r * 0.68f, r * 0.72f, r * 0.8f, 0f, r * 0.8f)
        p.cubicTo(-r * 0.72f, r * 0.8f, -w, r * 0.68f, -w, r * 0.3f)
        p.close()
    }

    private fun heartPath(p: Path, cx: Float, cy: Float, s: Float) {
        p.reset()
        p.moveTo(cx, cy + s * 0.75f)
        p.cubicTo(cx - s * 1.25f, cy - s * 0.05f, cx - s * 0.6f, cy - s * 1.05f, cx, cy - s * 0.35f)
        p.cubicTo(cx + s * 0.6f, cy - s * 1.05f, cx + s * 1.25f, cy - s * 0.05f, cx, cy + s * 0.75f)
        p.close()
    }

    private fun starPath(p: Path, cx: Float, cy: Float, ro: Float, ri: Float) {
        p.reset()
        for (i in 0 until 10) {
            val a = -PI.toFloat() / 2f + i * PI.toFloat() / 5f
            val rr = if (i % 2 == 1) ri else ro
            val px = cx + cos(a) * rr
            val py = cy + sin(a) * rr
            if (i == 0) p.moveTo(px, py) else p.lineTo(px, py)
        }
        p.close()
    }

    private fun pill(c: Canvas, cx: Float, cy: Float, ew: Float, h: Float) {
        val rad = min(ew, h)
        rect.set(cx - ew, cy - h, cx + ew, cy + h)
        c.drawRoundRect(rect, rad, rad, fill)
    }

    /** Stroked arc; angles in multiples of π, clockwise, as in the canvas original. */
    private fun arc(c: Canvas, cx: Float, cy: Float, rad: Float, fromPi: Float, toPi: Float) {
        rect.set(cx - rad, cy - rad, cx + rad, cy + rad)
        c.drawArc(rect, fromPi * 180f, (toPi - fromPi) * 180f, false, stroke)
    }

    private fun sparkle(c: Canvas, cx: Float, cy: Float, rad: Float, dim: Float) {
        val keep = fill.color
        fill.clearShadowLayer()
        fill.color = withAlpha(Color.WHITE, 0.85f * dim)
        c.drawCircle(cx, cy, rad, fill)
        restoreFace(keep)
    }

    private fun oval(cx: Float, cy: Float, rx: Float, ry: Float) = rect.set(cx - rx, cy - ry, cx + rx, cy + ry)

    private companion object {
        val SIDES = floatArrayOf(-1f, 1f)

        /** Canvas2D shadowBlur → Android shadow radius (σ = blur/2, radius ≈ σ / 0.577). */
        fun blur(canvasBlur: Float) = max(0.5f, canvasBlur * 0.85f)

        fun a255(a: Float) = (clamp(a, 0f, 1f) * 255f + 0.5f).toInt()

        fun withAlpha(color: Int, a: Float) = (color and 0x00FFFFFF) or (a255(a * (Color.alpha(color) / 255f)) shl 24)

        fun shade(color: Int, k: Float) = Color.argb(
            Color.alpha(color),
            (Color.red(color) * k).toInt(), (Color.green(color) * k).toInt(), (Color.blue(color) * k).toInt(),
        )
    }
}
