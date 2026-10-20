package com.jarvis.watchface

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.RadialGradient
import android.graphics.Rect
import android.graphics.RectF
import android.graphics.Shader
import android.graphics.Typeface
import android.os.BatteryManager
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.text.format.DateFormat
import android.util.Log
import android.view.SurfaceHolder
import androidx.wear.watchface.CanvasType
import androidx.wear.watchface.DrawMode
import androidx.wear.watchface.Renderer
import androidx.wear.watchface.WatchState
import androidx.wear.watchface.style.CurrentUserStyleRepository
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
 * Draws the face: time on top, Jarvis in the middle, date + battery below.
 * Interactive: full animation at ~60 fps. Ambient: one redraw a minute, black
 * background, outline-only Jarvis in a static pose, small pixel shift against burn-in.
 */
class JarvisCanvasRenderer(
    private val context: Context,
    surfaceHolder: SurfaceHolder,
    private val watchState: WatchState,
    currentUserStyleRepository: CurrentUserStyleRepository,
) : Renderer.CanvasRenderer2<JarvisCanvasRenderer.Assets>(
    surfaceHolder,
    currentUserStyleRepository,
    watchState,
    CanvasType.HARDWARE,
    FRAME_MS,
    false,
) {
    class Assets : SharedAssets {
        override fun onDestroy() = Unit
    }

    private val engine = JarvisEngine()
    private val director = MoodDirector(engine)
    private val avatar = JarvisRenderer()
    private val pose = JarvisPose()
    private val main = Handler(Looper.getMainLooper())

    private val timePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        textAlign = Paint.Align.CENTER
        color = 0xFFEEF1FF.toInt()
    }
    private val infoPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        textAlign = Paint.Align.LEFT
        color = 0xFF8A93C0.toInt()
        typeface = Typeface.create("sans-serif-medium", Typeface.NORMAL)
        letterSpacing = 0.06f
    }
    private val iconPaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val bgPaint = Paint()
    private val iconRect = RectF()

    private val timeTypeface = Typeface.create("sans-serif", Typeface.NORMAL)
    private val ambientTimeTypeface = Typeface.create("sans-serif-thin", Typeface.NORMAL)

    // Cached strings, rebuilt only when the minute or the battery changes.
    private var timeText = ""
    private var dateText = ""
    private var battText = ""
    private var cachedMinute = -1
    private var cachedBattery = -1
    private var cachedCharging = false
    private var cachedWidth = -1

    private var lastMode: DrawMode? = null
    private var clockBase = SystemClock.uptimeMillis()

    // Avatar hit area, in surface pixels.
    private var hitX = 0f
    private var hitY = 0f
    private var hitR = 0f

    private val batteryReceiver = object : BroadcastReceiver() {
        override fun onReceive(c: Context, i: Intent) = onBattery(i)
    }
    private val debugReceiver = object : BroadcastReceiver() {
        override fun onReceive(c: Context, i: Intent) {
            i.getStringExtra("cmd")?.let(AvatarBus::post)
        }
    }

    init {
        director.start(SystemClock.uptimeMillis())
        context.registerReceiver(batteryReceiver, IntentFilter(Intent.ACTION_BATTERY_CHANGED))?.let(::onBattery)
        if (BuildConfig.DEBUG) {
            val filter = IntentFilter(DEBUG_ACTION)
            if (Build.VERSION.SDK_INT >= 33) {
                context.registerReceiver(debugReceiver, filter, Context.RECEIVER_EXPORTED)
            } else {
                context.registerReceiver(debugReceiver, filter)
            }
        }
        AvatarBus.onPost = { postInvalidate() }
    }

    override suspend fun createSharedAssets() = Assets()

    override fun onDestroy() {
        AvatarBus.onPost = null
        runCatching { context.unregisterReceiver(batteryReceiver) }
        if (BuildConfig.DEBUG) runCatching { context.unregisterReceiver(debugReceiver) }
        super.onDestroy()
    }

    // ── Input ───────────────────────────────────────────────────────────────

    /** Called from the tap listener. Taps on Jarvis open the assistant. */
    fun onTap(x: Int, y: Int) {
        val dx = x - hitX
        val dy = y - hitY
        if (dx * dx + dy * dy > hitR * hitR) return
        director.onTap(SystemClock.uptimeMillis())
        // A short beat first so the listening perk-up is visible before Gemini covers the face.
        main.postDelayed({ AssistantLauncher.launch(context) }, ASSIST_DELAY_MS)
        invalidate()
    }

    private fun onBattery(i: Intent) {
        val level = i.getIntExtra(BatteryManager.EXTRA_LEVEL, -1)
        val scale = i.getIntExtra(BatteryManager.EXTRA_SCALE, 100)
        val status = i.getIntExtra(BatteryManager.EXTRA_STATUS, -1)
        val charging = status == BatteryManager.BATTERY_STATUS_CHARGING || status == BatteryManager.BATTERY_STATUS_FULL
        if (level >= 0) director.setRealBattery(level * 100 / scale, charging)
    }

    // ── Render ──────────────────────────────────────────────────────────────

    override fun render(canvas: Canvas, bounds: Rect, zonedDateTime: ZonedDateTime, sharedAssets: Assets) {
        val nowMs = SystemClock.uptimeMillis()
        val ambient = renderParameters.drawMode == DrawMode.AMBIENT
        val hour = director.hourOfDay(zonedDateTime.hour + zonedDateTime.minute / 60f)

        if (lastMode == DrawMode.AMBIENT && !ambient) {
            if (nowMs - clockBase > CLOCK_RESTART_MS) {
                clockBase = nowMs
                engine.restartClock()
            }
            director.onWake(nowMs, hour, zonedDateTime.dayOfYear)
        }
        lastMode = renderParameters.drawMode

        while (true) {
            val cmd = AvatarBus.poll() ?: break
            if (cmd == "assist") AssistantLauncher.launch(context)
            else if (cmd.startsWith("notify")) director.onNotification(cmd.substringAfter(':', "remind"), nowMs, !ambient)
            else director.command(cmd, nowMs, !ambient)
        }
        director.update(nowMs, hour)

        val w = bounds.width().toFloat()
        val h = bounds.height().toFloat()
        updateText(zonedDateTime, bounds.width())

        canvas.drawColor(Color.BLACK)
        val r = w * AVATAR_R

        if (ambient) {
            val lowBit = watchState.hasLowBitAmbient
            avatar.antiAlias = !lowBit
            timePaint.isAntiAlias = !lowBit
            infoPaint.isAntiAlias = !lowBit
            // Burn-in protection: drift a few pixels, a step each minute.
            val m = zonedDateTime.minute
            val sx = ((m % 5) - 2) * 2f
            val sy = (((m / 5) % 3) - 1) * 2f
            canvas.save()
            canvas.translate(sx, sy)
            drawTime(canvas, w, h, true)
            canvas.save()
            canvas.translate(w / 2f, h * AVATAR_Y)
            avatar.draw(canvas, ambientPose(r), 0f, RenderStyle.AMBIENT)
            canvas.restore()
            drawInfo(canvas, w, h, true)
            canvas.restore()
            hitR = 0f
            return
        }

        avatar.antiAlias = true
        timePaint.isAntiAlias = true
        infoPaint.isAntiAlias = true

        val nowSec = (nowMs - clockBase) / 1000f
        engine.update(nowSec)
        engine.pose(r, pose)

        // Soft navy glow behind him.
        canvas.drawCircle(w / 2f, h * AVATAR_Y, w * 0.42f, bgPaint)

        drawTime(canvas, w, h, false)
        val cx = w / 2f + engine.offsetX * r
        val cy = h * AVATAR_Y + engine.offsetY * r
        canvas.save()
        canvas.translate(cx, cy)
        avatar.draw(canvas, pose, nowSec, RenderStyle.FULL)
        canvas.restore()
        avatar.drawParticles(canvas, engine.particles, r, cx, cy - r * 0.3f)
        drawInfo(canvas, w, h, false)

        hitX = w / 2f
        hitY = h * AVATAR_Y - r * 0.2f
        hitR = r * 1.45f
    }

    override fun renderHighlightLayer(canvas: Canvas, bounds: Rect, zonedDateTime: ZonedDateTime, sharedAssets: Assets) {
        canvas.drawColor(renderParameters.highlightLayer!!.backgroundTint)
    }

    // ── Layers ──────────────────────────────────────────────────────────────

    private fun drawTime(canvas: Canvas, w: Float, h: Float, ambient: Boolean) {
        timePaint.typeface = if (ambient) ambientTimeTypeface else timeTypeface
        timePaint.color = if (ambient) 0xFFB8BED6.toInt() else 0xFFEEF1FF.toInt()
        timePaint.textSize = w * 0.19f
        canvas.drawText(timeText, w / 2f, h * TIME_BASELINE, timePaint)
    }

    private fun drawInfo(canvas: Canvas, w: Float, h: Float, ambient: Boolean) {
        infoPaint.textSize = w * 0.052f
        infoPaint.color = if (ambient) 0xFF6A7090.toInt() else 0xFF8A93C0.toInt()
        val gap = w * 0.05f
        val iw = w * 0.05f
        val ih = w * 0.026f
        val dateW = infoPaint.measureText(dateText)
        val battW = infoPaint.measureText(battText)
        val total = dateW + gap + iw + w * 0.012f + w * 0.015f + battW
        var x = (w - total) / 2f
        val y = h * INFO_BASELINE
        canvas.drawText(dateText, x, y, infoPaint)
        x += dateW + gap

        // Battery: outline, nub and level fill.
        val level = director.batteryLevel
        val accent = when {
            ambient -> 0xFF6A7090.toInt()
            director.charging -> 0xFF6EE7A0.toInt()
            level <= MoodDirector.LOW_BATTERY -> 0xFFFF9F5A.toInt()
            else -> 0xFF8CF5D2.toInt()
        }
        val top = y - ih - w * 0.004f
        iconPaint.color = accent
        iconPaint.style = Paint.Style.STROKE
        iconPaint.strokeWidth = w * 0.004f
        iconRect.set(x, top, x + iw, top + ih)
        canvas.drawRoundRect(iconRect, ih * 0.25f, ih * 0.25f, iconPaint)
        iconPaint.style = Paint.Style.FILL
        iconRect.set(x + iw, top + ih * 0.3f, x + iw + w * 0.008f, top + ih * 0.7f)
        canvas.drawRect(iconRect, iconPaint)
        if (!ambient) {
            val inset = w * 0.006f
            iconRect.set(x + inset, top + inset, x + inset + (iw - inset * 2) * level / 100f, top + ih - inset)
            canvas.drawRect(iconRect, iconPaint)
        }
        x += iw + w * 0.012f + w * 0.015f
        canvas.drawText(battText, x, y, infoPaint)
    }

    /** A dim, still pose for ambient: the mood reads, nothing moves. */
    private fun ambientPose(r: Float): JarvisPose {
        engine.pose(r, pose)
        pose.tilt = 0f; pose.sx = 1f; pose.sy = 1f
        pose.lookX = 0f; pose.lookY = 0f
        pose.eyeSwap = 1f; pose.mouthSwap = 1f
        pose.es = 1f
        pose.badge = BadgeKind.NONE
        pose.listen = false; pose.scan = false; pose.dizzy = false
        pose.dim = 0.85f
        pose.antenna = if (engine.state.antennaRest.isNaN()) 0f else engine.state.antennaRest
        pose.mouthOpen = 0f
        when (engine.state) {
            BotState.SLEEPING -> { pose.eyes = Eye.CLOSED; pose.mouth = Mouth.NONE }
            BotState.CHARGING -> { pose.eyes = Eye.HAPPY; pose.mouth = Mouth.SMILE }
            BotState.WORRIED -> { pose.eyes = Eye.WIDE; pose.mouth = Mouth.SMALL; pose.open = 0.8f }
            BotState.BORED -> { pose.eyes = Eye.FOCUS; pose.mouth = Mouth.FLAT; pose.open = 0.7f }
            else -> { pose.eyes = Eye.OPEN; pose.mouth = Mouth.SMILE; pose.open = 0.75f }
        }
        return pose
    }

    private fun updateText(z: ZonedDateTime, width: Int) {
        if (width != cachedWidth) {
            cachedWidth = width
            val w = width.toFloat()
            bgPaint.shader = RadialGradient(
                w / 2f, w * AVATAR_Y, w * 0.42f,
                0xFF161A3A.toInt(), Color.BLACK, Shader.TileMode.CLAMP,
            )
        }
        val minuteKey = z.hour * 60 + z.minute
        if (minuteKey != cachedMinute) {
            cachedMinute = minuteKey
            val h24 = DateFormat.is24HourFormat(context)
            val hh = if (h24) z.hour else (z.hour % 12).let { if (it == 0) 12 else it }
            timeText = if (h24) String.format(Locale.US, "%02d:%02d", hh, z.minute)
            else String.format(Locale.US, "%d:%02d", hh, z.minute)
            dateText = z.dayOfWeek.getDisplayName(TextStyle.SHORT, Locale.getDefault()).uppercase() + " " + z.dayOfMonth
        }
        if (director.batteryLevel != cachedBattery || director.charging != cachedCharging) {
            cachedBattery = director.batteryLevel
            cachedCharging = director.charging
            battText = "${director.batteryLevel}%"
        }
    }

    companion object {
        private const val TAG = "JarvisFace"
        const val DEBUG_ACTION = "com.jarvis.watchface.DEBUG"
        private const val FRAME_MS = 16L
        private const val ASSIST_DELAY_MS = 450L
        private const val CLOCK_RESTART_MS = 3 * 3600_000L

        // Layout, as fractions of the screen.
        private const val AVATAR_R = 0.2f
        private const val AVATAR_Y = 0.6f
        private const val TIME_BASELINE = 0.27f
        private const val INFO_BASELINE = 0.885f
    }
}
