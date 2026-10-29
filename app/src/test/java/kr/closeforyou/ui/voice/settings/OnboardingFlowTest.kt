package kr.closeforyou.ui.voice.settings

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
