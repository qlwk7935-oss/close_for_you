package kr.closeforyou.ui.voice

import kotlinx.coroutines.flow.StateFlow

enum class SpeechReadiness { INITIALIZING, READY, UNAVAILABLE }

/**
 * C/F10: the smallest speech surface the announcer needs, so JVM tests can replace Android TTS.
 * Implementations must not log or persist the spoken text.
 */
interface SpeechEngine {
    val readiness: StateFlow<SpeechReadiness>

    /** Suspends until playback ends. True only when the whole utterance finished playing. */
    suspend fun speak(text: String): Boolean

    /** Interrupts the current utterance; a suspended [speak] then returns false. */
    fun stop()

    fun shutdown()
}
