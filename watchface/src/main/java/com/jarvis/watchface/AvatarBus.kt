package com.jarvis.watchface

import java.util.concurrent.ConcurrentLinkedQueue

/**
 * In-process mailbox between the watch face and everything that pokes it: the
 * notification listener, the debug receiver and the debug screen. Commands are
 * plain strings (the same ones the adb debug broadcast takes) and are drained by
 * the renderer on its own thread each frame.
 *
 *   notify:remind | notify:message | notify:other
 *   state:<BotState> | emote:<Emote> | clear
 *   battery:<0-100>:<charging true|false> | battery:real
 *   hour:<0-23.99> | hour:real
 *   bored | tap | assist
 */
object AvatarBus {
    private val queue = ConcurrentLinkedQueue<String>()

    /** Set by the renderer so a command can wake an ambient frame. */
    @Volatile
    var onPost: (() -> Unit)? = null

    fun post(command: String) {
        queue.add(command.trim())
        onPost?.invoke()
    }

    fun poll(): String? = queue.poll()
}
