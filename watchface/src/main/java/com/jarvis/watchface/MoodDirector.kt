package com.jarvis.watchface

import android.util.Log
import com.jarvis.avatar.BotState
import com.jarvis.avatar.Emote
import com.jarvis.avatar.JarvisEngine
import kotlin.random.Random

/**
 * Decides what Jarvis feels from the signals a watch face can actually observe:
 * the clock, the battery, taps, wrist raises (ambient → interactive) and,
 * if access is granted, notifications and media.
 *
 * Taps climb a ladder: love → bored → annoyed → angry, and rapid tapping while
 * angry makes him dizzy. He calms down a step at a time when left alone.
 * (The assistant is on the watch's physical button, so taps never launch it.)
 *
 * Priority, highest first:
 *   debug hold → dizzy / tap mood → notify (after a notification)
 *   → full / charging → low battery (worried) → bored / asleep at night → idle
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
    private var taps = 0
    private var lastTap = -CALM_DELAY_MS
    private var lastCalmStep = 0L
    private var dizzyUntil = 0L
    private val rapid = LongArray(3) { -RAPID_MS }
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

    /** A tap on Jarvis: one rung up the love → bored → annoyed → angry ladder. */
    fun onTap(now: Long) {
        touch(now)
        engine.squash()
        if (now < dizzyUntil) return
        rapid[0] = rapid[1]; rapid[1] = rapid[2]; rapid[2] = now
        if (tapMood(taps) == BotState.ANGRY && now - rapid[0] < RAPID_MS) {
            // Three quick taps while angry: dizzy, then over it (the desktop "slap" behaviour).
            dizzyUntil = now + DIZZY_MS
            taps = 0
            engine.setState(BotState.DIZZY)
            return
        }
        taps++
        lastTap = now
        lastCalmStep = now
        when (tapMood(taps)) {
            null -> engine.triggerEmote(Emote.LOVE, 1.6f)
            BotState.BORED -> engine.sigh()
            BotState.ANNOYED -> engine.shake(0.04f)
            BotState.ANGRY -> engine.shake(0.09f)
            else -> Unit
        }
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
        if (dizzyUntil in 1..now) {
            dizzyUntil = 0
            engine.triggerEmote(Emote.HAPPY)
        }
        // Left alone, he calms down one rung at a time.
        if (taps > 0 && now - lastTap > CALM_DELAY_MS && now - lastCalmStep > CALM_STEP_MS) {
            lastCalmStep = now
            taps = when (tapMood(taps)) {
                BotState.ANGRY -> 6
                BotState.ANNOYED -> 4
                else -> 0
            }
        }
        if (noticeAt in 1..now) {
            noticeAt = 0
            touch(now)
            engine.triggerEmote(Emote.SURPRISED, 1.0f)
        }
        engine.setState(choose(now, hour))
    }

    private fun choose(now: Long, hour: Float): BotState {
        hold?.let { return it }
        if (now < dizzyUntil) return BotState.DIZZY
        tapMood(taps)?.let { return it }
        if (now < remindUntil) return BotState.NOTIFY
        if (charging) return if (batteryLevel >= 100) BotState.FULL else BotState.CHARGING
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
        const val CALM_DELAY_MS = 8_000L
        const val CALM_STEP_MS = 3_000L
        const val RAPID_MS = 1_700L
        const val DIZZY_MS = 3_300L

        /** Taps 1–2 love (an emote, no state change), 3–4 bored, 5–6 annoyed, 7+ angry. */
        fun tapMood(taps: Int): BotState? = when {
            taps >= 7 -> BotState.ANGRY
            taps >= 5 -> BotState.ANNOYED
            taps >= 3 -> BotState.BORED
            else -> null
        }
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
