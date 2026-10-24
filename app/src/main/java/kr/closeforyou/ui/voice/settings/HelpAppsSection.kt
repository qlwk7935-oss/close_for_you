package kr.closeforyou.ui.voice.settings

import androidx.activity.compose.BackHandler
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
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.Checkbox
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.RadioButton
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
import androidx.compose.ui.unit.dp
import androidx.core.graphics.drawable.toBitmap
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kr.closeforyou.R
import kr.closeforyou.app.CorePreferences

/** Which page of "도와줄 앱" is shown. The two lists replace the whole screen. */
enum class HelpAppsPage { MODE, PICK, EXCLUDED }

/** Launcher apps, loaded once off the main thread. Null while loading. */
@Composable
fun rememberAppCatalog(): AppCatalog? {
    val context = LocalContext.current
    val catalog by produceState<AppCatalog?>(null) {
        value = withContext(Dispatchers.IO) {
            HelpAppsCatalog.build(LauncherAppSource(context.packageManager).launchableApps(), context.packageName)
        }
    }
    return catalog
}

/** Survives rotation: the mode and the two ticked sets. */
val HelpAppsDraftSaver: Saver<HelpAppsDraft, Any> = listSaver(
    save = { listOf(it.mode.name, ArrayList(it.chosen), ArrayList(it.addedBack)) },
    restore = {
        @Suppress("UNCHECKED_CAST")
        HelpAppsDraft(
            mode = HelpAppsMode.valueOf(it[0] as String),
            chosen = (it[1] as List<String>).toSet(),
            addedBack = (it[2] as List<String>).toSet(),
        )
    },
)

/**
 * "도와줄 앱" editor shared by the first-run step and settings. On [HelpAppsPage.MODE] it is one
 * scrolling column: [top], the two radio rows with their summary, then [bottom] (the caller's
 * buttons). The pick lists are [LazyColumn]s with the count and [완료] first, so TalkBack reaches
 * them without swiping through every app. Focus is never moved.
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
        val excludedPage = page == HelpAppsPage.EXCLUDED
        AppPickList(
            catalog = catalog,
            excludedOnly = excludedPage,
            checked = if (excludedPage) draft.addedBack else draft.chosen,
            onToggle = { pkg, on ->
                onDraftChange(if (excludedPage) draft.toggleAddedBack(pkg, on) else draft.toggleChosen(pkg, on))
            },
            countLine = if (excludedPage) {
                stringResource(R.string.ui_help_apps_excluded_count, HelpAppsPlan.addedBackCount(draft, catalog))
            } else {
                chosenCountLine(draft, catalog)
            },
            onDone = { onPageChange(HelpAppsPage.MODE) },
            modifier = modifier,
        )
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

/** The radio group, then one summary for the chosen way and its list button. */
@Composable
private fun HelpAppsModeSection(
    catalog: AppCatalog?,
    draft: HelpAppsDraft,
    onDraftChange: (HelpAppsDraft) -> Unit,
    onPageChange: (HelpAppsPage) -> Unit,
) {
    Text(stringResource(R.string.ui_help_apps_intro))
    Column(Modifier.selectableGroup()) {
        HelpAppsMode.entries.forEach { mode ->
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .heightIn(min = 48.dp)
                    .selectable(selected = draft.mode == mode, role = Role.RadioButton) {
                        onDraftChange(draft.copy(mode = mode))
                    },
                verticalAlignment = Alignment.CenterVertically,
            ) {
                RadioButton(selected = draft.mode == mode, onClick = null)
                Column(Modifier.padding(start = 8.dp).padding(vertical = 4.dp)) {
                    Text(stringResource(HelpAppsText.modeLabelRes(mode)))
                    Text(stringResource(HelpAppsText.modeHelpRes(mode)), style = MaterialTheme.typography.bodySmall)
                }
            }
        }
    }
    when {
        catalog == null -> Text(stringResource(R.string.ui_help_apps_loading))
        catalog.isEmpty() -> Text(stringResource(R.string.ui_help_apps_list_failed))
        draft.mode == HelpAppsMode.ALL -> {
            Text(stringResource(R.string.ui_help_apps_all_summary, catalog.helpable.size, catalog.excluded.size))
            val addedBack = HelpAppsPlan.addedBackCount(draft, catalog)
            if (addedBack > 0) Text(stringResource(R.string.ui_help_apps_all_added_back, addedBack))
            if (catalog.excluded.isNotEmpty()) {
                OutlinedButton(
                    onClick = { onPageChange(HelpAppsPage.EXCLUDED) },
                    modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp),
                ) { Text(stringResource(R.string.ui_help_apps_show_excluded)) }
            }
            Text(stringResource(R.string.ui_help_apps_new_apps), style = MaterialTheme.typography.bodySmall)
        }
        else -> {
            Text(chosenCountLine(draft, catalog))
            OutlinedButton(
                onClick = { onPageChange(HelpAppsPage.PICK) },
                modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp),
            ) { Text(stringResource(R.string.ui_help_apps_pick)) }
        }
    }
}

@Composable
private fun chosenCountLine(draft: HelpAppsDraft, catalog: AppCatalog): String {
    val count = HelpAppsPlan.target(draft.copy(mode = HelpAppsMode.CHOSEN), catalog).size
    return if (count == 0) stringResource(R.string.ui_help_apps_chosen_none)
    else stringResource(R.string.ui_help_apps_chosen_count, count)
}

/**
 * Title, count, [완료], search, then the apps. Without a search: "추천" first (pick list only),
 * then every other app 가나다순. With a search: one flat list and a polite result count.
 */
@Composable
private fun AppPickList(
    catalog: AppCatalog,
    excludedOnly: Boolean,
    checked: Set<String>,
    onToggle: (String, Boolean) -> Unit,
    countLine: String,
    onDone: () -> Unit,
    modifier: Modifier = Modifier,
) {
    var query by rememberSaveable { mutableStateOf("") }
    val base = if (excludedOnly) catalog.excluded else catalog.all
    val results = remember(base, query) { HelpAppsCatalog.search(base, query) }
    val recommended = if (excludedOnly || query.isNotBlank()) emptyList() else catalog.recommended
    val recommendedPackages = recommended.mapTo(HashSet()) { it.packageName }
    val rest = results.filter { it.packageName !in recommendedPackages }
    val row: LazyListScope.(List<InstalledApp>) -> Unit = { apps ->
        items(apps, key = { it.packageName }) { app ->
            AppCheckRow(app, app.packageName in checked, app.packageName in catalog.sensitive, onToggle)
        }
    }
    LazyColumn(
        modifier = modifier,
        verticalArrangement = Arrangement.spacedBy(4.dp),
        contentPadding = PaddingValues(16.dp),
    ) {
        item {
            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Text(
                    stringResource(if (excludedOnly) R.string.ui_help_apps_excluded_title else R.string.ui_help_apps_pick_title),
                    style = MaterialTheme.typography.titleLarge,
                    modifier = Modifier.semantics { heading() },
                )
                if (excludedOnly) Text(stringResource(R.string.ui_help_apps_excluded_help))
                Text(countLine)
                Button(onClick = onDone, modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp)) {
                    Text(stringResource(R.string.ui_help_apps_done))
                }
                OutlinedTextField(
                    value = query,
                    onValueChange = { query = it },
                    label = { Text(stringResource(R.string.ui_help_apps_search)) },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                )
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
        if (recommended.isNotEmpty()) {
            item { GroupHeading(R.string.ui_help_apps_recommended) }
            row(recommended)
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

/**
 * One TalkBack node: "유튜브, 체크 안 됨, 체크 상자" (payment-like apps add "결제 앱일 수 있어요").
 * Ticking such an app shows the payment note below as a polite live region, read once.
 */
@Composable
private fun AppCheckRow(app: InstalledApp, checked: Boolean, sensitive: Boolean, onToggle: (String, Boolean) -> Unit) {
    Column {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .heightIn(min = 56.dp)
                .toggleable(value = checked, role = Role.Checkbox) { onToggle(app.packageName, it) },
            verticalAlignment = Alignment.CenterVertically,
        ) {
            AppIcon(app.packageName)
            Column(Modifier.weight(1f).padding(horizontal = 12.dp, vertical = 4.dp)) {
                Text(app.label)
                if (sensitive) Text(stringResource(R.string.ui_help_apps_payment_hint), style = MaterialTheme.typography.bodySmall)
            }
            Checkbox(checked = checked, onCheckedChange = null)
        }
        if (checked && sensitive) {
            Text(
                stringResource(R.string.ui_help_apps_payment_note),
                style = MaterialTheme.typography.bodySmall,
                modifier = Modifier.padding(start = 52.dp, bottom = 4.dp).semantics { liveRegion = LiveRegionMode.Polite },
            )
        }
    }
}

/** Decorative (the name is read instead). Loaded off the main thread; blank while loading. */
@Composable
private fun AppIcon(packageName: String) {
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

/** Settings' "도와줄 앱" block: how many apps A helps, what waits for consent, the last result. */
@Composable
fun HelpAppsStatus(
    mode: HelpAppsMode?,
    allowedCount: Int,
    pendingCount: Int,
    outcome: HelpAppsOutcome?,
    onChange: () -> Unit,
) {
    val resources = LocalContext.current.resources
    Text(
        if (allowedCount == 0) stringResource(R.string.ui_help_apps_status_none)
        else stringResource(R.string.ui_help_apps_status_count, allowedCount),
    )
    HelpAppsText.statusModeRes(mode)?.let { Text(stringResource(it)) }
    if (pendingCount > 0) Text(stringResource(R.string.ui_help_apps_status_pending, pendingCount))
    if (outcome != null) {
        Text(
            HelpAppsText.outcomeLines(outcome).joinToString(" ") { (res, count) -> resources.getString(res, count) },
            modifier = Modifier.semantics { liveRegion = LiveRegionMode.Polite },
        )
    }
    OutlinedButton(onClick = onChange, modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp)) {
        Text(stringResource(R.string.ui_help_apps_change))
    }
}

/**
 * Full-screen "도와줄 앱" from settings (and the debug preview): starts from what A allows now,
 * [저장] applies through A ([HelpAppsSync]) and closes, [취소] or back closes without changes.
 */
@Composable
fun HelpAppsEditScreen(onClose: () -> Unit, modifier: Modifier = Modifier) {
    val context = LocalContext.current
    val preferences = remember { UiVoicePreferences.get(context) }
    val core = remember { CorePreferences.get(context) }
    val catalog = rememberAppCatalog()
    var draft by rememberSaveable(stateSaver = HelpAppsDraftSaver) {
        val settings = preferences.current()
        mutableStateOf(
            HelpAppsDraft.from(settings.helpAppsMode, core.currentSettings().allowedPackages, settings.pendingHelpPackages),
        )
    }
    var page by rememberSaveable { mutableStateOf(HelpAppsPage.MODE) }
    if (page == HelpAppsPage.MODE) BackHandler(onBack = onClose)
    HelpAppsEditor(
        catalog = catalog,
        draft = draft,
        onDraftChange = { draft = it },
        page = page,
        onPageChange = { page = it },
        modifier = modifier,
        top = {
            Text(
                stringResource(R.string.ui_help_apps_section),
                style = MaterialTheme.typography.titleLarge,
                modifier = Modifier.semantics { heading() },
            )
        },
        bottom = {
            Button(
                onClick = {
                    if (catalog != null) HelpAppsSync.save(draft, catalog, preferences, CoreAllowedApps(core))
                    onClose()
                },
                enabled = catalog != null,
                modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp),
            ) { Text(stringResource(R.string.ui_help_apps_save)) }
            OutlinedButton(onClick = onClose, modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp)) {
                Text(stringResource(R.string.ui_help_apps_cancel))
            }
        },
    )
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
