package kr.closeforyou.ui.voice.settings

import kr.closeforyou.R
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** Fake launcher apps only (no device). Package and app names here are made-up fixtures. */
class HelpAppsTest {
    private val own = "kr.closeforyou"
    private val apps = listOf(
        InstalledApp("com.google.android.youtube", "YouTube"),
        InstalledApp("com.kakao.talk", "카카오톡"),
        InstalledApp("com.kakao.talk", "카카오톡"), // second launcher activity of the same app
        InstalledApp("com.coupang.mobile", "쿠팡"),
        InstalledApp("com.facebook.katana", "페이스북"),
        InstalledApp("com.kakao.page", "카카오페이지"),
        InstalledApp("kr.example.notes", "가계부 메모"),
        InstalledApp("com.viva.republica.toss", "토스"),
        InstalledApp("com.kbstar.kbbank", "KB스타뱅킹"),
        InstalledApp("com.samsung.android.spay", "Samsung Wallet"),
        InstalledApp("kr.example.shopping", "예시 간편결제"),
        InstalledApp("com.x8bit.bitwarden", "Bitwarden"),
        InstalledApp("kr.example.secure", "내 비밀번호 보관함"),
        InstalledApp("kr.example.stocks", "주식 시세"),
        InstalledApp(own, "닫아줄게"),
    )
    private val catalog = HelpAppsCatalog.build(apps, own)

    // --- 제외 규칙 ---

    @Test fun ourOwnAppIsNeverListed() = assertFalse(own in catalog.packages)

    @Test fun duplicateLauncherEntriesBecomeOneRow() =
        assertEquals(1, catalog.all.count { it.packageName == "com.kakao.talk" })

    @Test fun bankPaymentCardAndPasswordAppsAreExcludedFromAll() {
        listOf(
            "com.viva.republica.toss", "com.kbstar.kbbank", "com.samsung.android.spay",
            "kr.example.shopping", "com.x8bit.bitwarden", "kr.example.secure", "kr.example.stocks",
        ).forEach { assertTrue(it, it in catalog.sensitive) }
    }

    @Test fun facebookAndPageAppsAreNotMistakenForPay() {
        listOf("com.facebook.katana", "com.kakao.page", "com.google.android.youtube", "com.kakao.talk", "com.coupang.mobile")
            .forEach { assertFalse(it, it in catalog.sensitive) }
    }

    @Test fun nameKeywordIgnoresSpacesAndCase() {
        assertTrue(SensitiveAppRules.looksSensitive(InstalledApp("kr.example.a", "우리 은행")))
        assertTrue(SensitiveAppRules.looksSensitive(InstalledApp("kr.example.b", "Naver PAY")))
        assertTrue(SensitiveAppRules.looksSensitive(InstalledApp("kr.example.c", "모바일 증권 앱")))
    }

    @Test fun helpableAndExcludedSplitTheListWithoutOverlap() {
        assertEquals(catalog.all.size, catalog.helpable.size + catalog.excluded.size)
        assertTrue(catalog.helpable.none { it.packageName in catalog.sensitive })
    }

    // --- 정렬·추천·검색 ---

    @Test fun listIsInKoreanOrder() {
        val hangul = catalog.all.map { it.label }.filter { it.first() in '가'..'힣' }
        assertEquals(hangul.sortedWith(java.text.Collator.getInstance(java.util.Locale.KOREAN)), hangul)
        assertEquals("가계부 메모", hangul.first())
    }

    @Test fun recommendedKeepsTheFixedOrderAndOnlyInstalledApps() {
        assertEquals(
            listOf("com.google.android.youtube", "com.kakao.talk", "com.coupang.mobile", "com.kakao.page"),
            catalog.recommended.map { it.packageName },
        )
    }

    @Test fun recommendedListHasNoPaymentLookingApp() =
        assertTrue(RecommendedApps.PACKAGES.none { SensitiveAppRules.looksSensitive(InstalledApp(it, "")) })

    @Test fun searchMatchesPartOfTheKoreanNameIgnoringSpaces() {
        assertEquals(listOf("카카오톡", "카카오페이지").sorted(), HelpAppsCatalog.search(catalog.all, "카카오").map { it.label }.sorted())
        assertEquals(listOf("카카오톡"), HelpAppsCatalog.search(catalog.all, "카카오 톡").map { it.label })
        assertEquals(listOf("YouTube"), HelpAppsCatalog.search(catalog.all, "youtube").map { it.label })
        assertEquals(catalog.all, HelpAppsCatalog.search(catalog.all, "  "))
        assertTrue(HelpAppsCatalog.search(catalog.all, "없는앱").isEmpty())
    }

    // --- 저장할 목록 ---

    @Test fun allModeTargetsHelpableAppsPlusAddedBack() {
        val draft = HelpAppsDraft(mode = HelpAppsMode.ALL, addedBack = setOf("com.viva.republica.toss", "kr.unlisted"))
        val target = HelpAppsPlan.target(draft, catalog)
        assertEquals(catalog.helpable.map { it.packageName }.toSet() + "com.viva.republica.toss", target)
        assertEquals(1, HelpAppsPlan.addedBackCount(draft, catalog))
    }

    @Test fun chosenModeTargetsOnlyListedTickedApps() {
        val draft = HelpAppsDraft(mode = HelpAppsMode.CHOSEN, chosen = setOf("com.kakao.talk", "kr.unlisted"))
        assertEquals(setOf("com.kakao.talk"), HelpAppsPlan.target(draft, catalog))
    }

    @Test fun changeRemovesOnlyListedUntickedAppsAndKeepsUnlistedOnes() {
        val allowed = setOf("com.kakao.talk", "com.coupang.mobile", "kr.typed.by.hand")
        val draft = HelpAppsDraft(mode = HelpAppsMode.CHOSEN, chosen = setOf("com.kakao.talk", "com.google.android.youtube"))
        val change = HelpAppsPlan.change(draft, catalog, allowed)
        assertEquals(setOf("com.google.android.youtube"), change.add)
        assertEquals(setOf("com.coupang.mobile"), change.remove)
    }

    @Test fun draftReopensWithWhatAIsAllowsAndWhatIsPending() {
        val draft = HelpAppsDraft.from(HelpAppsMode.CHOSEN, allowed = setOf("com.kakao.talk"), pending = setOf("com.coupang.mobile"))
        assertEquals(setOf("com.kakao.talk", "com.coupang.mobile"), HelpAppsPlan.target(draft, catalog))
        assertEquals(HelpAppsMode.ALL, HelpAppsDraft.from(null, emptySet(), emptySet()).mode)
    }

    // --- 동의 전 보류 → 동의 후 적용 ---

    @Test fun beforeConsentAdditionsWaitAndRemovalsHappen() {
        val a = FakeCore(consent = false, allowed = mutableSetOf("com.coupang.mobile"))
        val change = HelpAppsChange(add = setOf("com.kakao.talk", "com.google.android.youtube"), remove = setOf("com.coupang.mobile"))
        val result = HelpAppsApplier.apply(change, a)
        assertEquals(HelpAppsOutcome(removed = 1, waiting = 2), result.outcome)
        assertEquals(change.add, result.stillPending)
        assertTrue(a.allowed.isEmpty())
    }

    @Test fun afterConsentPendingIsAddedAndCleared() {
        val a = FakeCore(consent = false)
        val pending = HelpAppsApplier.apply(HelpAppsChange(setOf("com.kakao.talk", "bad name"), emptySet()), a).stillPending
        a.consent = true
        val result = HelpAppsApplier.applyPending(pending, a)
        assertEquals(HelpAppsOutcome(added = 1, failed = 1), result.outcome)
        assertTrue(result.stillPending.isEmpty())
        assertEquals(setOf("com.kakao.talk"), a.allowed)
    }

    @Test fun alreadyAllowedAppsAreNotAddedAgain() {
        val a = FakeCore(consent = true, allowed = mutableSetOf("com.kakao.talk"))
        val result = HelpAppsApplier.apply(HelpAppsChange(setOf("com.kakao.talk"), emptySet()), a)
        assertTrue(result.outcome.nothingChanged)
        assertEquals(0, a.addCalls)
    }

    @Test fun emptyListInAllModeChangesNothing() {
        val empty = HelpAppsCatalog.build(emptyList(), own)
        assertTrue(empty.isEmpty())
        val change = HelpAppsPlan.change(HelpAppsDraft(HelpAppsMode.ALL), empty, setOf("kr.typed.by.hand"))
        assertEquals(HelpAppsChange(emptySet(), emptySet()), change)
    }

    // --- 문구 ---

    @Test fun outcomeLinesInReadingOrder() {
        assertEquals(
            listOf(
                R.string.ui_help_apps_result_added to 3,
                R.string.ui_help_apps_result_removed to 1,
                R.string.ui_help_apps_result_failed to 2,
                R.string.ui_help_apps_result_waiting to 4,
            ),
            HelpAppsText.outcomeLines(HelpAppsOutcome(added = 3, failed = 2, removed = 1, waiting = 4)),
        )
        assertEquals(listOf(R.string.ui_help_apps_result_nothing to 0), HelpAppsText.outcomeLines(HelpAppsOutcome()))
        assertEquals(listOf(R.string.ui_help_apps_result_waiting to 5), HelpAppsText.outcomeLines(HelpAppsOutcome(waiting = 5)))
    }

    private val strings = java.io.File("src/main/res/values/ui_voice_strings.xml").readText()
    private fun text(name: String) = Regex("""<string name="$name">([^<]*)</string>""").find(strings)!!.groupValues[1]

    @Test fun countTextsTakeOneNumber() {
        assertEquals("앱 %1\$d개를 골랐어요.", text("ui_help_apps_chosen_count"))
        listOf("added", "failed", "removed", "waiting").forEach { assertTrue(it, text("ui_help_apps_result_$it").contains("%1\$d")) }
        assertTrue(text("ui_help_apps_all_summary").contains("%1\$d") && text("ui_help_apps_all_summary").contains("%2\$d"))
    }

    @Test fun paymentNoteSaysPaymentAndConsentAreAlwaysChecked() {
        val line = text("ui_help_apps_payment_note")
        assertTrue(line.contains("결제") && line.contains("동의") && line.contains("항상 직접 확인"))
    }

    @Test fun recommendedModeIsAllApps() = assertTrue(text("ui_help_apps_all").contains("추천"))

    @Test fun helpAppsModeIsSavedAndRestored() {
        val store = MapPreferencesForHelpApps()
        val saved = UiVoiceSettings(helpAppsMode = HelpAppsMode.CHOSEN, pendingHelpPackages = setOf("com.kakao.talk"))
        store.edit().also { UiVoicePreferences.writeTo(it, saved) }.apply()
        assertEquals(saved, UiVoicePreferences.readFrom(store))
        assertEquals(null, UiVoicePreferences.readFrom(MapPreferencesForHelpApps()).helpAppsMode)
    }

    /** Behaves like A's CorePreferences: no consent → false; malformed name → false. */
    private class FakeCore(var consent: Boolean, override val allowed: MutableSet<String> = mutableSetOf()) : AllowedAppsTarget {
        var addCalls = 0
        override val consentGranted get() = consent
        override fun add(packageName: String): Boolean {
            addCalls++
            if (!consent || !Regex("[A-Za-z][A-Za-z0-9_]*(\\.[A-Za-z][A-Za-z0-9_]*)+").matches(packageName)) return false
            allowed += packageName
            return true
        }
        override fun remove(packageName: String) {
            allowed -= packageName
        }
    }
}

/** Minimal SharedPreferences (the one in PresetStorageAndTextTest is private to that file). */
private class MapPreferencesForHelpApps : android.content.SharedPreferences {
    private val values = mutableMapOf<String, Any?>()
    override fun getAll(): Map<String, *> = values.toMap()
    override fun getString(key: String, defValue: String?) = values[key] as String? ?: defValue
    @Suppress("UNCHECKED_CAST")
    override fun getStringSet(key: String, defValues: Set<String>?) = values[key] as Set<String>? ?: defValues
    override fun getInt(key: String, defValue: Int) = values[key] as Int? ?: defValue
    override fun getLong(key: String, defValue: Long) = values[key] as Long? ?: defValue
    override fun getFloat(key: String, defValue: Float) = values[key] as Float? ?: defValue
    override fun getBoolean(key: String, defValue: Boolean) = values[key] as Boolean? ?: defValue
    override fun contains(key: String) = key in values
    override fun registerOnSharedPreferenceChangeListener(l: android.content.SharedPreferences.OnSharedPreferenceChangeListener) = Unit
    override fun unregisterOnSharedPreferenceChangeListener(l: android.content.SharedPreferences.OnSharedPreferenceChangeListener) = Unit
    override fun edit(): android.content.SharedPreferences.Editor = object : android.content.SharedPreferences.Editor {
        private val pending = mutableMapOf<String, Any?>()
        override fun putString(key: String, value: String?) = apply { pending[key] = value }
        override fun putStringSet(key: String, values: Set<String>?) = apply { pending[key] = values?.toSet() }
        override fun putInt(key: String, value: Int) = apply { pending[key] = value }
        override fun putLong(key: String, value: Long) = apply { pending[key] = value }
        override fun putFloat(key: String, value: Float) = apply { pending[key] = value }
        override fun putBoolean(key: String, value: Boolean) = apply { pending[key] = value }
        override fun remove(key: String) = apply { pending[key] = null }
        override fun clear() = apply { values.clear() }
        override fun commit(): Boolean {
            apply()
            return true
        }
        override fun apply() {
            values.putAll(pending)
        }
    }
}
