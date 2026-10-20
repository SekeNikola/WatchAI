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

    private val ICONS = mapOf(
        Character.JARVIS to R.drawable.char_jarvis,
        Character.DOG to R.drawable.char_dog,
        Character.GHOST to R.drawable.char_ghost,
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
                Icon.createWithResource(context, ICONS.getValue(ch)),
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
