package kr.closeforyou.ui.voice.settings

import android.content.Context
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kr.closeforyou.core.contract.PopupRuleRepository
import kr.closeforyou.data.RuleStorage

/** What [UiVoiceSettingsRoute] needs from the remembered-rule storage, as one value. */
class RuleSettingsStorage(
    val repository: PopupRuleRepository,
    /** [전체 삭제]. */
    val deleteAll: suspend () -> Unit,
    /** Runs once each time the settings screen is opened. */
    val onOpenCleanUp: suspend () -> Unit,
)

/**
 * C: D's real Room storage (#15, #25) bundled for the settings screen. A calls this from
 * `MainActivity`; the debug preview keeps the in-memory mock and its fake rules never reach Room.
 *
 * - list, revoke: `RuleStorage.repository(context)`
 * - [전체 삭제]: `RuleStorage.eraseAll(context)` (rules and the fingerprint key)
 * - on open: `RuleStorage.cleanUp(context)` (expired and unreadable rows). Only counts come back and
 *   C drops them; a failure is swallowed by the Route without logging and the screen still opens.
 */
object RoomBackedRuleSettings {
    fun create(context: Context): RuleSettingsStorage {
        val app = context.applicationContext
        return bind(
            repository = RuleStorage.repository(app),
            eraseAll = { RuleStorage.eraseAll(app) },
            cleanUp = { RuleStorage.cleanUp(app) },
        )
    }

    /** Keystore work in `eraseAll` is not a Room call, so both run off the main thread. */
    internal fun bind(
        repository: PopupRuleRepository,
        eraseAll: suspend () -> Unit,
        cleanUp: suspend () -> Any?,
        io: CoroutineDispatcher = Dispatchers.IO,
    ) = RuleSettingsStorage(
        repository = repository,
        deleteAll = { withContext(io) { eraseAll() } },
        onOpenCleanUp = { withContext(io) { cleanUp() } },
    )
}

/** Settings screen on [storage]; e.g. `UiVoiceSettingsRoute(RoomBackedRuleSettings.create(this))`. */
@Composable
fun UiVoiceSettingsRoute(storage: RuleSettingsStorage, modifier: Modifier = Modifier, onOpenCoreSettings: (() -> Unit)? = null) {
    UiVoiceSettingsRoute(
        repository = storage.repository,
        modifier = modifier,
        deleteAll = storage.deleteAll,
        onOpenCleanUp = storage.onOpenCleanUp,
        onOpenCoreSettings = onOpenCoreSettings,
    )
}

/** Same as above, creating the Room bundle once for this screen. */
@Composable
fun RoomBackedUiVoiceSettingsRoute(modifier: Modifier = Modifier, onOpenCoreSettings: (() -> Unit)? = null) {
    val context = LocalContext.current
    val storage = remember { RoomBackedRuleSettings.create(context) }
    UiVoiceSettingsRoute(storage, modifier, onOpenCoreSettings)
}
