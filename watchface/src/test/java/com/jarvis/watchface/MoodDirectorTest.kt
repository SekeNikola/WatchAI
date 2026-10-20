package com.jarvis.watchface

import com.jarvis.avatar.BotState
import com.jarvis.avatar.JarvisEngine
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class MoodDirectorTest {
    private val engine = JarvisEngine()
    private val d = MoodDirector(engine).apply { start(0L); setRealBattery(80, false) }
    private val noon = 12f

    @Test
    fun idleThenBoredWithin30To60s() {
        d.update(29_000L, noon)
        assertEquals(BotState.IDLE, engine.state)
        d.update(61_000L, noon)
        assertEquals(BotState.BORED, engine.state)
    }

    @Test
    fun asleepInsteadOfBoredLateAtNight() {
        d.update(61_000L, 2f)
        assertEquals(BotState.SLEEPING, engine.state)
    }

    @Test
    fun tapLadderThenCalmsDown() {
        var t = 1_000L
        fun tap() { d.onTap(t); d.update(t + 10, noon); t += 2_000L }
        tap(); tap()
        assertEquals(BotState.IDLE, engine.state) // love is an emote
        tap()
        assertEquals(BotState.BORED, engine.state)
        tap(); tap()
        assertEquals(BotState.ANNOYED, engine.state)
        tap(); tap()
        assertEquals(BotState.ANGRY, engine.state)
        // Left alone: angry → annoyed → bored → calm.
        t += MoodDirector.CALM_DELAY_MS
        d.update(t, noon); assertEquals(BotState.ANNOYED, engine.state)
        t += MoodDirector.CALM_STEP_MS + 1
        d.update(t, noon); assertEquals(BotState.BORED, engine.state)
        t += MoodDirector.CALM_STEP_MS + 1
        d.update(t, noon); assertEquals(BotState.IDLE, engine.state)
    }

    @Test
    fun rapidTapsWhileAngryMakeHimDizzy() {
        repeat(7) { d.onTap(1_000L + it * 2_000L) }
        d.update(14_000L, noon)
        assertEquals(BotState.ANGRY, engine.state)
        d.onTap(14_100L); d.onTap(14_400L); d.onTap(14_700L)
        d.update(14_800L, noon)
        assertEquals(BotState.DIZZY, engine.state)
        d.update(14_700L + MoodDirector.DIZZY_MS + 1, noon)
        assertEquals(BotState.IDLE, engine.state)
    }

    @Test
    fun batteryMoods() {
        d.setRealBattery(10, false)
        d.update(1_000L, noon)
        assertEquals(BotState.WORRIED, engine.state)
        d.setRealBattery(10, true)
        d.update(2_000L, noon)
        assertEquals(BotState.CHARGING, engine.state)
        d.setRealBattery(100, true)
        d.update(3_000L, noon)
        assertEquals(BotState.FULL, engine.state)
    }

    @Test
    fun reminderWhileAmbientPlaysOnWake() {
        d.onNotification("remind", 5_000L, interactive = false)
        d.update(5_100L, noon)
        assertEquals(BotState.IDLE, engine.state)
        d.onWake(20_000L, noon, 1)
        d.update(20_100L, noon)
        assertEquals(BotState.NOTIFY, engine.state)
        d.update(20_000L + MoodDirector.REPLAY_MS + 1, noon)
        assertEquals(BotState.IDLE, engine.state)
    }

    @Test
    fun debugHoldAndClear() {
        d.command("state:dizzy", 1_000L, true)
        d.update(100_000L, noon)
        assertEquals(BotState.DIZZY, engine.state)
        d.command("clear", 100_000L, true)
        d.update(100_100L, noon)
        assertEquals(BotState.IDLE, engine.state)
    }
}
