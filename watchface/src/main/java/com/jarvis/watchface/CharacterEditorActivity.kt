package com.jarvis.watchface

import android.graphics.Color
import android.os.Bundle
import android.util.Log
import android.view.Gravity
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import androidx.activity.ComponentActivity
import androidx.lifecycle.lifecycleScope
import androidx.wear.watchface.editor.EditorSession
import androidx.wear.watchface.style.UserStyleSetting
import androidx.wear.watchface.style.UserStyleSetting.ListUserStyleSetting
import com.jarvis.avatar.Character
import kotlinx.coroutines.launch

/**
 * The watch face's Customize screen: pick a character. Opened from the system
 * watch face editor (long-press the face → Edit). The choice is saved as the
 * face's user style when the screen closes.
 */
class CharacterEditorActivity : ComponentActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        lifecycleScope.launch {
            // Only the system's Customize flow supplies the editor request; opened any other
            // way the library throws. Close quietly: the face shares this process.
            val session = try {
                EditorSession.createOnWatchEditorSession(this@CharacterEditorActivity)
            } catch (e: Exception) {
                Log.w("JarvisEditor", "not opened from Customize: $e")
                finish()
                return@launch
            }
            val setting = session.userStyleSchema.userStyleSettings
                .first { it.id.value == Styles.CHARACTER_ID } as ListUserStyleSetting
            val current = Styles.character(session.userStyle.value)
            showPicker(current) { picked ->
                val option = setting.options.first {
                    it.id == UserStyleSetting.Option.Id(picked.id)
                }
                session.userStyle.value = session.userStyle.value.toMutableUserStyle()
                    .apply { this[setting] = option }
                    .toUserStyle()
                finish()
            }
        }
    }

    private fun showPicker(current: Character, onPick: (Character) -> Unit) {
        val w = resources.displayMetrics.widthPixels
        val list = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER_HORIZONTAL
            setPadding(w / 8, w / 10, w / 8, w / 6)
        }
        list.addView(TextView(this).apply {
            text = getString(R.string.character_setting)
            setTextColor(Color.parseColor("#8CF5D2"))
            textSize = 15f
            gravity = Gravity.CENTER
            setPadding(0, 0, 0, w / 24)
        })
        for (ch in Character.entries) {
            val row = LinearLayout(this).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
                setPadding(w / 30, w / 40, w / 30, w / 40)
                background = android.graphics.drawable.GradientDrawable().apply {
                    cornerRadius = w / 10f
                    setColor(if (ch == current) Color.parseColor("#2A2F5A") else Color.parseColor("#15172B"))
                    if (ch == current) setStroke(3, Color.parseColor("#8CF5D2"))
                }
                setOnClickListener { onPick(ch) }
            }
            row.addView(ImageView(this).apply {
                setImageResource(Styles.iconRes(ch))
                layoutParams = LinearLayout.LayoutParams(w / 5, w / 5)
            })
            row.addView(TextView(this).apply {
                text = ch.displayName
                setTextColor(Color.WHITE)
                textSize = 16f
                setPadding(w / 20, 0, 0, 0)
            })
            list.addView(row, LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT,
            ).apply { bottomMargin = w / 40 })
        }
        setContentView(ScrollView(this).apply {
            setBackgroundColor(Color.BLACK)
            addView(list)
        })
    }
}
