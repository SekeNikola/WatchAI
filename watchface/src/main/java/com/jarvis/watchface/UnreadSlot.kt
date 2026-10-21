package com.jarvis.watchface

import android.content.res.Resources
import android.graphics.Canvas
import android.graphics.Rect
import android.graphics.RectF
import androidx.wear.watchface.CanvasComplication
import androidx.wear.watchface.CanvasComplicationFactory
import androidx.wear.watchface.ComplicationSlot
import androidx.wear.watchface.ComplicationSlotsManager
import androidx.wear.watchface.RenderParameters
import androidx.wear.watchface.complications.ComplicationSlotBounds
import androidx.wear.watchface.complications.DefaultComplicationDataSourcePolicy
import androidx.wear.watchface.complications.SystemDataSources
import androidx.wear.watchface.complications.data.ComplicationData
import androidx.wear.watchface.complications.data.ComplicationType
import androidx.wear.watchface.complications.data.NoDataComplicationData
import androidx.wear.watchface.complications.data.ShortTextComplicationData
import androidx.wear.watchface.style.CurrentUserStyleRepository
import java.time.Instant
import java.time.ZonedDateTime

/**
 * A hidden, fixed complication fed by the system's own unread-notification counter
 * (the number behind Wear's unread dot). Notifications forwarded from the phone live
 * in Wear's stream and may never show up to a NotificationListenerService on the
 * watch, so this is the reliable source for the envelope badge.
 *
 * It is never drawn, can't be changed in the editor, and sits in a corner that is
 * outside the round screen, so it never takes a tap.
 */
object UnreadSlot {
    const val ID = 100

    fun manager(repo: CurrentUserStyleRepository): ComplicationSlotsManager {
        val factory = CanvasComplicationFactory { _, _ -> Holder() }
        val slot = ComplicationSlot.createRoundRectComplicationSlotBuilder(
            ID,
            factory,
            listOf(ComplicationType.SHORT_TEXT),
            DefaultComplicationDataSourcePolicy(
                SystemDataSources.DATA_SOURCE_UNREAD_NOTIFICATION_COUNT,
                ComplicationType.SHORT_TEXT,
            ),
            ComplicationSlotBounds(RectF(0f, 0f, 0.02f, 0.02f)),
        ).setFixedComplicationDataSource(true).build()
        return ComplicationSlotsManager(listOf(slot), repo)
    }

    /**
     * The system's unread count, or null when the watch doesn't provide one (then the
     * notification listener's own count is used instead).
     */
    fun count(data: ComplicationData, resources: Resources): Int? = when (data) {
        is ShortTextComplicationData ->
            data.text.getTextAt(resources, Instant.now()).toString().trim().toIntOrNull() ?: 0
        is NoDataComplicationData -> null
        else -> null
    }

    /** Keeps the data; draws nothing. */
    private class Holder : CanvasComplication {
        private var data: ComplicationData = NoDataComplicationData()
        override fun render(canvas: Canvas, bounds: Rect, zonedDateTime: ZonedDateTime, renderParameters: RenderParameters, slotId: Int) = Unit
        override fun drawHighlight(canvas: Canvas, bounds: Rect, boundsType: Int, zonedDateTime: ZonedDateTime, color: Int) = Unit
        override fun getData(): ComplicationData = data
        override fun loadData(complicationData: ComplicationData, loadDrawablesAsynchronous: Boolean) {
            data = complicationData
        }
    }
}
