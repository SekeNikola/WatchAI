package com.jarvis.watchface

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Path
import android.graphics.Typeface
import com.jarvis.avatar.BotState
import com.jarvis.avatar.Emote
import com.jarvis.avatar.JarvisEngine
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import java.io.File
import java.time.ZoneId
import java.time.ZonedDateTime

/**
 * Renders the real face (FacePainter + JarvisRenderer on a native android Canvas)
 * for every state, emote and ambient pose. Output: watchface/build/screens/.
 *
 *   ./gradlew :watchface:testDebugUnitTest --tests '*ScreenshotTest*'
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [34])
class ScreenshotTest {
    private val size = 450
    private val out = File("build/screens").apply { mkdirs() }
    private val time = ZonedDateTime.of(2026, 10, 20, 10, 8, 0, 0, ZoneId.systemDefault())

    private data class Shot(val name: String, val bitmap: Bitmap)

    @Test
    fun renderAll() {
        val shots = mutableListOf<Shot>()
        for (s in BotState.entries) {
            val battery = when (s) { BotState.WORRIED -> 12; BotState.CHARGING -> 64; BotState.FULL -> 100; else -> 82 }
            val onCharger = s == BotState.CHARGING || s == BotState.FULL
            shots += Shot(s.name.lowercase(), frame(battery = battery, charging = onCharger) { e ->
                e.setState(s, force = true); 1.6f
            })
        }
        for (em in Emote.entries) {
            shots += Shot("emote_" + em.name.lowercase(), frame { e ->
                e.update(0.5f)
                e.triggerEmote(em, 1.8f)
                if (em == Emote.YAWN) 1.4f else 0.95f
            })
        }
        for (s in listOf(BotState.IDLE, BotState.BORED, BotState.SLEEPING, BotState.WORRIED, BotState.CHARGING)) {
            shots += Shot("ambient_" + s.name.lowercase(), frame(ambient = true, charging = s == BotState.CHARGING) { e ->
                e.setState(s, force = true); 0.5f
            })
        }
        shots += Shot("music", frame { e -> e.music = true; 3.0f })
        shots += Shot("charging_bolts", frame(battery = 64, charging = true) { e -> e.setState(BotState.CHARGING, force = true); 3.2f })
        shots += Shot("ambient_notification", frame(ambient = true, alert = true) { e -> e.update(0.5f); 0.5f })
        shots.forEach { save(it.name, it.bitmap) }
        save("_contact_sheet", sheet(shots))
    }

    @Test
    fun renderCharacters() {
        for (ch in com.jarvis.avatar.Character.entries) {
            val shots = mutableListOf<Shot>()
            fun st(name: String, s: BotState, battery: Int = 82, charging: Boolean = false, until: Float = 1.6f) {
                shots += Shot(name, frame(battery = battery, charging = charging, character = ch) { e -> e.setState(s, force = true); until })
            }
            st("idle", BotState.IDLE)
            shots += Shot("love", frame(character = ch) { e -> e.update(0.5f); e.triggerEmote(Emote.LOVE, 1.8f); 0.95f })
            st("bored", BotState.BORED)
            st("annoyed", BotState.ANNOYED)
            st("angry", BotState.ANGRY)
            st("dizzy", BotState.DIZZY)
            st("notify", BotState.NOTIFY)
            shots += Shot("music", frame(character = ch) { e -> e.music = true; 3.0f })
            st("charging", BotState.CHARGING, 64, true, 3.2f)
            st("full", BotState.FULL, 100, true, 2.2f)
            st("worried", BotState.WORRIED, 12)
            st("sleeping", BotState.SLEEPING)
            shots += Shot("ambient", frame(ambient = true, character = ch) { e -> e.update(0.5f); 0.5f })
            shots += Shot("ambient_notification", frame(ambient = true, alert = true, character = ch) { e -> e.update(0.5f); 0.5f })
            save("_sheet_${ch.id}", sheet(shots))
            save("char_${ch.id}", icon(ch))
        }
    }

    /** Picker icon: the character alone on a dark disc. */
    private fun icon(ch: com.jarvis.avatar.Character): Bitmap {
        val n = 240
        val bmp = Bitmap.createBitmap(n, n, Bitmap.Config.ARGB_8888)
        val c = Canvas(bmp)
        c.drawCircle(n / 2f, n / 2f, n / 2f, Paint(Paint.ANTI_ALIAS_FLAG).apply { color = 0xFF15172B.toInt() })
        val engine = JarvisEngine().apply { wander = false; update(0f); update(0.05f) }
        val pose = engine.pose(n * 0.27f, com.jarvis.avatar.JarvisPose())
        c.translate(n / 2f, n * 0.58f)
        com.jarvis.avatar.JarvisRenderer().apply { character = ch }.draw(c, pose, 0f)
        return bmp
    }

    /** Runs the engine at 60 fps for the time the setup returns, then draws one frame. */
    private fun frame(
        battery: Int = 82, charging: Boolean = false, ambient: Boolean = false, alert: Boolean = false,
        character: com.jarvis.avatar.Character = com.jarvis.avatar.Character.JARVIS,
        setup: (JarvisEngine) -> Float,
    ): Bitmap {
        val engine = JarvisEngine()
        engine.wander = false
        engine.update(0f)
        val until = setup(engine)
        var t = 0.5f.coerceAtMost(until)
        while (t < until) { t += 1f / 60f; engine.update(t) }
        val bmp = Bitmap.createBitmap(size, size, Bitmap.Config.ARGB_8888)
        val c = Canvas(bmp)
        val painter = FacePainter(RuntimeEnvironment.getApplication())
        painter.character = character
        val w = size.toFloat()
        if (ambient) painter.drawAmbient(c, w, w, time, engine, battery, charging, false, alert)
        else painter.drawInteractive(c, w, w, time, engine, t, battery, charging)
        roundMask(c)
        return bmp
    }

    private fun roundMask(c: Canvas) {
        val p = Path().apply {
            fillType = Path.FillType.INVERSE_EVEN_ODD
            addCircle(size / 2f, size / 2f, size / 2f, Path.Direction.CW)
        }
        c.drawPath(p, Paint(Paint.ANTI_ALIAS_FLAG).apply { color = 0xFF2A2A2E.toInt() })
    }

    private fun sheet(shots: List<Shot>): Bitmap {
        val cols = 5
        val cell = 270
        val label = 34
        val rows = (shots.size + cols - 1) / cols
        val bmp = Bitmap.createBitmap(cols * cell, rows * (cell + label), Bitmap.Config.ARGB_8888)
        val c = Canvas(bmp)
        c.drawColor(0xFF2A2A2E.toInt())
        val text = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.WHITE; textSize = 22f; textAlign = Paint.Align.CENTER
            typeface = Typeface.create(Typeface.DEFAULT, Typeface.BOLD)
        }
        shots.forEachIndexed { i, s ->
            val x = (i % cols) * cell
            val y = (i / cols) * (cell + label)
            val scaled = Bitmap.createScaledBitmap(s.bitmap, cell - 10, cell - 10, true)
            c.drawBitmap(scaled, x + 5f, y + 5f, null)
            c.drawText(s.name, x + cell / 2f, y + cell + 22f, text)
        }
        return bmp
    }

    private fun save(name: String, bmp: Bitmap) {
        File(out, "$name.png").outputStream().use { bmp.compress(Bitmap.CompressFormat.PNG, 100, it) }
    }
}
