package kr.closeforyou.ui.voice.settings

import androidx.compose.ui.text.font.FontWeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.background
import kr.closeforyou.ui.voice.theme.InkButton
import kr.closeforyou.ui.voice.theme.InkCard
import kr.closeforyou.ui.voice.theme.InkGrade
import kr.closeforyou.ui.voice.theme.PopkokHeader
import kr.closeforyou.ui.voice.theme.inkRow
import kr.closeforyou.ui.voice.theme.uiVoicePalette
import android.Manifest
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.Checkbox
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.Saver
import androidx.compose.runtime.saveable.listSaver
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.unit.dp
import androidx.core.graphics.drawable.toBitmap
import androidx.lifecycle.compose.LifecycleResumeEffect
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kr.closeforyou.R
import kr.closeforyou.app.CorePreferences
import kr.closeforyou.ui.voice.command.AndroidMicrophoneAccess
import kr.closeforyou.ui.voice.command.OnDeviceDictation
import kr.closeforyou.ui.voice.command.SpeakButtonPolicy
import kr.closeforyou.ui.voice.command.SpeakButtonState
import kr.closeforyou.ui.voice.command.VoiceButtonFeature

/** Which page of "도와줄 앱" is shown. The lists replace the whole screen. */
enum class HelpAppsPage {
    MODE,
    /** [HelpAppsMode.CHOSEN]: tick the apps to help. */
    PICK,
    /** [HelpAppsMode.ALL]: untick apps to take them out. */
    EXCLUDE_MORE,
    /** The always-excluded apps, shown only (no checkboxes). */
    ALWAYS_EXCLUDED,
}

/** Launcher apps, loaded once off the main thread. Null while loading. */
@Composable
fun rememberAppCatalog(): AppCatalog? {
    val context = LocalContext.current
    val catalog by produceState<AppCatalog?>(null) {
        value = withContext(Dispatchers.IO) {
            HelpAppsCatalog.build(
                LauncherAppSource(context.packageManager).launchableApps(),
                context.packageName,
                unknownLabel = context.getString(R.string.ui_app_unknown),
            )
        }
    }
    return catalog
}

/** Survives rotation: the way (or none yet) and the two sets. */
val HelpAppsDraftSaver: Saver<HelpAppsDraft, Any> = listSaver(
    save = { listOf(it.mode?.name.orEmpty(), ArrayList(it.chosen), ArrayList(it.userExcluded)) },
    restore = {
        @Suppress("UNCHECKED_CAST")
        HelpAppsDraft(
            mode = HelpAppsMode.entries.firstOrNull { mode -> mode.name == it[0] },
            chosen = (it[1] as List<String>).toSet(),
            userExcluded = (it[2] as List<String>).toSet(),
        )
    },
)

/**
 * "도와줄 앱" editor shared by the first-run step and settings. On [HelpAppsPage.MODE] it is one
 * scrolling column: [top], the scope explanation, the two radio rows (none selected until the
 * person picks one), the summary, then [bottom] (the caller's buttons). The lists are
 * [LazyColumn]s with the count and [완료] first, so TalkBack reaches them without swiping through
 * every app. Focus is never moved.
 */
@Composable
fun HelpAppsEditor(
    catalog: AppCatalog?,
    draft: HelpAppsDraft,
    onDraftChange: (HelpAppsDraft) -> Unit,
    page: HelpAppsPage,
    onPageChange: (HelpAppsPage) -> Unit,
    modifier: Modifier = Modifier,
    top: @Composable ColumnScope.() -> Unit = {},
    bottom: @Composable ColumnScope.() -> Unit = {},
) {
    if (page != HelpAppsPage.MODE && catalog != null) {
        BackHandler { onPageChange(HelpAppsPage.MODE) }
        val onDone = { onPageChange(HelpAppsPage.MODE) }
        when (page) {
            HelpAppsPage.PICK -> AppPickList(
                title = stringResource(R.string.ui_help_apps_pick_title),
                help = null,
                apps = catalog.helpable,
                recommended = catalog.recommended,
                checked = { it in draft.chosen },
                onToggle = { pkg, on -> onDraftChange(draft.toggleChosen(pkg, on)) },
                countLine = chosenCountLine(draft, catalog),
                onDone = onDone,
                modifier = modifier,
            )
            HelpAppsPage.EXCLUDE_MORE -> AppPickList(
                title = stringResource(R.string.ui_help_apps_exclude_more_title),
                help = stringResource(R.string.ui_help_apps_exclude_more_help),
                apps = catalog.helpable,
                recommended = emptyList(),
                checked = { it !in draft.userExcluded },
                onToggle = { pkg, on -> onDraftChange(draft.toggleHelpedInAll(pkg, on)) },
                countLine = allSummaryLine(draft, catalog),
                onDone = onDone,
                modifier = modifier,
            )
            else -> AppPickList(
                title = stringResource(R.string.ui_help_apps_excluded_title),
                help = stringResource(R.string.ui_help_apps_excluded_help),
                apps = catalog.excluded,
                recommended = emptyList(),
                checked = null,
                onToggle = { _, _ -> },
                countLine = stringResource(R.string.ui_help_apps_excluded_count, catalog.excluded.size),
                onDone = onDone,
                modifier = modifier,
            )
        }
        return
    }
    Column(
        modifier = modifier.verticalScroll(rememberScrollState()).padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        top()
        HelpAppsModeSection(catalog, draft, onDraftChange, onPageChange)
        bottom()
    }
}

/** Scope first, then the radio group, then one summary for the chosen way and its list buttons. */
@Composable
private fun HelpAppsModeSection(
    catalog: AppCatalog?,
    draft: HelpAppsDraft,
    onDraftChange: (HelpAppsDraft) -> Unit,
    onPageChange: (HelpAppsPage) -> Unit,
) {
    BodyText(stringResource(R.string.ui_help_apps_intro))
    BodyText(stringResource(R.string.ui_help_apps_scope))
    HelpText(stringResource(R.string.ui_help_apps_separate))
    Column(Modifier.selectableGroup()) {
        HelpAppsMode.entries.forEach { mode ->
            ChoiceCard(
                title = stringResource(HelpAppsText.modeLabelRes(mode)),
                body = stringResource(HelpAppsText.modeHelpRes(mode)),
                selected = draft.mode == mode,
                onClick = { onDraftChange(draft.copy(mode = mode)) },
            )
        }
    }
    when {
        catalog == null -> Text(stringResource(R.string.ui_help_apps_loading))
        catalog.isEmpty() -> Text(stringResource(R.string.ui_help_apps_list_failed))
        draft.mode == HelpAppsMode.ALL -> {
            Text(allSummaryLine(draft, catalog))
            InkButton(stringResource(R.string.ui_help_apps_exclude_more), { onPageChange(HelpAppsPage.EXCLUDE_MORE) }, InkGrade.LINE, Modifier.fillMaxWidth())
            if (catalog.excluded.isNotEmpty()) {
                InkButton(stringResource(R.string.ui_help_apps_show_excluded), { onPageChange(HelpAppsPage.ALWAYS_EXCLUDED) }, InkGrade.LINE, Modifier.fillMaxWidth())
            }
            Text(stringResource(R.string.ui_help_apps_new_apps), style = MaterialTheme.typography.bodySmall)
        }
        draft.mode == HelpAppsMode.CHOSEN -> {
            Text(chosenCountLine(draft, catalog))
            InkButton(stringResource(R.string.ui_help_apps_pick), { onPageChange(HelpAppsPage.PICK) }, InkGrade.SUB, Modifier.fillMaxWidth())
        }
        else -> Unit
    }
}

/**
 * The caller's [시작하기]/[저장]: disabled until a way is chosen. While disabled, the reason is
 * shown above it and also given as the button's state, so TalkBack reads the label, the reason,
 * then "사용 중지됨".
 */
@Composable
fun HelpAppsSaveButton(
    label: String,
    enabled: Boolean,
    onClick: () -> Unit,
    /** Shown above and read as the state while disabled. */
    reason: String = stringResource(R.string.ui_help_apps_choose_first),
) {
    if (!enabled) Text(reason)
    InkButton(
        label,
        onClick,
        InkGrade.MAIN,
        Modifier
            .fillMaxWidth()
            .semantics { if (!enabled) stateDescription = reason },
        enabled = enabled,
    )
}

@Composable
private fun allSummaryLine(draft: HelpAppsDraft, catalog: AppCatalog): String {
    val helped = HelpAppsPlan.target(draft.copy(mode = HelpAppsMode.ALL), catalog).size
    val summary = stringResource(R.string.ui_help_apps_all_summary, helped, catalog.excluded.size)
    val mine = HelpAppsPlan.userExcludedCount(draft, catalog)
    return if (mine == 0) summary else summary + " " + stringResource(R.string.ui_help_apps_user_excluded, mine)
}

@Composable
private fun chosenCountLine(draft: HelpAppsDraft, catalog: AppCatalog): String {
    val count = HelpAppsPlan.target(draft.copy(mode = HelpAppsMode.CHOSEN), catalog).size
    return if (count == 0) stringResource(R.string.ui_help_apps_chosen_none)
    else stringResource(R.string.ui_help_apps_chosen_count, count)
}

/**
 * Title, (help), count, [완료], search, then the apps. Without a search: [recommended] first, then
 * every other app 가나다순. With a search: one flat list and a polite result count.
 * [checked] null = shown only, no checkboxes.
 */
@Composable
private fun AppPickList(
    title: String,
    help: String?,
    apps: List<InstalledApp>,
    recommended: List<InstalledApp>,
    checked: ((String) -> Boolean)?,
    onToggle: (String, Boolean) -> Unit,
    countLine: String,
    onDone: () -> Unit,
    modifier: Modifier = Modifier,
) {
    var query by rememberSaveable { mutableStateOf("") }
    val results = remember(apps, query) { HelpAppsCatalog.search(apps, query) }
    // [말로 찾기]: same rules as the prompt's [말하기]; the microphone is asked only here in the Activity.
    val context = LocalContext.current
    val preferences = remember { UiVoicePreferences.get(context) }
    val microphone = remember { AndroidMicrophoneAccess(context) }
    fun buttonState() =
        SpeakButtonPolicy.state(VoiceButtonFeature.enabled, preferences.current().voiceCommandsEnabled, microphone)
    val voiceSearch = remember { VoiceSearchController(OnDeviceDictation(context), buttonState()) { query = it } }
    val voice by voiceSearch.state.collectAsState()
    var microphoneDenied by rememberSaveable { mutableStateOf(false) }
    val permissionLauncher = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        microphoneDenied = !granted
        voiceSearch.updateButton(buttonState())
    }
    // Back from system settings: re-check. Leaving the screen or the app: stop listening.
    LifecycleResumeEffect(voiceSearch) {
        voiceSearch.updateButton(buttonState())
        onPauseOrDispose { voiceSearch.stop() }
    }
    val shownRecommended = if (query.isNotBlank()) emptyList() else recommended
    val recommendedPackages = shownRecommended.mapTo(HashSet()) { it.packageName }
    val rest = results.filter { it.packageName !in recommendedPackages }
    val row: LazyListScope.(List<InstalledApp>) -> Unit = { list ->
        items(list, key = { it.packageName }) { app ->
            if (checked == null) AppInfoRow(app) else AppCheckRow(app, checked(app.packageName), onToggle)
        }
    }
    LazyColumn(
        modifier = modifier,
        verticalArrangement = Arrangement.spacedBy(4.dp),
        contentPadding = PaddingValues(16.dp),
    ) {
        item {
            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                PopkokHeader { Text(title, style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold, modifier = Modifier.semantics { heading() }) }
                if (help != null) Text(help)
                Text(countLine)
                InkButton(stringResource(R.string.ui_help_apps_done), onDone, InkGrade.MAIN, Modifier.fillMaxWidth())
                Row(
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    OutlinedTextField(
                        value = query,
                        onValueChange = { query = it },
                        label = { Text(stringResource(R.string.ui_help_apps_search)) },
                        singleLine = true,
                        modifier = Modifier.weight(1f),
                    )
                    if (voice.buttonVisible) VoiceSearchButton(voice.listening, voiceSearch::onPress)
                }
                VoiceSearchLines(voice, microphoneDenied) { permissionLauncher.launch(Manifest.permission.RECORD_AUDIO) }
                if (query.isNotBlank()) {
                    // Polite: TalkBack reads the count once it settles, focus stays in the field.
                    Text(
                        if (results.isEmpty()) stringResource(R.string.ui_help_apps_search_none)
                        else stringResource(R.string.ui_help_apps_search_results, results.size),
                        modifier = Modifier.semantics { liveRegion = LiveRegionMode.Polite },
                    )
                }
            }
        }
        if (shownRecommended.isNotEmpty()) {
            item { GroupHeading(R.string.ui_help_apps_recommended) }
            row(shownRecommended)
            item { GroupHeading(R.string.ui_help_apps_all_list) }
        }
        row(rest)
    }
}

@Composable
private fun GroupHeading(textRes: Int) {
    Column {
        HorizontalDivider()
        Spacer(Modifier.size(8.dp))
        Text(stringResource(textRes), style = MaterialTheme.typography.titleMedium, modifier = Modifier.semantics { heading() })
    }
}

/** "말로 찾기, 버튼"; while listening "듣기 멈추기, 듣는 중, 버튼" (a second press cancels). */
@Composable
private fun VoiceSearchButton(listening: Boolean, onPress: () -> Unit) {
    val listeningState = stringResource(R.string.ui_voice_listening_state)
    InkButton(
        stringResource(if (listening) R.string.ui_voice_stop_listening else R.string.ui_help_apps_voice_search),
        onPress,
        InkGrade.LINE,
        Modifier.semantics { if (listening) stateDescription = listeningState },
        minHeight = 56.dp,
    )
}

/** Without the button: one keyboard hint (and [마이크 허용] when only the permission is missing). Then the status line. */
@Composable
private fun VoiceSearchLines(voice: VoiceSearchState, microphoneDenied: Boolean, onAllowMicrophone: () -> Unit) {
    if (!voice.buttonVisible) {
        Text(stringResource(R.string.ui_help_apps_voice_search_keyboard), style = MaterialTheme.typography.bodySmall)
        if (voice.button == SpeakButtonState.PERMISSION_NEEDED) {
            if (microphoneDenied) Text(stringResource(R.string.ui_settings_mic_denied), style = MaterialTheme.typography.bodySmall)
            InkButton(stringResource(R.string.ui_settings_mic_allow), onAllowMicrophone, InkGrade.SUB, Modifier.fillMaxWidth())
        }
    }
    val message = voice.messageRes
    if (message != null) {
        // Polite and only when it changes: "듣고 있어요." is read once, focus stays where it is.
        Text(stringResource(message), modifier = Modifier.semantics { liveRegion = LiveRegionMode.Polite })
    }
}

/** One TalkBack node: "유튜브, 체크 안 됨, 체크 상자". */
@Composable
private fun AppCheckRow(app: InstalledApp, checked: Boolean, onToggle: (String, Boolean) -> Unit) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .inkRow(uiVoicePalette())
            .toggleable(value = checked, role = Role.Checkbox) { onToggle(app.packageName, it) }
            .padding(horizontal = 10.dp, vertical = 4.dp)
            .heightIn(min = 56.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        AppIcon(app.packageName)
        Text(app.label, Modifier.weight(1f).padding(horizontal = 12.dp, vertical = 4.dp))
        Checkbox(checked = checked, onCheckedChange = null)
    }
}

/** Always-excluded app: name only, one node, nothing to press. */
@Composable
private fun AppInfoRow(app: InstalledApp) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .inkRow(uiVoicePalette())
            .semantics(mergeDescendants = true) {}
            .padding(horizontal = 10.dp, vertical = 4.dp)
            .heightIn(min = 48.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        AppIcon(app.packageName)
        Text(app.label, Modifier.padding(horizontal = 12.dp, vertical = 4.dp))
    }
}

/** Decorative (the name is read instead). Loaded off the main thread; blank while loading or hidden. */
@Composable
internal fun AppIcon(packageName: String) {
    val context = LocalContext.current
    val icon by produceState<ImageBitmap?>(null, packageName) {
        value = withContext(Dispatchers.IO) {
            try {
                context.packageManager.getApplicationIcon(packageName).toBitmap(96, 96).asImageBitmap()
            } catch (_: Exception) {
                null
            }
        }
    }
    val current = icon
    if (current != null) Image(current, contentDescription = null, modifier = Modifier.size(40.dp))
    else Spacer(Modifier.size(40.dp))
}

/** Settings' "도와줄 앱" block: one line ([HelpAppsStatusPlan]), "준비 중" when waiting, then the buttons. */
@Composable
fun HelpAppsStatus(line: HelpAppsStatusLine, onChange: () -> Unit, onExclude: () -> Unit) {
    BodyText(
        when {
            line.mode == HelpAppsMode.ALL && line.excludedCount != null ->
                stringResource(R.string.ui_help_apps_status_all_excluded, line.excludedCount)
            line.mode == HelpAppsMode.ALL -> stringResource(R.string.ui_help_apps_status_all)
            line.mode == HelpAppsMode.CHOSEN -> stringResource(R.string.ui_help_apps_status_chosen, line.helpedCount)
            else -> stringResource(R.string.ui_help_apps_status_none)
        },
    )
    if (line.waiting) HelpText(stringResource(R.string.ui_help_apps_status_waiting))
    if (line.mode == HelpAppsMode.ALL) {
        InkButton(stringResource(R.string.ui_help_apps_exclude_button), onExclude, InkGrade.LINE, Modifier.fillMaxWidth(), minHeight = 56.dp, fontSize = UiType.body)
    }
    InkButton(stringResource(R.string.ui_help_apps_change), onChange, InkGrade.SUB, Modifier.fillMaxWidth(), minHeight = 56.dp, fontSize = UiType.body)
}

/**
 * Full-screen "도와줄 앱" from settings (and the debug preview): starts from what A allows now,
 * [저장] applies through A ([HelpAppsSync]) and closes, [취소] or back closes without changes.
 */
@Composable
fun HelpAppsEditScreen(onClose: () -> Unit, modifier: Modifier = Modifier, initialPage: HelpAppsPage = HelpAppsPage.MODE) {
    val context = LocalContext.current
    val preferences = remember { UiVoicePreferences.get(context) }
    val core = remember { CorePreferences.get(context) }
    val catalog = rememberAppCatalog()
    var draft by rememberSaveable(stateSaver = HelpAppsDraftSaver) {
        val settings = preferences.current()
        mutableStateOf(
            HelpAppsDraft.from(
                settings.helpAppsMode, settings.helpAppsExcluded,
                core.currentSettings().allowedPackages, settings.pendingHelpPackages,
            ),
        )
    }
    var page by rememberSaveable { mutableStateOf(initialPage) }
    if (page == HelpAppsPage.MODE) BackHandler(onBack = onClose)
    // F63: the same white card on the screen color as the settings page.
    Box(modifier.fillMaxSize().background(uiVoicePalette().screen)) {
    InkCard(Modifier.fillMaxSize().padding(12.dp), padding = PaddingValues(0.dp)) {
    HelpAppsEditor(
        catalog = catalog,
        draft = draft,
        onDraftChange = { draft = it },
        page = page,
        onPageChange = { page = it },
        modifier = Modifier.fillMaxSize(),
        top = {
            PopkokHeader {
                Text(
                    stringResource(R.string.ui_help_apps_section),
                    style = MaterialTheme.typography.titleLarge,
                    fontWeight = FontWeight.Bold,
                    modifier = Modifier.semantics { heading() },
                )
            }
        },
        bottom = {
            HelpAppsSaveButton(
                label = stringResource(R.string.ui_help_apps_save),
                enabled = HelpAppsPlan.canSave(draft, catalog),
                onClick = {
                    if (catalog != null) HelpAppsSync.save(draft, catalog, preferences, CoreAllowedApps(core))
                    onClose()
                },
            )
            InkButton(stringResource(R.string.ui_help_apps_cancel), onClose, InkGrade.LINE, Modifier.fillMaxWidth())
        },
    )
    }
    }
}

/** After A's collection consent (or on opening a C screen), adds what was saved before it. */
@Composable
fun PendingHelpAppsEffect() {
    val context = LocalContext.current
    val preferences = remember { UiVoicePreferences.get(context) }
    val core = remember { CorePreferences.get(context) }
    val consent by core.state.collectAsState()
    val settings by preferences.state.collectAsState()
    val pending = settings.pendingHelpPackages
    LaunchedEffect(consent.consentGranted, pending) {
        if (consent.consentGranted && pending.isNotEmpty()) HelpAppsSync.retryPending(preferences, CoreAllowedApps(core))
    }
}

/** String choices, separate so JVM tests can check them without Compose. */
internal object HelpAppsText {
    fun modeLabelRes(mode: HelpAppsMode) = when (mode) {
        HelpAppsMode.ALL -> R.string.ui_help_apps_all
        HelpAppsMode.CHOSEN -> R.string.ui_help_apps_chosen
    }

    fun modeHelpRes(mode: HelpAppsMode) = when (mode) {
        HelpAppsMode.ALL -> R.string.ui_help_apps_all_help
        HelpAppsMode.CHOSEN -> R.string.ui_help_apps_chosen_help
    }

    fun statusModeRes(mode: HelpAppsMode?) = when (mode) {
        HelpAppsMode.ALL -> R.string.ui_help_apps_status_mode_all
        HelpAppsMode.CHOSEN -> R.string.ui_help_apps_status_mode_chosen
        null -> null
    }

    /** One (string, count) per non-zero count, in the order they are read; "바뀐 앱이 없어요" alone otherwise. */
    fun outcomeLines(outcome: HelpAppsOutcome): List<Pair<Int, Int>> {
        if (outcome.nothingChanged) return listOf(R.string.ui_help_apps_result_nothing to 0)
        return listOfNotNull(
            (R.string.ui_help_apps_result_added to outcome.added).takeIf { outcome.added > 0 },
            (R.string.ui_help_apps_result_removed to outcome.removed).takeIf { outcome.removed > 0 },
            (R.string.ui_help_apps_result_failed to outcome.failed).takeIf { outcome.failed > 0 },
            (R.string.ui_help_apps_result_waiting to outcome.waiting).takeIf { outcome.waiting > 0 },
        )
    }
}
