package com.jarvis.watchface

import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.util.Log

/**
 * Opens the system assistant (Gemini on current Wear OS) the way a hardware
 * long-press would. Tries the intents in order and uses the first one that
 * resolves. We get no signal back about what the assistant is doing.
 */
object AssistantLauncher {
    private const val TAG = "JarvisAssist"

    private val ACTIONS = listOf(
        Intent.ACTION_VOICE_COMMAND,
        Intent.ACTION_ASSIST,
        "android.intent.action.VOICE_ASSIST",
    )

    /** Last outcome, for the debug screen and logcat. */
    @Volatile
    var lastResult = "not tried"
        private set

    fun launch(context: Context): Boolean {
        val pm = context.packageManager
        for (action in ACTIONS) {
            val intent = Intent(action).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            val target = intent.resolveActivity(pm) ?: continue
            try {
                context.startActivity(intent)
                lastResult = "$action → ${target.flattenToShortString()}"
                Log.i(TAG, "launched $lastResult")
                return true
            } catch (e: ActivityNotFoundException) {
                Log.w(TAG, "$action resolved to $target but failed: $e")
            } catch (e: SecurityException) {
                Log.w(TAG, "$action blocked: $e")
            }
        }
        lastResult = "no assistant intent resolved"
        Log.w(TAG, lastResult)
        return false
    }

    /** Which actions resolve on this device, for diagnostics. */
    fun probe(context: Context): String = ACTIONS.joinToString("\n") { action ->
        val target = Intent(action).resolveActivity(context.packageManager)
        "$action → ${target?.flattenToShortString() ?: "none"}"
    }
}
