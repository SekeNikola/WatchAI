package com.jarvis.watchface

import android.app.Activity
import android.graphics.Color
import android.os.Bundle
import android.view.Gravity
import android.widget.Button
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import com.jarvis.avatar.BotState
import com.jarvis.avatar.Emote

/**
 * Debug builds only. Each button sends one command to the watch face and closes,
 * so you land back on the face and see the result. States stay held until "clear".
 */
class DebugActivity : Activity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val pad = (resources.displayMetrics.widthPixels * 0.14f).toInt()
        val list = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(pad, pad / 2, pad, pad)
        }
        fun header(text: String) = list.addView(TextView(this).apply {
            this.text = text
            setTextColor(Color.parseColor("#8CF5D2"))
            gravity = Gravity.CENTER
            setPadding(0, 24, 0, 8)
        })
        fun button(label: String, cmd: String, close: Boolean = true) = list.addView(Button(this).apply {
            text = label
            isAllCaps = false
            setOnClickListener {
                AvatarBus.post(cmd)
                if (close) finish()
            }
        })

        header("Jarvis debug")
        button("Clear (back to live)", "clear")
        button("Tap (mood ladder)", "tap")
        button("Bored now", "bored")
        button("Notify: reminder", "notify:remind")
        button("Notify: message", "notify:message")
        button("Battery 10%", "battery:10:false")
        button("Charging 60%", "battery:60:true")
        button("Real battery", "battery:real")
        button("Night (02:00)", "hour:2")
        button("Morning (08:00)", "hour:8")
        button("Real time", "hour:real")
        header("States (held)")
        BotState.entries.forEach { button(it.name.lowercase(), "state:${it.name}") }
        header("Emotes")
        Emote.entries.forEach { button(it.name.lowercase(), "emote:${it.name}") }
        header("Assistant intents")
        list.addView(TextView(this).apply {
            text = AssistantLauncher.probe(this@DebugActivity) + "\n\nLast: " + AssistantLauncher.lastResult
            setTextColor(Color.LTGRAY)
            textSize = 11f
        })

        setContentView(ScrollView(this).apply {
            setBackgroundColor(Color.BLACK)
            addView(list)
        })
    }
}
