package com.jarvis.watchface

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.graphics.Canvas
import android.graphics.Rect
import android.os.BatteryManager
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.view.SurfaceHolder
import androidx.wear.watchface.CanvasType
import androidx.wear.watchface.DrawMode
import androidx.wear.watchface.Renderer
import androidx.wear.watchface.WatchState
import androidx.wear.watchface.style.CurrentUserStyleRepository
import com.jarvis.avatar.JarvisEngine
import java.time.ZonedDateTime

/**
 * Framework glue: frame timing, ambient transitions, battery and debug input.
 * Interactive: full animation at ~60 fps. Ambient: the framework redraws once
 * a minute and we draw a static, outline-only face (see FacePainter).
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
    private val painter = FacePainter(context)
    private val main = Handler(Looper.getMainLooper())

    private var lastMode: DrawMode? = null
    private var clockBase = SystemClock.uptimeMillis()

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

    /** Called from the tap listener. Taps on Jarvis open the assistant. */
    fun onTap(x: Int, y: Int) {
        val dx = x - painter.hitX
        val dy = y - painter.hitY
        if (painter.hitR <= 0f || dx * dx + dy * dy > painter.hitR * painter.hitR) return
        director.onTap(SystemClock.uptimeMillis())
        // A short beat first so the listening perk-up shows before Gemini covers the face.
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
            when {
                cmd == "assist" -> AssistantLauncher.launch(context)
                cmd.startsWith("notify") -> {
                    val parts = cmd.split(':')
                    val postedAt = parts.getOrNull(2)?.toLongOrNull() ?: nowMs
                    director.onNotification(parts.getOrElse(1) { "remind" }, nowMs, !ambient, postedAt)
                }
                else -> director.command(cmd, nowMs, !ambient)
            }
        }
        director.update(nowMs, hour)
        engine.mail = AvatarBus.unread > 0

        val w = bounds.width().toFloat()
        val h = bounds.height().toFloat()
        if (ambient) {
            painter.drawAmbient(
                canvas, w, h, zonedDateTime, engine, director.batteryLevel, director.charging,
                watchState.hasLowBitAmbient, director.hasPendingAlert(nowMs) || AvatarBus.unread > 0,
            )
        } else {
            val t = (nowMs - clockBase) / 1000f
            engine.update(t)
            painter.drawInteractive(canvas, w, h, zonedDateTime, engine, t, director.batteryLevel, director.charging)
        }
    }

    override fun renderHighlightLayer(canvas: Canvas, bounds: Rect, zonedDateTime: ZonedDateTime, sharedAssets: Assets) {
        canvas.drawColor(renderParameters.highlightLayer!!.backgroundTint)
    }

    companion object {
        const val DEBUG_ACTION = "com.jarvis.watchface.DEBUG"
        private const val FRAME_MS = 16L
        private const val ASSIST_DELAY_MS = 450L
        private const val CLOCK_RESTART_MS = 3 * 3600_000L
    }
}
