package com.jarvis.watchface

import android.util.Log
import com.jarvis.avatar.BotState
import com.jarvis.avatar.Emote
import com.jarvis.avatar.JarvisEngine
import kotlin.random.Random

/**
 * Decides what Jarvis feels from the signals a watch face can actually observe:
 * the clock, the battery, taps, wrist raises (ambient → interactive) and,
 * if access is granted, notifications. Gemini's own state is NOT observable,
 * so the tap "listening" pose is purely timed.
 *
 * Priority, highest first:
 *   debug hold → listening (after a tap) → remind (after a notification)
 *   → charging → low battery (worried) → bored / asleep at night → idle
 *
 * All times are uptime milliseconds.
 */
class MoodDirector(private val engine: JarvisEngine) {

    var batteryLevel = 100
        private set
    var charging = false
        private set
    private var realBattery = 100
    private var realCharging = false
    private var batteryOverride = false

    private var hourOverride = Float.NaN

    private var lastInteraction = 0L
    private var boredAfterMs = randomBoredDelay()
    private var listenUntil = 0L
    private var remindUntil = 0L
    private var pendingNotify: String? = null
    private var pendingNotifyAt = 0L
    private var noticeAt = 0L
    private var hold: BotState? = null
    private var lastMorningDay = -1
    private var lastYawn = -YAWN_GAP_MS

    fun start(now: Long) {
        lastInteraction = now
    }

    fun setRealBattery(level: Int, isCharging: Boolean) {
        realBattery = level
        realCharging = isCharging
        if (!batteryOverride) {
            batteryLevel = level
            charging = isCharging
        }
    }

    fun hourOfDay(realHour: Float): Float = if (hourOverride.isNaN()) realHour else hourOverride

    /** A tap on Jarvis: hop, then the timed listening pose while Gemini opens. */
    fun onTap(now: Long) {
        touch(now)
        engine.squash()
        listenUntil = now + LISTEN_MS
    }

    /** The screen came back from ambient (wrist raise / touch). */
    fun onWake(now: Long, hour: Float, dayOfYear: Int) {
        engine.settle()
        val pending = pendingNotify
        if (pending != null && now - pendingNotifyAt < PENDING_NOTIFY_MS) {
            pendingNotify = null
            // Wear OS shows its notification card on this same wrist raise, so hold
            // the alert long enough to still be playing when the card is swiped away.
            engine.triggerEmote(Emote.SURPRISED, 1.2f)
            remindUntil = now + REPLAY_MS
            touch(now)
            return
        }
        pendingNotify = null
        val st = engine.state
        if (st == BotState.BORED || st == BotState.SLEEPING) {
            // Let the moment read, then he notices you.
            noticeAt = now + NOTICE_DELAY_MS
            return
        }
        greet(now, hour, dayOfYear)
    }

    /** A notification arrived while in ambient and hasn't been shown yet. */
    fun hasPendingAlert(now: Long): Boolean =
        pendingNotify != null && now - pendingNotifyAt < PENDING_NOTIFY_MS

    /**
     * [postedAt] is when the notification arrived. If it waited in the queue (the
     * face doesn't draw while Wear's notification card covers it), the reaction is
     * held longer so it is still playing when the card is swiped away.
     */
    fun onNotification(kind: String, now: Long, interactive: Boolean, postedAt: Long = now) {
        Log.i(TAG, "notification '$kind' while ${if (interactive) "interactive" else "ambient"}")
        if (interactive) {
            notify(kind, now)
            if (now - postedAt > HIDDEN_MS) remindUntil = maxOf(remindUntil, now + REPLAY_MS)
            touch(now)
        } else {
            pendingNotify = kind
            pendingNotifyAt = now
        }
    }

    /** Debug / adb commands. Returns true if it was understood. */
    fun command(cmd: String, now: Long, interactive: Boolean): Boolean {
        val parts = cmd.split(':')
        try {
            when (parts[0].lowercase()) {
                "state" -> {
                    hold = BotState.valueOf(parts[1].uppercase())
                    engine.setState(hold!!, force = true)
                }
                "emote" -> engine.triggerEmote(Emote.valueOf(parts[1].uppercase()), parts.getOrNull(2)?.toFloat() ?: 1.8f)
                "clear" -> {
                    hold = null
                    batteryOverride = false; batteryLevel = realBattery; charging = realCharging
                    hourOverride = Float.NaN
                    touch(now)
                }
                "notify" -> onNotification(parts.getOrElse(1) { "remind" }, now, interactive, parts.getOrNull(2)?.toLongOrNull() ?: now)
                "battery" -> if (parts[1] == "real") {
                    batteryOverride = false; batteryLevel = realBattery; charging = realCharging
                } else {
                    batteryOverride = true
                    batteryLevel = parts[1].toInt()
                    charging = parts.getOrNull(2)?.toBoolean() ?: false
                }
                "hour" -> hourOverride = if (parts[1] == "real") Float.NaN else parts[1].toFloat()
                "bored" -> { hold = null; lastInteraction = now - boredAfterMs - 1 }
                "tap" -> onTap(now)
                "unread" -> AvatarBus.setUnread(parts[1].toInt())
                "music" -> AvatarBus.setMusic(parts[1] == "on")
                else -> return false
            }
        } catch (e: Exception) {
            Log.w(TAG, "bad command '$cmd': $e")
            return false
        }
        Log.i(TAG, "command '$cmd' → ${engine.state}")
        return true
    }

    /** Picks the base state; call every frame (and once per ambient minute). */
    fun update(now: Long, hour: Float) {
        engine.drowsy = drowsiness(hour)
        if (noticeAt in 1..now) {
            noticeAt = 0
            touch(now)
            engine.triggerEmote(Emote.SURPRISED, 1.0f)
        }
        engine.setState(choose(now, hour))
    }

    private fun choose(now: Long, hour: Float): BotState {
        hold?.let { return it }
        if (now < listenUntil) return BotState.LISTENING
        if (now < remindUntil) return BotState.NOTIFY
        if (charging) return BotState.CHARGING
        if (batteryLevel <= LOW_BATTERY) return BotState.WORRIED
        if (now - lastInteraction > boredAfterMs) {
            return if (drowsiness(hour) >= 0.8f) BotState.SLEEPING else BotState.BORED
        }
        return BotState.IDLE
    }

    private fun notify(kind: String, now: Long) {
        engine.triggerEmote(Emote.SURPRISED, 1.2f)
        remindUntil = now + when (kind) {
            "remind" -> REMIND_MS
            else -> MESSAGE_MS
        }
    }

    /** Small moods on wake, as on desktop: love on the first morning look, a yawn late at night. */
    private fun greet(now: Long, hour: Float, dayOfYear: Int) {
        if (hour in 5f..11f && lastMorningDay != dayOfYear) {
            lastMorningDay = dayOfYear
            engine.triggerEmote(Emote.LOVE, 1.6f)
            return
        }
        val late = hour >= 23f || hour < 5f
        if (late && now - lastYawn > YAWN_GAP_MS && Random.nextFloat() < 0.5f) {
            lastYawn = now
            engine.triggerEmote(Emote.YAWN, 2.2f)
        }
    }

    private fun touch(now: Long) {
        lastInteraction = now
        boredAfterMs = randomBoredDelay()
    }

    companion object {
        private const val TAG = "JarvisMood"
        const val LISTEN_MS = 4_000L
        const val REMIND_MS = 6_000L
        const val MESSAGE_MS = 3_000L
        const val REPLAY_MS = 12_000L
        const val HIDDEN_MS = 1_000L
        const val NOTICE_DELAY_MS = 1_400L
        const val PENDING_NOTIFY_MS = 10 * 60_000L
        const val YAWN_GAP_MS = 20 * 60_000L
        const val LOW_BATTERY = 15

        private fun randomBoredDelay() = 30_000L + Random.nextLong(30_001L)

        /** Desktop curve: sleepy from 21:00, fully drowsy 23:30–5:00, awake by 7. */
        fun drowsiness(hour: Float): Float = when {
            hour >= 21f -> minOf(1f, (hour - 21f) / 2.5f)
            hour < 5f -> 1f
            hour < 7f -> 1f - (hour - 5f) / 2f
            else -> 0f
        }
    }
}
