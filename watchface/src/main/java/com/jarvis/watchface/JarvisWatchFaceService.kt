package com.jarvis.watchface

import android.view.SurfaceHolder
import androidx.wear.watchface.ComplicationSlot
import androidx.wear.watchface.ComplicationSlotsManager
import androidx.wear.watchface.TapEvent
import androidx.wear.watchface.TapType
import androidx.wear.watchface.WatchFace
import androidx.wear.watchface.WatchFaceService
import androidx.wear.watchface.WatchFaceType
import androidx.wear.watchface.WatchState
import androidx.wear.watchface.style.CurrentUserStyleRepository
import androidx.wear.watchface.style.UserStyleSchema

class JarvisWatchFaceService : WatchFaceService() {

    override fun createUserStyleSchema(): UserStyleSchema = Styles.schema(this)

    override fun createComplicationSlotsManager(currentUserStyleRepository: CurrentUserStyleRepository) =
        UnreadSlot.manager(currentUserStyleRepository)

    override suspend fun createWatchFace(
        surfaceHolder: SurfaceHolder,
        watchState: WatchState,
        complicationSlotsManager: ComplicationSlotsManager,
        currentUserStyleRepository: CurrentUserStyleRepository,
    ): WatchFace {
        val renderer = JarvisCanvasRenderer(
            applicationContext, surfaceHolder, watchState, currentUserStyleRepository, complicationSlotsManager,
        )
        return WatchFace(WatchFaceType.DIGITAL, renderer).setTapListener(
            object : WatchFace.TapListener {
                override fun onTapEvent(tapType: Int, tapEvent: TapEvent, complicationSlot: ComplicationSlot?) {
                    if (tapType == TapType.UP) renderer.onTap(tapEvent.xPos, tapEvent.yPos)
                }
            },
        )
    }
}
