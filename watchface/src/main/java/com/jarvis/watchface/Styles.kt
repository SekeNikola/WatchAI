package com.jarvis.watchface

import android.content.Context
import android.graphics.drawable.Icon
import androidx.wear.watchface.style.UserStyle
import androidx.wear.watchface.style.UserStyleSchema
import androidx.wear.watchface.style.UserStyleSetting
import androidx.wear.watchface.style.UserStyleSetting.ListUserStyleSetting
import androidx.wear.watchface.style.WatchFaceLayer
import com.jarvis.avatar.Character

/** The watch face's user style: one setting, the character, first in the editor. */
object Styles {
    const val CHARACTER_ID = "character"

    // Large icons for our picker screen.
    private val ICONS = mapOf(
        Character.JARVIS to R.drawable.char_jarvis,
        Character.DOG to R.drawable.char_dog,
        Character.GHOST to R.drawable.char_ghost,
    )

    // Small icons inside the schema: the whole schema is sent over Bluetooth and the
    // library refuses (crashes the face) above 50 000 bytes. Keep these ~64 px.
    private val SCHEMA_ICONS = mapOf(
        Character.JARVIS to R.drawable.char_jarvis_small,
        Character.DOG to R.drawable.char_dog_small,
        Character.GHOST to R.drawable.char_ghost_small,
    )
    private val NAMES = mapOf(
        Character.JARVIS to R.string.char_jarvis,
        Character.DOG to R.string.char_dog,
        Character.GHOST to R.string.char_ghost,
    )

    fun iconRes(c: Character): Int = ICONS.getValue(c)

    fun schema(context: Context): UserStyleSchema {
        val options = Character.entries.map { ch ->
            ListUserStyleSetting.ListOption(
                UserStyleSetting.Option.Id(ch.id),
                context.resources,
                NAMES.getValue(ch),
                Icon.createWithResource(context, SCHEMA_ICONS.getValue(ch)),
            )
        }
        val character = ListUserStyleSetting(
            UserStyleSetting.Id(CHARACTER_ID),
            context.resources,
            R.string.character_setting,
            R.string.character_setting_desc,
            null,
            options,
            listOf(WatchFaceLayer.BASE),
        )
        return UserStyleSchema(listOf(character))
    }

    /** The character selected in [style], Jarvis if unset. */
    fun character(style: UserStyle): Character {
        for ((setting, option) in style) {
            if (setting.id.value == CHARACTER_ID) return Character.fromId(option.id.value.decodeToString())
        }
        return Character.JARVIS
    }
}
