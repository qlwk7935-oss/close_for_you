package kr.closeforyou.ui.voice.settings

import android.view.accessibility.AccessibilityEvent
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.luminance
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class OnboardingFlowTest {
    private val catalog = HelpAppsCatalog.build(
        listOf(
            InstalledApp("com.android.chrome", "Chrome"),
            InstalledApp("com.google.android.youtube", "YouTube"),
            InstalledApp("com.kbstar.kbbank", "KB스타뱅킹"),
            InstalledApp("com.nhn.android.search", "네이버"),
        ),
        ownPackage = "kr.closeforyou",
    )

    private fun draft(step: OnboardingStep = OnboardingStep.WELCOME) = OnboardingDraft(step = step, talkBackTakeover = true)

    @Test fun fiveStepsInOrder() {
        assertEquals(5, OnboardingStep.COUNT)
        assertEquals(
            listOf(OnboardingStep.WELCOME, OnboardingStep.SERVICE, OnboardingStep.HANDLING, OnboardingStep.HELP_APPS, OnboardingStep.DONE),
            OnboardingStep.entries,
        )
        assertEquals(listOf(1, 2, 3, 4, 5), OnboardingStep.entries.map { it.number })
    }

    @Test fun welcomeAndServiceNeverBlock() {
        assertNull(OnboardingFlow.block(draft(), null))
        // Service off still continues ([나중에 켜기]); the step and the summary say it is off.
        assertNull(OnboardingFlow.block(draft(OnboardingStep.SERVICE), null))
        assertEquals(OnboardingStep.HANDLING, OnboardingFlow.next(draft(OnboardingStep.SERVICE), null).step)
    }

    @Test fun presetStepWaitsForAPreset() {
        val noPreset = draft(OnboardingStep.HANDLING)
        assertEquals(OnboardingBlock.CHOOSE_PRESET, OnboardingFlow.block(noPreset, null))
        assertEquals(noPreset, OnboardingFlow.next(noPreset, null))
        val chosen = OnboardingFlow.withPreset(noPreset, HandlingPreset.CLOSE_ADS_ONLY)
        assertEquals(OnboardingStep.HELP_APPS, OnboardingFlow.next(chosen, null).step)
    }

    @Test fun autoCloseSwitchNeedsAPresetAndStartsOff() {
        val start = OnboardingGatePolicy.initialDraft(UiVoiceSettings())
        assertNull(start.preset)
        assertFalse(start.autoCloseConsent)
        assertFalse(OnboardingFlow.withAutoCloseConsent(start, true).autoCloseConsent)
        val chosen = OnboardingFlow.withPreset(start, HandlingPreset.CLOSE_ALL)
        assertTrue(OnboardingFlow.withAutoCloseConsent(chosen, true).autoCloseConsent)
    }

    @Test fun takeoverQuestionStartsAtTheSavedSetting() {
        assertEquals(UiVoiceSettings().talkBackTakeover, OnboardingGatePolicy.initialDraft(UiVoiceSettings()).talkBackTakeover)
        assertFalse(OnboardingGatePolicy.initialDraft(UiVoiceSettings(talkBackTakeover = false)).talkBackTakeover)
    }

    @Test fun helpAppsNeedAWayAndChosenNeedsOneApp() {
        val step = draft(OnboardingStep.HELP_APPS)
        assertEquals(OnboardingBlock.CHOOSE_HELP_WAY, OnboardingFlow.block(step, catalog))
        // The list is still loading.
        assertEquals(OnboardingBlock.CHOOSE_HELP_WAY, OnboardingFlow.block(step.copy(helpApps = HelpAppsDraft(HelpAppsMode.ALL)), null))
        assertNull(OnboardingFlow.block(step.copy(helpApps = HelpAppsDraft(HelpAppsMode.ALL)), catalog))
        val chosenNone = step.copy(helpApps = HelpAppsDraft(HelpAppsMode.CHOSEN))
        assertEquals(OnboardingBlock.PICK_ONE_APP, OnboardingFlow.block(chosenNone, catalog))
        // A bank app ticked is never helped, so it does not count as one.
        assertEquals(OnboardingBlock.PICK_ONE_APP, OnboardingFlow.block(chosenNone.copy(helpApps = HelpAppsDraft(HelpAppsMode.CHOSEN, chosen = setOf("com.kbstar.kbbank"))), catalog))
        assertNull(OnboardingFlow.block(chosenNone.copy(helpApps = HelpAppsDraft(HelpAppsMode.CHOSEN, chosen = setOf("com.android.chrome"))), catalog))
    }

    @Test fun backGoesOneStepAndNotBeforeTheFirst() {
        assertNull(OnboardingFlow.back(draft()))
        OnboardingStep.entries.drop(1).forEach { step ->
            assertEquals(OnboardingStep.entries[step.ordinal - 1], OnboardingFlow.back(draft(step))!!.step)
        }
    }

    @Test fun backKeepsEveryChoice() {
        val done = draft(OnboardingStep.DONE).copy(preset = HandlingPreset.CLOSE_ALL, autoCloseConsent = true, helpApps = HelpAppsDraft(HelpAppsMode.ALL))
        val back = OnboardingFlow.back(done)!!
        assertEquals(done.copy(step = OnboardingStep.HELP_APPS), back)
    }

    @Test fun nextOnTheLastStepStays() {
        val done = draft(OnboardingStep.DONE).copy(preset = HandlingPreset.CLOSE_ALL)
        assertEquals(done, OnboardingFlow.next(done, catalog))
    }

    @Test fun skipHelpAppsGoesToTheSummaryAndSavesNoApps() {
        val step = draft(OnboardingStep.HELP_APPS).copy(preset = HandlingPreset.CLOSE_ADS_ONLY, helpApps = HelpAppsDraft(HelpAppsMode.ALL))
        val skipped = OnboardingFlow.skipHelpApps(step)
        assertEquals(OnboardingStep.DONE, skipped.step)
        assertTrue(skipped.helpAppsSkipped)
        assertNull(OnboardingFlow.result(skipped, catalog)!!.helpApps)
        assertEquals(HelpAppsSummary.Later, OnboardingFlow.helpAppsSummary(skipped, catalog))
        // Only on its own step.
        assertEquals(draft(OnboardingStep.HANDLING), OnboardingFlow.skipHelpApps(draft(OnboardingStep.HANDLING)))
    }

    @Test fun goingBackAndChoosingClearsTheSkip() {
        val skipped = OnboardingFlow.skipHelpApps(draft(OnboardingStep.HELP_APPS).copy(preset = HandlingPreset.CLOSE_ALL))
        val again = OnboardingFlow.back(skipped)!!.copy(helpApps = HelpAppsDraft(HelpAppsMode.ALL))
        val done = OnboardingFlow.next(again, catalog)
        assertFalse(done.helpAppsSkipped)
        assertTrue(OnboardingFlow.result(done, catalog)!!.helpApps != null)
    }

    @Test fun resultCarriesEveryChoice() {
        val done = draft(OnboardingStep.DONE).copy(
            preset = HandlingPreset.CLOSE_ADS_AND_COUPONS,
            autoCloseConsent = true,
            talkBackTakeover = false,
            helpApps = HelpAppsDraft(HelpAppsMode.CHOSEN, chosen = setOf("com.android.chrome")),
        )
        val result = OnboardingFlow.result(done, catalog)!!
        assertEquals(HandlingPreset.CLOSE_ADS_AND_COUPONS, result.preset)
        assertTrue(result.autoCloseConsent)
        assertFalse(result.talkBackTakeover)
        assertEquals(HelpAppsMode.CHOSEN, result.helpApps!!.draft.mode)
    }

    @Test fun noResultWithoutAPreset() = assertNull(OnboardingFlow.result(draft(OnboardingStep.DONE), catalog))

    /** After rotation the list loads again; [사용 시작] waits instead of dropping the chosen apps. */
    @Test fun beginWaitsForTheAppListWhenAppsWereChosen() {
        val done = draft(OnboardingStep.DONE).copy(preset = HandlingPreset.CLOSE_ALL, helpApps = HelpAppsDraft(HelpAppsMode.ALL))
        assertEquals(OnboardingBlock.LOADING_APPS, OnboardingFlow.block(done, null))
        assertNull(OnboardingFlow.result(done, null))
        assertNull(OnboardingFlow.block(done, catalog))
        // Skipped: nothing to wait for.
        assertNull(OnboardingFlow.block(done.copy(helpAppsSkipped = true), null))
    }

    @Test fun summaryNamesTheChosenAppsInListOrder() {
        val done = draft(OnboardingStep.DONE).copy(
            preset = HandlingPreset.CLOSE_ALL,
            helpApps = HelpAppsDraft(HelpAppsMode.CHOSEN, chosen = setOf("com.nhn.android.search", "com.android.chrome", "com.kbstar.kbbank")),
        )
        assertEquals(HelpAppsSummary.Chosen(listOf("Chrome", "네이버")), OnboardingFlow.helpAppsSummary(done, catalog))
    }

    @Test fun summaryCountsAllAppsWithoutBankApps() {
        val done = draft(OnboardingStep.DONE).copy(preset = HandlingPreset.CLOSE_ALL, helpApps = HelpAppsDraft(HelpAppsMode.ALL))
        assertEquals(HelpAppsSummary.All(3), OnboardingFlow.helpAppsSummary(done, catalog))
    }

    @Test fun restartingSetupStopsAutoCloseUntilBegin() {
        val settings = UiVoiceSettings(handlingPreset = HandlingPreset.CLOSE_ALL, onboardingDone = true).withAutoCloseConsent(true, 1L)
        val restarted = settings.copy(onboardingDone = false)
        assertTrue(OnboardingGatePolicy.showOnboarding(restarted))
        assertTrue(restarted.autoCloseKinds(live = true).isEmpty())
        assertNull(OnboardingGatePolicy.initialDraft(restarted).preset)
    }
}

class AccessibilityServiceRulesTest {
    private val service = ServiceComponent("kr.closeforyou", AndroidCoreSetupStatus.A_SERVICE_CLASS)

    @Test fun enabledListMatchesPackageAndClass() {
        assertTrue(AccessibilityServiceRules.isEnabled(service, listOf(service), null))
        assertFalse(AccessibilityServiceRules.isEnabled(service, listOf(ServiceComponent("kr.closeforyou", "kr.closeforyou.ui.voice.debug.ChoiceOverlayTestService")), null))
        assertFalse(AccessibilityServiceRules.isEnabled(service, listOf(ServiceComponent("kr.other", service.className)), ""))
    }

    @Test fun secureSettingFullAndShortNames() {
        assertTrue(AccessibilityServiceRules.inSecureSetting(service, "com.google.android.marvin.talkback/.TalkBackService:kr.closeforyou/kr.closeforyou.accessibility.CloseForYouAccessibilityService"))
        assertTrue(AccessibilityServiceRules.inSecureSetting(service, "kr.closeforyou/.accessibility.CloseForYouAccessibilityService"))
        assertTrue(AccessibilityServiceRules.isEnabled(service, emptyList(), "kr.closeforyou/.accessibility.CloseForYouAccessibilityService"))
    }

    @Test fun secureSettingOthersOrBrokenAreOff() {
        listOf(null, "", ":", "kr.closeforyou", "/x", "com.google.android.marvin.talkback/.TalkBackService", "kr.closeforyou/.ui.voice.debug.ChoiceOverlayTestService")
            .forEach { assertFalse("$it", AccessibilityServiceRules.inSecureSetting(service, it)) }
    }

    @Test fun noneIsTheSafeSide() {
        assertFalse(CoreSetupStatus.NONE.serviceEnabled())
        assertFalse(CoreSetupStatus.NONE.collectionConsentGranted())
    }
}

/** KWCAG/WCAG ≥ 4.5:1 for the brand colors on the material default surfaces. */
class BrandColorContrastTest {
    private fun contrast(a: Color, b: Color): Double {
        val l1 = a.luminance().toDouble()
        val l2 = b.luminance().toDouble()
        return (maxOf(l1, l2) + 0.05) / (minOf(l1, l2) + 0.05)
    }

    @Test fun brandTextAndButtonsMeetContrast() {
        listOf(false, true).forEach { dark ->
            val scheme = kr.closeforyou.ui.voice.uiVoiceColorScheme(dark)
            val brand = UiBrandColors.of(dark)
            val pairs = mapOf(
                "강조 글자/화면" to (brand.accent to scheme.surface),
                "버튼 글자/버튼" to (brand.onButton to brand.buttonContainer),
                "강조 글자/카드" to (brand.accent to scheme.surfaceVariant),
            )
            pairs.forEach { (name, pair) ->
                val ratio = contrast(pair.first, pair.second)
                assertTrue("${if (dark) "다크" else "라이트"} $name = ${"%.2f".format(ratio)}", ratio >= 4.5)
            }
        }
    }
}

class OnboardingTextTest {
    private val strings = java.io.File("src/main/res/values/ui_voice_strings.xml").readText()
    private fun text(name: String) = Regex("""<string name="$name">([^<]*)</string>""").find(strings)!!.groupValues[1]

    @Test fun appNameIsPopupHelperEverywhereInCStrings() {
        assertEquals("팝업처리도우미", text("ui_app_display_name"))
        assertFalse(strings.contains("닫아줄게"))
    }

    @Test fun serviceStepPromisesNeverToPressImportantButtons() {
        val line = text("ui_setup_service_never_press")
        listOf("결제", "동의", "구독", "삭제", "절대").forEach { assertTrue(it, line.contains(it)) }
        assertTrue(text("ui_setup_service_local").contains("밖으로 보내지 않아요"))
    }

    @Test fun stepLineHasCountAndNumber() {
        assertEquals("5단계 중 2단계", text("ui_setup_step").replace("%1\$d", "5").replace("%2\$d", "2"))
    }

    @Test fun selectedWordsMatchTalkBackStateWording() {
        assertEquals("선택됨", text("ui_setup_selected"))
        assertEquals("선택 안 됨", text("ui_setup_not_selected"))
    }

    @Test fun setupTextsNeverClaimSomethingWasClosed() {
        Regex("""<string name="(ui_setup_[a-z_]+)">""").findAll(strings).map { it.groupValues[1] }.toList().also { assertTrue(it.size >= 30) }
            .forEach { name ->
                listOf("닫았어요", "닫혔어요", "차단했어요").forEach { assertFalse("$name", text(name).contains(it)) }
            }
    }
}

class ServiceTurnOnGuideTest {
    @Test fun highlightKeyIsTheFullFlattenedComponent() {
        val key = ServiceSettingsHighlight.key(ServiceComponent("kr.closeforyou", AndroidCoreSetupStatus.A_SERVICE_CLASS))
        assertEquals("kr.closeforyou/kr.closeforyou.accessibility.CloseForYouAccessibilityService", key)
        assertEquals(":settings:fragment_args_key", ServiceSettingsHighlight.EXTRA_FRAGMENT_ARG_KEY)
        assertEquals(":settings:show_fragment_args", ServiceSettingsHighlight.EXTRA_SHOW_FRAGMENT_ARGS)
    }

    @Test fun guideIsSpokenAtOnceWithoutTalkBack() {
        assertEquals(GuideSpeech.NOW, ServiceGuidePolicy.guide(touchExplorationOn = false, takeoverOn = false))
        assertEquals(GuideSpeech.NOW, ServiceGuidePolicy.guide(touchExplorationOn = false, takeoverOn = true))
    }

    @Test fun withTalkBackOurSpeechFollowsTheTakeoverSetting() {
        assertEquals(GuideSpeech.AFTER_TALKBACK, ServiceGuidePolicy.guide(touchExplorationOn = true, takeoverOn = true))
        assertEquals(GuideSpeech.NONE, ServiceGuidePolicy.guide(touchExplorationOn = true, takeoverOn = false))
        assertTrue(ServiceGuidePolicy.AFTER_TALKBACK_MILLIS > 0)
    }

    @Test fun focusMovesToOpenOnlyAfterFullSpeechWithTalkBackAndTakeover() {
        assertTrue(ServiceGuidePolicy.moveFocusToOpen(GuideSpeech.AFTER_TALKBACK, completed = true, personInteracted = false))
        assertFalse(ServiceGuidePolicy.moveFocusToOpen(GuideSpeech.AFTER_TALKBACK, completed = false, personInteracted = false))
        assertFalse(ServiceGuidePolicy.moveFocusToOpen(GuideSpeech.AFTER_TALKBACK, completed = true, personInteracted = true))
        // Without TalkBack there is no TalkBack focus to move.
        assertFalse(ServiceGuidePolicy.moveFocusToOpen(GuideSpeech.NOW, completed = true, personInteracted = false))
        assertFalse(ServiceGuidePolicy.moveFocusToOpen(GuideSpeech.NONE, completed = true, personInteracted = false))
    }

    @Test fun touchOrSwipeAwayFromTheTitleMeansThePersonTookOver() {
        val title = "설정에서 이렇게 켜 주세요"
        assertTrue(ServiceGuidePolicy.isPersonEvent(AccessibilityEvent.TYPE_VIEW_HOVER_ENTER, "", title))
        assertTrue(ServiceGuidePolicy.isPersonEvent(AccessibilityEvent.TYPE_VIEW_ACCESSIBILITY_FOCUSED, "취소", title))
        // TalkBack focusing the title by itself when the dialog opens is not the person.
        assertFalse(ServiceGuidePolicy.isPersonEvent(AccessibilityEvent.TYPE_VIEW_ACCESSIBILITY_FOCUSED, title, title))
        assertFalse(ServiceGuidePolicy.isPersonEvent(AccessibilityEvent.TYPE_WINDOW_CONTENT_CHANGED, "", title))
    }

    @Test fun turnedOnIsSpokenWithoutTalkBackOrAfterItWithTakeover() {
        assertEquals(GuideSpeech.NOW, ServiceGuidePolicy.turnedOn(touchExplorationOn = false, takeoverOn = false))
        // LG 10/30: TalkBack read only the pane title, so with takeover our TTS says it after TalkBack.
        assertEquals(GuideSpeech.AFTER_TALKBACK, ServiceGuidePolicy.turnedOn(touchExplorationOn = true, takeoverOn = true))
        assertEquals(GuideSpeech.NONE, ServiceGuidePolicy.turnedOn(touchExplorationOn = true, takeoverOn = false))
    }

    /** 재호 10/30: 2/5 shown with the service on and TalkBack on → [다음], whatever was spoken. */
    @Test fun nextGetsFocusWheneverTheServiceStepShowsWithTheServiceOn() {
        assertTrue(ServiceGuidePolicy.placeOnShow(serviceOn = true, talkBackOn = true, hasTarget = true))
        assertFalse(ServiceGuidePolicy.placeOnShow(serviceOn = false, talkBackOn = true, hasTarget = true))
        // Without TalkBack there is no TalkBack focus.
        assertFalse(ServiceGuidePolicy.placeOnShow(serviceOn = true, talkBackOn = false, hasTarget = true))
        // Settings has no [다음].
        assertFalse(ServiceGuidePolicy.placeOnShow(serviceOn = true, talkBackOn = true, hasTarget = false))
    }

    @Test fun placingWaitsForTalkBackAndWatchesForSeveralSeconds() {
        assertTrue(ServiceGuidePolicy.PLACE_DELAY_MILLIS in 1_500L..2_000L)
        assertTrue(ServiceGuidePolicy.PLACE_DEADLINE_MILLIS >= 5_000L)
    }

    /** LG 10/30: moves came in bundles on a timer. Move again only after TalkBack's focus went elsewhere. */
    @Test fun focusMovesAgainOnlyAfterItWasLost() {
        assertEquals(PlaceStep.WAIT, ServiceGuidePolicy.placeStep(personInteracted = false, lostSinceMove = false, moves = 1))
        assertEquals(PlaceStep.MOVE, ServiceGuidePolicy.placeStep(personInteracted = false, lostSinceMove = true, moves = 1))
        assertEquals(PlaceStep.STOP, ServiceGuidePolicy.placeStep(personInteracted = true, lostSinceMove = true, moves = 1))
        // Never more than a few moves in all, however often TalkBack takes it back.
        assertEquals(PlaceStep.STOP, ServiceGuidePolicy.placeStep(false, true, ServiceGuidePolicy.PLACE_MAX_MOVES))
        assertTrue(ServiceGuidePolicy.PLACE_MAX_MOVES <= 3)
    }

    /** The log says why the person was taken to have taken over (no text). */
    @Test fun personReasonIsHoverOrSwipe() {
        val own = listOf("5단계 중 2단계", "다음")
        assertEquals(PersonReason.HOVER, ServiceGuidePolicy.personReason(AccessibilityEvent.TYPE_VIEW_HOVER_ENTER, "", own))
        assertEquals(PersonReason.SWIPE_FOCUS, ServiceGuidePolicy.personReason(AccessibilityEvent.TYPE_VIEW_ACCESSIBILITY_FOCUSED, "이전 단계", own))
        assertNull(ServiceGuidePolicy.personReason(AccessibilityEvent.TYPE_VIEW_ACCESSIBILITY_FOCUSED, "5단계 중 2단계", own))
        assertNull(ServiceGuidePolicy.personReason(AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED, "", own))
    }

    /** e71ee6f: TalkBack's back gesture right after resume was taken for the person; the grace covers it. */
    @Test fun graceCoversTalkBacksReturnBeforeTheFirstMove() {
        assertTrue(ServiceGuidePolicy.PLACE_DELAY_MILLIS >= 1_000L)
    }

    @Test fun debugTraceNamesCarryNoScreenText() {
        FocusTrace.entries.forEach { assertTrue(it.name, it.name.matches(Regex("[A-Z_]+"))) }
    }

    @Test fun focusToNextIsCheckedAndRepeatedUntilItHoldsUnlessThePersonTookOver() {
        assertTrue(ServiceGuidePolicy.shouldMoveAgain(targetFocused = false, personInteracted = false))
        assertFalse(ServiceGuidePolicy.shouldMoveAgain(targetFocused = true, personInteracted = false))
        assertFalse(ServiceGuidePolicy.shouldMoveAgain(targetFocused = false, personInteracted = true))
    }

    /** LG 10/30: back from settings TalkBack refocuses the step line by itself; that is not the person. */
    @Test fun talkBackRefocusingTheStepLineIsNotThePerson() {
        val own = listOf("5단계 중 2단계", "다음")
        assertFalse(ServiceGuidePolicy.isPersonEvent(AccessibilityEvent.TYPE_VIEW_ACCESSIBILITY_FOCUSED, "5단계 중 2단계", own))
        assertFalse(ServiceGuidePolicy.isPersonEvent(AccessibilityEvent.TYPE_VIEW_ACCESSIBILITY_FOCUSED, "다음", own))
        assertTrue(ServiceGuidePolicy.isPersonEvent(AccessibilityEvent.TYPE_VIEW_ACCESSIBILITY_FOCUSED, "이전 단계", own))
        assertTrue(ServiceGuidePolicy.isPersonEvent(AccessibilityEvent.TYPE_VIEW_HOVER_ENTER, "5단계 중 2단계", own))
    }

    @Test fun onTheScreenEveryFocusMoveAfterTalkBackSettledIsThePerson() {
        assertTrue(ServiceGuidePolicy.isPersonEvent(AccessibilityEvent.TYPE_VIEW_ACCESSIBILITY_FOCUSED, "5단계 중 2단계", null as String?))
        assertTrue(ServiceGuidePolicy.isPersonEvent(AccessibilityEvent.TYPE_VIEW_HOVER_ENTER, "", null as String?))
    }
}

class ServiceGuideTextTest {
    private val strings = java.io.File("src/main/res/values/ui_voice_strings.xml").readText()
    private fun text(name: String) = Regex("""<string name="$name">([^<]*)</string>""").find(strings)!!.groupValues[1]

    /** The service's name in system settings is A's app_name, so the guide takes it as an argument. */
    @Test fun guideNamesTheAppThroughAnArgument() {
        listOf("ui_setup_guide_find", "ui_setup_guide_find_talkback", "ui_setup_service_open_failed").forEach {
            assertTrue(it, text(it).contains("%1\$s"))
            assertFalse(it, text(it).contains("팝업처리도우미"))
        }
    }

    @Test fun guideSaysDoubleTapAllowAndComeBack() {
        val talkBack = text("ui_setup_guide_find_talkback")
        assertTrue(talkBack.contains("두 번 탭") && talkBack.contains("오른쪽으로") && talkBack.contains("서비스 아래"))
        assertTrue(text("ui_setup_guide_find").contains("서비스 아래"))
        assertTrue(text("ui_setup_guide_turn_on").contains("허용"))
        assertTrue(text("ui_setup_guide_come_back").contains("뒤로 가기"))
    }
}

class HelpAppsStatusPlanTest {
    private val catalog = HelpAppsCatalog.build(
        listOf(
            InstalledApp("com.android.chrome", "Chrome"),
            InstalledApp("com.google.android.youtube", "YouTube"),
            InstalledApp("com.kbstar.kbbank", "KB스타뱅킹"),
            InstalledApp("com.nhn.android.search", "네이버"),
        ),
        ownPackage = "kr.closeforyou",
    )
    private val all = setOf("com.android.chrome", "com.google.android.youtube", "com.nhn.android.search")

    @Test fun allModeShowsOnlyHowManyAreLeftOut() {
        val line = HelpAppsStatusPlan.line(HelpAppsMode.ALL, all, emptySet(), setOf("com.google.android.youtube"), catalog, consentGranted = true)
        assertEquals(HelpAppsMode.ALL, line.mode)
        // The bank app (always) + YouTube (taken out by the person).
        assertEquals(2, line.excludedCount)
        assertFalse(line.waiting)
        // Still loading: no number.
        assertNull(HelpAppsStatusPlan.line(HelpAppsMode.ALL, all, emptySet(), emptySet(), null, true).excludedCount)
    }

    @Test fun chosenModeCountsAllowedAndWaitingTogetherAsOneNumber() {
        val line = HelpAppsStatusPlan.line(HelpAppsMode.CHOSEN, setOf("com.android.chrome"), setOf("com.nhn.android.search"), emptySet(), catalog, consentGranted = false)
        assertEquals(2, line.helpedCount)
        assertTrue(line.waiting)
        assertNull(line.excludedCount)
    }

    @Test fun waitingOnlyBeforeConsentAndOnlyForAppsNotYetAllowed() {
        assertFalse(HelpAppsStatusPlan.line(HelpAppsMode.ALL, all, setOf("com.android.chrome"), emptySet(), catalog, false).waiting)
        assertFalse(HelpAppsStatusPlan.line(HelpAppsMode.ALL, emptySet(), all, emptySet(), catalog, true).waiting)
        assertTrue(HelpAppsStatusPlan.line(HelpAppsMode.ALL, emptySet(), all, emptySet(), catalog, false).waiting)
    }

    @Test fun f19UnsetOnlyWithNoWayAndNoApp() {
        assertTrue(HelpAppsStatusPlan.unset(HelpAppsStatusPlan.line(null, emptySet(), emptySet(), emptySet(), catalog, true)))
        assertFalse(HelpAppsStatusPlan.unset(HelpAppsStatusPlan.line(null, setOf("com.android.chrome"), emptySet(), emptySet(), catalog, true)))
        assertFalse(HelpAppsStatusPlan.unset(HelpAppsStatusPlan.line(null, emptySet(), setOf("com.android.chrome"), emptySet(), catalog, false)))
        assertFalse(HelpAppsStatusPlan.unset(HelpAppsStatusPlan.line(HelpAppsMode.ALL, emptySet(), emptySet(), emptySet(), catalog, true)))
    }

    @Test fun noWaySavedButAppsAllowedReadsAsChosen() {
        assertEquals(HelpAppsMode.CHOSEN, HelpAppsStatusPlan.line(null, setOf("kr.example.typed"), emptySet(), emptySet(), catalog, true).mode)
        assertNull(HelpAppsStatusPlan.line(null, emptySet(), emptySet(), emptySet(), catalog, true).mode)
    }

    @Test fun autoCloseListsOnlyRememberedOrSwitchedOnAppsThatAreAllowed() {
        val candidates = AutoCloseApps.candidates(
            rememberedPackages = listOf("com.android.chrome", "kr.example.gone"),
            autoClosePackages = setOf("com.nhn.android.search"),
            allowed = all,
        )
        assertEquals(setOf("com.android.chrome", "com.nhn.android.search"), candidates)
        // Helping every app does not list every app with a switch.
        assertTrue(AutoCloseApps.candidates(emptyList(), emptySet(), all).isEmpty())
    }

    @Test fun blankCatalogNameIsUnknownNotThePackage() {
        val built = HelpAppsCatalog.build(listOf(InstalledApp("kr.example.blank", "  ")), "kr.closeforyou", unknownLabel = "알 수 없는 앱")
        assertEquals("알 수 없는 앱", built.all.single().label)
    }
}

class PerAppAutoCloseGateTest {
    /** 10/30: no per-app switch in demo mode; every helped app may repeat its remembered popups. */
    @Test fun demoModeAllowsEveryHelpedAppWithoutAPerAppSwitch() {
        assertFalse(PerAppAutoCloseGateFeature.enabled)
        val settings = UiVoiceSettings()
        assertTrue(settings.autoCloseAllowed("com.android.chrome", setOf("com.android.chrome")))
        // Still never outside "도와줄 앱".
        assertFalse(settings.autoCloseAllowed("com.android.chrome", emptySet()))
    }

    @Test fun theStoredPerAppListStillWorksWhenTheGateComesBack() {
        val on = UiVoiceSettings().withAutoClose("com.android.chrome", true)
        assertTrue(on.autoCloseAllowed("com.android.chrome", setOf("com.android.chrome"), perAppGate = true))
        assertFalse(UiVoiceSettings().autoCloseAllowed("com.android.chrome", setOf("com.android.chrome"), perAppGate = true))
    }

    @Test fun rulesHelpSaysPerPopupInEveryHelpedApp() {
        val strings = java.io.File("src/main/res/values/ui_voice_strings.xml").readText()
        val line = Regex("""<string name="ui_settings_rules_help">([^<]*)</string>""").find(strings)!!.groupValues[1]
        assertTrue(line.contains("도와줄 앱") && line.contains("어느 앱에서든") && line.contains("똑같은 팝업"))
    }
}
