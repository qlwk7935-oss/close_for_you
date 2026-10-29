package kr.closeforyou.ui.voice.settings

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.paneTitle
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.LifecycleResumeEffect
import kr.closeforyou.R
import kr.closeforyou.app.CorePreferences
import kr.closeforyou.ui.voice.UiVoiceTheme
import kr.closeforyou.ui.voice.overlay.TalkBackTakeoverFeature

/** Which screen the app's first screen shows. Pure, so the gate is tested on the JVM. */
object OnboardingGatePolicy {
    fun showOnboarding(settings: UiVoiceSettings): Boolean = !settings.onboardingDone

    /**
     * Preset shown as chosen when the preset step opens (A #34 10/25). First run: none, even though
     * [UiVoiceSettings.handlingPreset] already holds the internal safe default
     * ([HandlingPreset.DEFAULT]); the person picks one before [다음]. After setup: the saved one.
     */
    fun initialPreset(settings: UiVoiceSettings): HandlingPreset? =
        if (settings.onboardingDone) settings.handlingPreset else null

    /** [다음] on the preset step waits for a preset. */
    fun canContinue(chosen: HandlingPreset?): Boolean = chosen != null

    /** The draft the first run opens with: nothing chosen for the person, takeover as saved. */
    fun initialDraft(settings: UiVoiceSettings): OnboardingDraft {
        val preset = initialPreset(settings)
        return OnboardingDraft(
            preset = preset,
            autoCloseConsent = settings.autoCloseConsent && preset != null,
            talkBackTakeover = settings.talkBackTakeover,
        )
    }
}

/**
 * For A's first screen: the five-step first run until [사용 시작] is pressed once, then [content].
 * e.g. `UiVoiceOnboardingGate { CoreSetupScreen(...) }`; [UiVoiceAppRoot] wraps this with C's
 * settings as the home screen. Nothing is shown over another app.
 * After setup, "도와줄 앱" saved before A's collection consent is applied as soon as it is given.
 */
@Composable
fun UiVoiceOnboardingGate(
    modifier: Modifier = Modifier,
    status: CoreSetupStatus? = null,
    content: @Composable () -> Unit,
) {
    val context = LocalContext.current
    val preferences = remember { UiVoicePreferences.get(context) }
    val core = remember { CorePreferences.get(context) }
    val setupStatus = status ?: remember { AndroidCoreSetupStatus(context) }
    val settings by preferences.state.collectAsState()
    if (OnboardingGatePolicy.showOnboarding(settings)) {
        UiVoiceTheme {
            // Own Scaffold: theme background and system-bar insets (targetSdk 35 draws edge to edge).
            Scaffold(modifier) { insets ->
                UiVoiceOnboardingScreen(
                    initialDraft = OnboardingGatePolicy.initialDraft(settings),
                    status = setupStatus,
                    onFinish = { result ->
                        result.helpApps?.let { HelpAppsSync.save(it.draft, it.catalog, preferences, CoreAllowedApps(core)) }
                        preferences.completeOnboarding(result.preset, result.autoCloseConsent, result.talkBackTakeover)
                    },
                    modifier = Modifier.padding(insets),
                )
            }
        }
    } else {
        PendingHelpAppsEffect()
        content()
    }
}

/** What the "도와줄 앱" step decided; null in [OnboardingResult] means it was not decided. */
data class OnboardingHelpApps(val draft: HelpAppsDraft, val catalog: AppCatalog)

/**
 * First-run setup in five steps ([OnboardingStep]), demo-musical scene 2 as the design:
 * 1. 환영: app mark, "팝업처리도우미", one line, what it does → [시작하기]
 * 2. 접근성 서비스: what it reads and never does → [서비스 켜기] opens system settings; on return
 *    the state is read again and shows "켜짐 ✓" (polite live region, focus is not moved).
 *    [나중에 켜기] continues while it is off.
 * 3. 팝업 처리 방식: three presets (none chosen on first run), "자동 닫기" switch with its dialog
 *    ([HandlingPresetWithConsent]), and "선택 창이 뜨면 안내를 맡기기" ([TalkBackTakeoverFeature]).
 * 4. 도와줄 앱: [HelpAppsEditor] (모든 앱 / 고른 앱만, real app names and icons) or [나중에 고르기].
 * 5. 완료: a summary of every choice → [사용 시작] saves everything at once ([onFinish]).
 * Nothing is saved before [사용 시작]; rotation keeps the choices. Each step is a pane whose title
 * TalkBack announces; focus is never moved, the person explores from where they are.
 */
@Composable
fun UiVoiceOnboardingScreen(
    initialDraft: OnboardingDraft,
    status: CoreSetupStatus,
    onFinish: (OnboardingResult) -> Unit,
    modifier: Modifier = Modifier,
    /** Null = load the launcher apps; previews pass a fixed catalog. */
    catalogOverride: AppCatalog? = null,
    /** Opens system accessibility settings; false = could not open. */
    openServiceSettings: () -> Boolean = defaultOpenServiceSettings(),
) {
    var step by rememberSaveable { mutableStateOf(initialDraft.step) }
    var preset by rememberSaveable { mutableStateOf(initialDraft.preset) }
    var autoCloseConsent by rememberSaveable { mutableStateOf(initialDraft.autoCloseConsent) }
    var takeover by rememberSaveable { mutableStateOf(initialDraft.talkBackTakeover) }
    var helpApps by rememberSaveable(stateSaver = HelpAppsDraftSaver) { mutableStateOf(initialDraft.helpApps) }
    var skipped by rememberSaveable { mutableStateOf(initialDraft.helpAppsSkipped) }
    var helpPage by rememberSaveable { mutableStateOf(HelpAppsPage.MODE) }
    val draft = OnboardingDraft(step, preset, autoCloseConsent, takeover, helpApps, skipped)
    fun apply(next: OnboardingDraft) {
        step = next.step
        preset = next.preset
        autoCloseConsent = next.autoCloseConsent
        takeover = next.talkBackTakeover
        helpApps = next.helpApps
        skipped = next.helpAppsSkipped
    }
    // Loaded once the person reaches "도와줄 앱" (or the summary), off the main thread.
    val catalog = catalogOverride ?: if (step >= OnboardingStep.HELP_APPS) rememberAppCatalog() else null

    var serviceOn by remember { mutableStateOf(status.serviceEnabled()) }
    var consentGranted by remember { mutableStateOf(status.collectionConsentGranted()) }
    // Back from system settings: read again.
    LifecycleResumeEffect(status) {
        serviceOn = status.serviceEnabled()
        consentGranted = status.collectionConsentGranted()
        onPauseOrDispose {}
    }

    if (step != OnboardingStep.WELCOME && !(step == OnboardingStep.HELP_APPS && helpPage != HelpAppsPage.MODE)) {
        BackHandler { OnboardingFlow.back(draft)?.let(::apply) }
    }
    val back = { OnboardingFlow.back(draft)?.let(::apply); Unit }
    val next = { apply(OnboardingFlow.next(draft, catalog)) }
    val blockReason = OnboardingFlow.block(draft, catalog)?.let { stringResource(it.reasonRes()) }
    val paneName = stringResource(step.titleRes())

    if (step == OnboardingStep.HELP_APPS) {
        // The editor scrolls by itself and its lists replace the whole screen.
        HelpAppsEditor(
            catalog = catalog,
            draft = helpApps,
            onDraftChange = { helpApps = it },
            page = helpPage,
            onPageChange = { helpPage = it },
            modifier = modifier.semantics { paneTitle = paneName },
            top = {
                StepHeader(step)
                StepTitle(paneName)
            },
            bottom = {
                Spacer(Modifier.height(4.dp))
                BrandButton(stringResource(R.string.ui_onboarding_next), next, enabled = blockReason == null, disabledReason = blockReason)
                SecondaryButton(stringResource(R.string.ui_onboarding_back), back)
                SecondaryButton(stringResource(R.string.ui_setup_help_apps_later)) { apply(OnboardingFlow.skipHelpApps(draft)) }
                HelpText(stringResource(R.string.ui_onboarding_skip_help))
            },
        )
        return
    }

    Column(modifier.fillMaxSize().semantics { paneTitle = paneName }) {
        Column(
            Modifier
                .weight(1f)
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 24.dp, vertical = 16.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            if (step != OnboardingStep.WELCOME) StepHeader(step)
            when (step) {
                OnboardingStep.WELCOME -> WelcomeStep()
                OnboardingStep.SERVICE -> ServiceStep(serviceOn, openServiceSettings)
                OnboardingStep.HANDLING -> HandlingStep(
                    preset = preset,
                    autoCloseConsent = autoCloseConsent,
                    takeover = takeover,
                    onPreset = { apply(OnboardingFlow.withPreset(draft, it)) },
                    onConsent = { apply(OnboardingFlow.withAutoCloseConsent(draft, it)) },
                    onTakeover = { takeover = it },
                )
                else -> DoneStep(draft, catalog, serviceOn, consentGranted)
            }
        }
        Column(
            Modifier.padding(horizontal = 24.dp, vertical = 12.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            when (step) {
                OnboardingStep.WELCOME -> BrandButton(stringResource(R.string.ui_onboarding_start), next)
                OnboardingStep.SERVICE -> {
                    if (serviceOn) {
                        BrandButton(stringResource(R.string.ui_onboarding_next), next)
                    } else {
                        SecondaryButton(stringResource(R.string.ui_setup_service_later), next)
                    }
                    SecondaryButton(stringResource(R.string.ui_onboarding_back), back)
                }
                OnboardingStep.DONE -> {
                    BrandButton(
                        stringResource(R.string.ui_setup_begin),
                        { OnboardingFlow.result(draft, catalog)?.let(onFinish) },
                        enabled = blockReason == null,
                        disabledReason = blockReason,
                    )
                    SecondaryButton(stringResource(R.string.ui_onboarding_back), back)
                }
                else -> {
                    BrandButton(stringResource(R.string.ui_onboarding_next), next, enabled = blockReason == null, disabledReason = blockReason)
                    SecondaryButton(stringResource(R.string.ui_onboarding_back), back)
                }
            }
        }
    }
}

@Composable
private fun defaultOpenServiceSettings(): () -> Boolean {
    val context = LocalContext.current
    return { AndroidCoreSetupStatus.openServiceSettings(context) }
}

@Composable
private fun SecondaryButton(label: String, onClick: () -> Unit) {
    OutlinedButton(
        onClick = onClick,
        modifier = Modifier.fillMaxWidth().heightIn(min = 56.dp),
        shape = RoundedCornerShape(16.dp),
    ) { Text(label, fontSize = 17.sp) }
}

private fun OnboardingStep.titleRes() = when (this) {
    OnboardingStep.WELCOME -> R.string.ui_app_display_name
    OnboardingStep.SERVICE -> R.string.ui_setup_service_title
    OnboardingStep.HANDLING -> R.string.ui_preset_section
    OnboardingStep.HELP_APPS -> R.string.ui_help_apps_section
    OnboardingStep.DONE -> R.string.ui_setup_done_title
}

internal fun OnboardingBlock.reasonRes() = when (this) {
    OnboardingBlock.CHOOSE_PRESET -> R.string.ui_onboarding_choose_preset_first
    OnboardingBlock.CHOOSE_HELP_WAY -> R.string.ui_help_apps_choose_first
    OnboardingBlock.PICK_ONE_APP -> R.string.ui_setup_pick_one_app
    OnboardingBlock.LOADING_APPS -> R.string.ui_help_apps_loading
}

@Composable
private fun ColumnScope.WelcomeStep() {
    Text(
        stringResource(R.string.ui_setup_step, OnboardingStep.COUNT, OnboardingStep.WELCOME.number),
        fontSize = UiType.help,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
    )
    Spacer(Modifier.height(16.dp))
    Box(Modifier.fillMaxWidth(), contentAlignment = Alignment.Center) { AppMark() }
    Text(
        stringResource(R.string.ui_app_display_name),
        fontSize = 34.sp,
        fontWeight = FontWeight.ExtraBold,
        textAlign = TextAlign.Center,
        modifier = Modifier.fillMaxWidth().semantics { heading() },
    )
    Text(
        stringResource(R.string.ui_setup_tagline),
        fontSize = UiType.subtitle,
        textAlign = TextAlign.Center,
        modifier = Modifier.fillMaxWidth(),
    )
    BodyText(stringResource(R.string.ui_onboarding_intro))
    HelpText(stringResource(R.string.ui_onboarding_later))
}

@Composable
private fun ServiceStep(serviceOn: Boolean, openServiceSettings: () -> Boolean) {
    var openFailed by rememberSaveable { mutableStateOf(false) }
    StepTitle(stringResource(R.string.ui_setup_service_title))
    BodyText(stringResource(R.string.ui_setup_service_intro))
    InfoCard(
        listOf(
            stringResource(R.string.ui_setup_service_reads),
            stringResource(R.string.ui_setup_service_local),
            stringResource(R.string.ui_setup_service_never_press),
            stringResource(R.string.ui_setup_service_excluded),
        ),
    )
    // Polite: when the person comes back from settings, TalkBack reads "켜짐" once; focus stays.
    Box(Modifier.semantics(mergeDescendants = true) { liveRegion = LiveRegionMode.Polite }) { ServiceStateBox(serviceOn) }
    if (!serviceOn) {
        HelpText(stringResource(R.string.ui_setup_service_how))
        BrandButton(stringResource(R.string.ui_setup_service_turn_on), { openFailed = !openServiceSettings() })
        if (openFailed) {
            Text(
                stringResource(R.string.ui_setup_service_open_failed),
                fontSize = UiType.body,
                modifier = Modifier.semantics { liveRegion = LiveRegionMode.Polite },
            )
        }
        HelpText(stringResource(R.string.ui_setup_service_off_note))
    }
}

@Composable
private fun HandlingStep(
    preset: HandlingPreset?,
    autoCloseConsent: Boolean,
    takeover: Boolean,
    onPreset: (HandlingPreset) -> Unit,
    onConsent: (Boolean) -> Unit,
    onTakeover: (Boolean) -> Unit,
) {
    StepTitle(stringResource(R.string.ui_preset_section))
    BodyText(stringResource(R.string.ui_preset_section_help))
    HandlingPresetWithConsent(
        selected = preset,
        consentOn = autoCloseConsent,
        onPresetChange = { chosen, _ -> onPreset(chosen) },
        onConsentChange = onConsent,
    )
    if (TalkBackTakeoverFeature.enabled) {
        Text(
            stringResource(R.string.ui_setup_takeover_heading),
            fontSize = UiType.subtitle,
            fontWeight = FontWeight.Bold,
            modifier = Modifier.padding(top = 8.dp).semantics { heading() },
        )
        BodyText(stringResource(R.string.ui_setup_takeover_question))
        SwitchCard(
            label = stringResource(R.string.ui_settings_takeover),
            help = stringResource(R.string.ui_settings_takeover_help),
            checked = takeover,
            onCheckedChange = onTakeover,
        )
    }
}

@Composable
private fun DoneStep(draft: OnboardingDraft, catalog: AppCatalog?, serviceOn: Boolean, consentGranted: Boolean) {
    val brand = brandColors()
    Box(Modifier.fillMaxWidth(), contentAlignment = Alignment.Center) { CheckMark(brand.gradientEnd, 96, big = true) }
    StepTitle(stringResource(R.string.ui_setup_done_title))
    BodyText(stringResource(R.string.ui_setup_done_intro))
    val on = stringResource(R.string.ui_setup_on)
    val off = stringResource(R.string.ui_setup_off)
    val summary = OnboardingFlow.helpAppsSummary(draft, catalog)
    Surface(shape = RoundedCornerShape(16.dp), color = MaterialTheme.colorScheme.surfaceVariant, modifier = Modifier.fillMaxWidth()) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(14.dp)) {
            SummaryRow(stringResource(R.string.ui_setup_sum_service), if (serviceOn) on else off)
            SummaryRow(stringResource(R.string.ui_preset_section), draft.preset?.let { stringResource(PresetText.labelRes(it)) }.orEmpty())
            SummaryRow(stringResource(R.string.ui_preset_consent_switch), if (draft.autoCloseConsent) on else off)
            if (TalkBackTakeoverFeature.enabled) {
                SummaryRow(stringResource(R.string.ui_settings_takeover), if (draft.talkBackTakeover) on else off)
            }
            SummaryRow(stringResource(R.string.ui_help_apps_section), helpAppsSummaryText(summary))
        }
    }
    if (!serviceOn) BodyText(stringResource(R.string.ui_setup_done_service_off))
    if (summary != HelpAppsSummary.Later && !consentGranted) BodyText(stringResource(R.string.ui_setup_done_consent))
    HelpText(stringResource(R.string.ui_onboarding_later))
}

@Composable
private fun helpAppsSummaryText(summary: HelpAppsSummary): String = when (summary) {
    HelpAppsSummary.Later -> stringResource(R.string.ui_setup_help_apps_later)
    is HelpAppsSummary.All -> stringResource(R.string.ui_setup_sum_help_all, summary.count)
    is HelpAppsSummary.Chosen -> {
        val shown = summary.names.take(OnboardingFlow.SUMMARY_NAMES).joinToString(", ")
        val more = summary.names.size - OnboardingFlow.SUMMARY_NAMES
        if (more > 0) stringResource(R.string.ui_setup_sum_help_more, shown, more) else shown
    }
}

private val previewCatalog = HelpAppsCatalog.build(
    listOf(InstalledApp("com.android.chrome", "Chrome"), InstalledApp("com.google.android.youtube", "YouTube")),
    ownPackage = "kr.closeforyou",
)

@Preview(name = "처음 설정 1단계", showBackground = true, heightDp = 800)
@Composable
private fun OnboardingWelcomePreview() {
    UiVoiceTheme {
        UiVoiceOnboardingScreen(
            initialDraft = OnboardingDraft(talkBackTakeover = true),
            status = CoreSetupStatus.NONE,
            onFinish = {},
            catalogOverride = previewCatalog,
            openServiceSettings = { true },
        )
    }
}

@Preview(name = "처음 설정 5단계", showBackground = true, heightDp = 1000)
@Composable
private fun OnboardingDonePreview() {
    UiVoiceTheme {
        UiVoiceOnboardingScreen(
            initialDraft = OnboardingDraft(
                step = OnboardingStep.DONE,
                preset = HandlingPreset.CLOSE_ADS_ONLY,
                talkBackTakeover = true,
                helpApps = HelpAppsDraft(mode = HelpAppsMode.CHOSEN, chosen = setOf("com.android.chrome")),
            ),
            status = CoreSetupStatus.NONE,
            onFinish = {},
            catalogOverride = previewCatalog,
            openServiceSettings = { true },
        )
    }
}
