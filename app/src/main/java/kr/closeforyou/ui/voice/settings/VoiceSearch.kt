package kr.closeforyou.ui.voice.settings

import androidx.annotation.StringRes
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kr.closeforyou.R
import kr.closeforyou.core.contract.VoiceAvailability
import kr.closeforyou.ui.voice.command.DictationResult
import kr.closeforyou.ui.voice.command.SpeakButtonState
import kr.closeforyou.ui.voice.command.VoiceDictation

/**
 * The heard sentence turned into an app search: request endings ("찾아 줘", "검색") and a trailing
 * "앱"/"어플" or object particle are dropped. Dropping only ever shortens the query, and
 * [HelpAppsCatalog.search] matches parts of names, so it never hides an app. Null = nothing to search.
 */
object VoiceSearchQuery {
    // Longest first, compared without spaces ("찾아 줘" = "찾아줘").
    private val endings = listOf(
        "검색해주세요", "찾아주세요", "검색해줘요", "찾아줘요", "검색해줘", "찾아줘", "보여줘", "검색해", "찾아", "검색",
        "어플", "앱",
    ).sortedByDescending { it.length }
    private val particles = listOf("을", "를")
    private const val PUNCTUATION = ".,!?~…·'\"()[]"

    fun from(heard: String?): String? {
        var query = heard?.trimText() ?: return null
        var dropped = true
        while (dropped && query.isNotEmpty()) {
            dropped = false
            val compact = query.filterNot { it.isWhitespace() }
            val ending = endings.firstOrNull { compact.endsWith(it) }
            if (ending != null) {
                query = query.dropLastLetters(ending.length).trimText()
                dropped = true
                // "카카오톡을 찾아 줘": the particle only goes together with an ending, never on its own.
                val particle = particles.firstOrNull { query.endsWith(it) && query.length > it.length }
                if (particle != null) query = query.dropLast(particle.length).trimText()
            }
        }
        return query.ifEmpty { null }
    }

    private fun String.trimText() = trim { it.isWhitespace() || it in PUNCTUATION }

    /** Drops [count] letters from the end, skipping the spaces between them. */
    private fun String.dropLastLetters(count: Int): String {
        var left = count
        var end = length
        while (end > 0 && left > 0) {
            if (!this[end - 1].isWhitespace()) left--
            end--
        }
        return substring(0, end)
    }
}

/** [말로 찾기] and its one status line. The heard words are never kept here. */
data class VoiceSearchState(
    val button: SpeakButtonState,
    val listening: Boolean = false,
    /** Fixed sentence, announced politely; null after a search (the result count is read instead). */
    @StringRes val messageRes: Int? = null,
) {
    val buttonVisible: Boolean get() = button == SpeakButtonState.SHOWN
}

/**
 * One press listens once; a second press cancels and the search box stays as it was. A usable
 * sentence goes to [onQuery] (the same path as typing). Main thread only. Never logs what was heard.
 */
class VoiceSearchController(
    private val dictation: VoiceDictation,
    button: SpeakButtonState,
    private val onQuery: (String) -> Unit,
) {
    private val mutableState = MutableStateFlow(VoiceSearchState(button))
    val state: StateFlow<VoiceSearchState> = mutableState.asStateFlow()

    /** Bumped on every start and stop, so a late result of an old session is dropped. */
    private var session = 0

    /** Settings or permission may change while the screen is open (checked on resume). */
    fun updateButton(button: SpeakButtonState) {
        if (button != SpeakButtonState.SHOWN) stop()
        mutableState.update { it.copy(button = button) }
    }

    fun onPress() {
        if (!mutableState.value.buttonVisible) return
        if (mutableState.value.listening) {
            stop()
            mutableState.update { it.copy(messageRes = R.string.ui_voice_listen_cancelled) }
            return
        }
        val mine = ++session
        val message = when (dictation.start { onResult(mine, it) }) {
            VoiceAvailability.READY -> {
                mutableState.update { it.copy(listening = true, messageRes = R.string.ui_voice_listening) }
                return
            }
            VoiceAvailability.MICROPHONE_PERMISSION_REQUIRED -> R.string.ui_voice_permission_hint
            else -> R.string.ui_voice_search_unavailable
        }
        mutableState.update { it.copy(listening = false, messageRes = message) }
    }

    /** Leaving the screen or the app: stop quietly. */
    fun stop() {
        session++
        if (!mutableState.value.listening) return
        dictation.cancel()
        mutableState.update { it.copy(listening = false, messageRes = null) }
    }

    private fun onResult(mine: Int, result: DictationResult) {
        if (mine != session || !mutableState.value.listening) return
        val query = (result as? DictationResult.Heard)?.let { VoiceSearchQuery.from(it.text) }
        if (query != null) {
            mutableState.update { it.copy(listening = false, messageRes = null) }
            onQuery(query)
            return
        }
        val message = when (result) {
            DictationResult.Silent -> R.string.ui_voice_search_silent
            DictationResult.Unavailable -> R.string.ui_voice_search_unavailable
            else -> R.string.ui_voice_search_not_understood
        }
        mutableState.update { it.copy(listening = false, messageRes = message) }
    }
}
