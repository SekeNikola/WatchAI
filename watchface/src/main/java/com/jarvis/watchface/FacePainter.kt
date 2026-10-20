package com.jarvis.watchface

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.RadialGradient
import android.graphics.RectF
import android.graphics.Shader
import android.graphics.Typeface
import android.text.format.DateFormat
import com.jarvis.avatar.BadgeKind
import com.jarvis.avatar.BotState
import com.jarvis.avatar.Eye
import com.jarvis.avatar.JarvisEngine
import com.jarvis.avatar.JarvisPose
import com.jarvis.avatar.JarvisRenderer
import com.jarvis.avatar.Mouth
import com.jarvis.avatar.RenderStyle
import java.time.ZonedDateTime
import java.time.format.TextStyle
import java.util.Locale

/**
 * The face layout: time on top, Jarvis in the middle, date + battery below.
 * Independent of the watch face framework, so it can also be rendered off-device.
 * Strings are rebuilt only when the minute or battery changes; nothing else allocates.
 */
class FacePainter(private val context: Context) {

    private val avatar = JarvisRenderer()
    private val pose = JarvisPose()

    private val timePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { textAlign = Paint.Align.CENTER }
    private val infoPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        textAlign = Paint.Align.LEFT
        typeface = Typeface.create("sans-serif-medium", Typeface.NORMAL)
        letterSpacing = 0.06f
    }
    private val iconPaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val bgPaint = Paint()
    private val iconRect = RectF()
    private val timeTypeface = Typeface.create("sans-serif", Typeface.NORMAL)
    private val ambientTimeTypeface = Typeface.create("sans-serif-thin", Typeface.NORMAL)

    private var timeText = ""
    private var dateText = ""
    private var battText = ""
    private var cachedMinute = -1
    private var cachedBattery = -1
    private var cachedWidth = -1f

    /** Who is drawn. */
    var character: com.jarvis.avatar.Character
        get() = avatar.character
        set(v) { avatar.character = v }

    /** Avatar hit circle from the last interactive frame, in surface pixels. */
    var hitX = 0f; private set
    var hitY = 0f; private set
    var hitR = 0f; private set

    fun drawInteractive(
        c: Canvas, w: Float, h: Float, z: ZonedDateTime,
        engine: JarvisEngine, t: Float, battery: Int, charging: Boolean, lite: Boolean = false,
    ) {
        prepare(w, z, battery)
        setAntiAlias(true)
        val r = w * AVATAR_R
        engine.pose(r, pose)

        c.drawColor(Color.BLACK)
        c.drawCircle(w / 2f, h * AVATAR_Y, w * 0.42f, bgPaint)
        val cx = w / 2f + engine.offsetX * r
        val cy = h * AVATAR_Y + engine.offsetY * r
        c.save()
        c.translate(cx, cy)
        avatar.draw(c, pose, t, if (lite) RenderStyle.LITE else RenderStyle.FULL)
        c.restore()
        avatar.drawParticles(c, engine.particles, r, cx, cy - r * 0.3f)
        // Time last: hearts and z's float up behind it, never over it.
        drawTime(c, w, h, false)
        drawInfo(c, w, h, false, battery, charging)

        hitX = w / 2f
        hitY = h * AVATAR_Y - r * 0.2f
        hitR = r * 1.45f
    }

    fun drawAmbient(
        c: Canvas, w: Float, h: Float, z: ZonedDateTime,
        engine: JarvisEngine, battery: Int, charging: Boolean, lowBit: Boolean,
        alert: Boolean = false, music: Boolean = false,
    ) {
        prepare(w, z, battery)
        setAntiAlias(!lowBit)
        c.drawColor(Color.BLACK)
        // Burn-in protection: drift a few pixels, one step per minute.
        val m = z.minute
        c.save()
        c.translate(((m % 5) - 2) * 2f, (((m / 5) % 3) - 1) * 2f)
        drawTime(c, w, h, true)
        c.save()
        c.translate(w / 2f, h * AVATAR_Y)
        val p = ambientPose(engine, w * AVATAR_R)
        if (alert) {
            // A notification is waiting: eyes wide, a letter in the bubble, until the next wrist raise.
            p.eyes = Eye.WIDE; p.mouth = Mouth.O; p.mouthOpen = 0.5f; p.open = 1f
            p.badge = BadgeKind.MAIL; p.badgeColor = 0xFFFFB547.toInt(); p.badgeScale = 1f
            p.antenna = 0f
        }
        avatar.draw(c, p, 0f, RenderStyle.AMBIENT)
        if (music) avatar.drawAmbientNote(c, w * AVATAR_R)
        c.restore()
        drawInfo(c, w, h, true, battery, charging)
        c.restore()
        hitR = 0f
    }

    private fun setAntiAlias(on: Boolean) {
        avatar.antiAlias = on
        timePaint.isAntiAlias = on
        infoPaint.isAntiAlias = on
        iconPaint.isAntiAlias = on
    }

    private fun drawTime(c: Canvas, w: Float, h: Float, ambient: Boolean) {
        timePaint.typeface = if (ambient) ambientTimeTypeface else timeTypeface
        timePaint.color = if (ambient) 0xFFB8BED6.toInt() else 0xFFEEF1FF.toInt()
        timePaint.textSize = w * 0.19f
        c.drawText(timeText, w / 2f, h * TIME_BASELINE, timePaint)
    }

    private fun drawInfo(c: Canvas, w: Float, h: Float, ambient: Boolean, level: Int, charging: Boolean) {
        infoPaint.textSize = w * 0.052f
        infoPaint.color = if (ambient) 0xFF6A7090.toInt() else 0xFF8A93C0.toInt()
        val gap = w * 0.05f
        val iw = w * 0.05f
        val ih = w * 0.026f
        val dateW = infoPaint.measureText(dateText)
        val battW = infoPaint.measureText(battText)
        val total = dateW + gap + iw + w * 0.027f + battW
        var x = (w - total) / 2f
        val y = h * INFO_BASELINE
        c.drawText(dateText, x, y, infoPaint)
        x += dateW + gap

        // Battery: outline, nub and level fill.
        val accent = when {
            ambient -> 0xFF6A7090.toInt()
            charging -> 0xFF6EE7A0.toInt()
            level <= MoodDirector.LOW_BATTERY -> 0xFFFF9F5A.toInt()
            else -> 0xFF8CF5D2.toInt()
        }
        val top = y - ih - w * 0.004f
        iconPaint.color = accent
        iconPaint.style = Paint.Style.STROKE
        iconPaint.strokeWidth = w * 0.004f
        iconRect.set(x, top, x + iw, top + ih)
        c.drawRoundRect(iconRect, ih * 0.25f, ih * 0.25f, iconPaint)
        iconPaint.style = Paint.Style.FILL
        iconRect.set(x + iw, top + ih * 0.3f, x + iw + w * 0.008f, top + ih * 0.7f)
        c.drawRect(iconRect, iconPaint)
        if (!ambient) {
            val inset = w * 0.006f
            iconRect.set(x + inset, top + inset, x + inset + (iw - inset * 2) * level / 100f, top + ih - inset)
            c.drawRect(iconRect, iconPaint)
        }
        c.drawText(battText, x + iw + w * 0.027f, y, infoPaint)
    }

    /** A dim, still pose for ambient: the mood reads, nothing moves. */
    private fun ambientPose(engine: JarvisEngine, r: Float): JarvisPose {
        engine.pose(r, pose)
        pose.tilt = 0f; pose.sx = 1f; pose.sy = 1f
        pose.lookX = 0f; pose.lookY = 0f
        pose.eyeSwap = 1f; pose.mouthSwap = 1f
        pose.es = 1f
        pose.badge = BadgeKind.NONE
        pose.listen = false; pose.scan = false; pose.dizzy = false
        pose.dim = 0.85f
        pose.antenna = engine.state.antennaRest.let { if (it.isNaN()) 0f else it }
        pose.mouthOpen = 0f
        when (engine.state) {
            BotState.SLEEPING -> { pose.eyes = Eye.CLOSED; pose.mouth = Mouth.NONE }
            BotState.CHARGING, BotState.FULL -> { pose.eyes = Eye.HAPPY; pose.mouth = Mouth.SMILE }
            BotState.WORRIED -> { pose.eyes = Eye.WIDE; pose.mouth = Mouth.SMALL; pose.open = 0.8f }
            BotState.BORED -> { pose.eyes = Eye.FOCUS; pose.mouth = Mouth.FLAT; pose.open = 0.7f }
            else -> { pose.eyes = Eye.OPEN; pose.mouth = Mouth.SMILE; pose.open = 0.75f }
        }
        return pose
    }

    private fun prepare(w: Float, z: ZonedDateTime, battery: Int) {
        if (w != cachedWidth) {
            cachedWidth = w
            bgPaint.shader = RadialGradient(
                w / 2f, w * AVATAR_Y, w * 0.42f, 0xFF161A3A.toInt(), Color.BLACK, Shader.TileMode.CLAMP,
            )
        }
        val minuteKey = z.dayOfYear * 1440 + z.hour * 60 + z.minute
        if (minuteKey != cachedMinute) {
            cachedMinute = minuteKey
            val h24 = DateFormat.is24HourFormat(context)
            timeText = if (h24) {
                String.format(Locale.US, "%02d:%02d", z.hour, z.minute)
            } else {
                String.format(Locale.US, "%d:%02d", (z.hour % 12).let { if (it == 0) 12 else it }, z.minute)
            }
            dateText = z.dayOfWeek.getDisplayName(TextStyle.SHORT, Locale.getDefault()).uppercase() + " " + z.dayOfMonth
        }
        if (battery != cachedBattery) {
            cachedBattery = battery
            battText = "$battery%"
        }
    }

    companion object {
        // Layout, as fractions of the screen.
        const val AVATAR_R = 0.2f
        const val AVATAR_Y = 0.6f
        const val TIME_BASELINE = 0.27f
        const val INFO_BASELINE = 0.885f
    }
}
