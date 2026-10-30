package kr.closeforyou.ui.voice.settings

/** The five first-run steps, in order. Shown as "5단계 중 N단계". */
enum class OnboardingStep {
    WELCOME,
    SERVICE,
    HANDLING,
    HELP_APPS,
    DONE,
    ;

    /** 1-based, for "5단계 중 N단계". */
    val number: Int get() = ordinal + 1

    companion object {
        val COUNT = entries.size
    }
}

/**
 * Everything the first run decided, unsaved until [사용 시작]. [preset] starts null (A #34),
 * [helpApps] starts with no way chosen (A 10/24), [helpAppsSkipped] = [나중에 고르기].
 */
data class OnboardingDraft(
    val step: OnboardingStep = OnboardingStep.WELCOME,
    val preset: HandlingPreset? = null,
    val autoCloseConsent: Boolean = false,
    val talkBackTakeover: Boolean,
    val helpApps: HelpAppsDraft = HelpAppsDraft(),
    val helpAppsSkipped: Boolean = false,
)

/** Why [다음] is disabled on a step; read as the button's state and shown above it. */
enum class OnboardingBlock { CHOOSE_PRESET, CHOOSE_HELP_WAY, PICK_ONE_APP, LOADING_APPS }

/** Pure step rules, JVM-tested. Nothing here reads Android or saves anything. */
object OnboardingFlow {
    /**
     * Null = [다음] may be pressed. The service step never blocks: a person who can not turn it on
     * now (restricted settings, another device owner) still finishes and turns it on later; the
     * step says it is off and the summary repeats it.
     */
    fun block(draft: OnboardingDraft, catalog: AppCatalog?): OnboardingBlock? = when (draft.step) {
        OnboardingStep.HANDLING -> if (draft.preset == null) OnboardingBlock.CHOOSE_PRESET else null
        OnboardingStep.HELP_APPS -> helpAppsBlock(draft.helpApps, catalog)
        // The app list is loaded again after rotation; [사용 시작] waits so the chosen apps are not dropped.
        OnboardingStep.DONE -> if (!draft.helpAppsSkipped && draft.helpApps.mode != null && catalog == null) OnboardingBlock.LOADING_APPS else null
        else -> null
    }

    /** A way must be chosen, and "고른 앱만" needs at least one app. */
    fun helpAppsBlock(helpApps: HelpAppsDraft, catalog: AppCatalog?): OnboardingBlock? = when {
        !HelpAppsPlan.canSave(helpApps, catalog) -> OnboardingBlock.CHOOSE_HELP_WAY
        helpApps.mode == HelpAppsMode.CHOSEN && catalog != null && HelpAppsPlan.target(helpApps, catalog).isEmpty() ->
            OnboardingBlock.PICK_ONE_APP
        else -> null
    }

    fun next(draft: OnboardingDraft, catalog: AppCatalog?): OnboardingDraft {
        if (draft.step == OnboardingStep.DONE || block(draft, catalog) != null) return draft
        val step = OnboardingStep.entries[draft.step.ordinal + 1]
        // Leaving "도와줄 앱" with [다음] means it was decided, not skipped.
        return draft.copy(step = step, helpAppsSkipped = if (draft.step == OnboardingStep.HELP_APPS) false else draft.helpAppsSkipped)
    }

    /** [나중에 고르기] on "도와줄 앱": nothing is saved for the apps, the summary says so. */
    fun skipHelpApps(draft: OnboardingDraft): OnboardingDraft =
        if (draft.step != OnboardingStep.HELP_APPS) draft else draft.copy(step = OnboardingStep.DONE, helpAppsSkipped = true)

    /** [이전 단계] and system back. Null on the first step (back then leaves the app as usual). */
    fun back(draft: OnboardingDraft): OnboardingDraft? =
        if (draft.step == OnboardingStep.WELCOME) null else draft.copy(step = OnboardingStep.entries[draft.step.ordinal - 1])

    /** The step-3 preset; the switch can not stay on without one ([AutoCloseConsentPolicy.switchEnabled]). */
    fun withPreset(draft: OnboardingDraft, preset: HandlingPreset): OnboardingDraft = draft.copy(preset = preset)

    fun withAutoCloseConsent(draft: OnboardingDraft, on: Boolean): OnboardingDraft =
        draft.copy(autoCloseConsent = on && draft.preset != null)

    /** What [사용 시작] saves. Null before a preset is chosen (can not happen through [next]) or while [block]ed. */
    fun result(draft: OnboardingDraft, catalog: AppCatalog?): OnboardingResult? {
        val preset = draft.preset ?: return null
        if (block(draft, catalog) != null) return null
        val apps = if (draft.helpAppsSkipped || catalog == null || helpAppsBlock(draft.helpApps, catalog) != null) {
            null
        } else {
            OnboardingHelpApps(draft.helpApps, catalog)
        }
        return OnboardingResult(preset, draft.autoCloseConsent, draft.talkBackTakeover, apps)
    }

    /** The "도와줄 앱" line of the summary. */
    fun helpAppsSummary(draft: OnboardingDraft, catalog: AppCatalog?): HelpAppsSummary {
        val apps = result(draft, catalog)?.helpApps ?: return HelpAppsSummary.Later
        return when (apps.draft.mode) {
            HelpAppsMode.ALL -> HelpAppsSummary.All(HelpAppsPlan.target(apps.draft, apps.catalog).size)
            else -> {
                val target = HelpAppsPlan.target(apps.draft, apps.catalog)
                HelpAppsSummary.Chosen(apps.catalog.helpable.filter { it.packageName in target }.map { it.label })
            }
        }
    }

    /** Up to this many app names are read in the summary; the rest are counted. */
    const val SUMMARY_NAMES = 5
}

/** What [사용 시작] hands to the gate. [helpApps] null = not decided (skipped, or no list). */
data class OnboardingResult(
    val preset: HandlingPreset,
    val autoCloseConsent: Boolean,
    val talkBackTakeover: Boolean,
    val helpApps: OnboardingHelpApps?,
)

sealed interface HelpAppsSummary {
    data object Later : HelpAppsSummary
    data class All(val count: Int) : HelpAppsSummary
    /** Names in the picker's order (recommended first is not kept: 가나다순). */
    data class Chosen(val names: List<String>) : HelpAppsSummary
}
