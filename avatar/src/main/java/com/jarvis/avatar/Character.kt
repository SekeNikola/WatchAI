package com.jarvis.avatar

/**
 * Who is drawn. All characters share the face (eyes, mouth, badges, particles),
 * the engine and every emotion; each one only brings its own body and where its
 * face sits. The status light is the antenna bulb (Jarvis), the collar tag (Dog)
 * or the floating wisp (Ghost).
 */
enum class Character(val id: String, val displayName: String) {
    JARVIS("jarvis", "Jarvis"),
    DOG("dog", "Biscuit"),
    GHOST("ghost", "Boo");

    companion object {
        fun fromId(id: String?): Character = entries.firstOrNull { it.id == id } ?: JARVIS
    }
}
