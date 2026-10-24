package kr.closeforyou.ui.voice.settings

import java.text.Collator
import java.util.Locale

/**
 * "도와줄 앱" (first-run step and settings): which apps A may analyse. C never owns that list; it
 * only calls A's public `CorePreferences.addAllowedPackage` / `removeAllowedPackage` through
 * [AllowedAppsTarget]. Everything here is pure and JVM-tested. App names and package names are
 * never logged (CLAUDE.local.md 3-3).
 */
enum class HelpAppsMode {
    /** Every launchable app except our own and [SensitiveAppRules] matches (unless added back). */
    ALL,
    /** Only the apps the person ticked. */
    CHOSEN,
}

/** One launchable app as the picker shows it. [label] is the visible app name. */
data class InstalledApp(val packageName: String, val label: String)

/** Where the launchable apps come from (Android: launcher activities, see LauncherAppSource). */
fun interface InstalledAppSource {
    fun launchableApps(): List<InstalledApp>
}

/**
 * Apps that look like banking, securities, payment, card, simple-pay or password-manager apps.
 * Excluded from [HelpAppsMode.ALL] by default; the person may add one back.
 *
 * Android's `ApplicationInfo.category` has no finance or payment category (only game, audio,
 * video, image, social, news, maps, productivity, accessibility), so it cannot help here; the
 * decision is the fixed keyword lists below. They lean towards over-matching: a wrongly excluded
 * app is one tick away, a missed bank app would be analysed without the person asking.
 */
object SensitiveAppRules {
    /** Lower-case substrings of the package name. */
    val PACKAGE_KEYWORDS: List<String> = listOf(
        "bank", "banking", "pay", "card", "wallet", "finance", "fintech", "invest", "stock",
        "securities", "trade", "trading", "crypto", "coin", "upbit", "bithumb", "insur",
        "toss", "viva.republica", "kbstar", "kbank", "shinhan", "woori", "hana", "nonghyup", ".nh.",
        "ibk", "kakaobank", "kakao.bank", "kiwoom", "miraeasset", "truefriend", "samsungpop",
        "kftc", "yessign", "crosscert", "signkorea", "smartotp", "tauth", "ktauth",
        "password", "keepass", "1password", "lastpass", "bitwarden", "dashlane", "keeper", "authenticator",
    )

    /** Substrings of the visible app name, compared without spaces and ignoring case. */
    val NAME_KEYWORDS: List<String> = listOf(
        "은행", "뱅크", "뱅킹", "증권", "투자", "주식", "카드", "결제", "송금", "지갑", "월렛",
        "금융", "저축", "캐피탈", "보험", "코인", "거래소", "토스", "인증서", "비밀번호", "패스워드",
        "bank", "pay", "wallet", "card", "otp", "password",
    )

    /** "페이" (pay) but not "페이스북" or "페이지". */
    private val PAY_IN_KOREAN = Regex("페이(?![스지])")

    fun looksSensitive(app: InstalledApp): Boolean {
        val pkg = app.packageName.lowercase(Locale.ROOT)
        val name = app.label.filterNot { it.isWhitespace() }.lowercase(Locale.ROOT)
        return PACKAGE_KEYWORDS.any { it in pkg } ||
            NAME_KEYWORDS.any { it in name } ||
            PAY_IN_KOREAN.containsMatchIn(name)
    }
}

/**
 * Apps with many ad popups, shown first under "추천" when installed. Order = shown order.
 * Package names are the public store ids; the person should double-check the list (report).
 */
object RecommendedApps {
    val PACKAGES: List<String> = listOf(
        "com.google.android.youtube", // 유튜브
        "com.kakao.talk", // 카카오톡
        "com.coupang.mobile", // 쿠팡
        "com.nhn.android.search", // 네이버
        "com.instagram.android", // 인스타그램
        "com.zhiliaoapp.musically", // 틱톡
        "com.ss.android.ugc.trill", // 틱톡 (다른 지역판)
        "com.coupang.mobile.eats", // 쿠팡이츠
        "com.sampleapp", // 배달의민족
        "com.fineapp.yogiyo", // 요기요
        "com.towneers.www", // 당근
        "com.elevenst", // 11번가
        "com.ebay.kr.gmarket", // G마켓
        "com.alibaba.aliexpresshd", // 알리익스프레스
        "com.einnovation.temu", // 테무
        "com.nhn.android.webtoon", // 네이버 웹툰
        "com.kakao.page", // 카카오페이지
    )
}

/** The picker's view of the device: sorted, de-duplicated, our own app removed. */
data class AppCatalog(
    /** Every launchable app, 가나다순. */
    val all: List<InstalledApp>,
    /** Package names [SensitiveAppRules] excludes from [HelpAppsMode.ALL]. */
    val sensitive: Set<String>,
    /** Installed [RecommendedApps], in that list's order. */
    val recommended: List<InstalledApp>,
) {
    val helpable: List<InstalledApp> get() = all.filter { it.packageName !in sensitive }
    val excluded: List<InstalledApp> get() = all.filter { it.packageName in sensitive }
    val packages: Set<String> get() = all.mapTo(LinkedHashSet()) { it.packageName }
    fun isEmpty() = all.isEmpty()
}

object HelpAppsCatalog {
    private val collator: Collator = Collator.getInstance(Locale.KOREAN)

    /** Several launcher activities of one package become one row; our own app is never listed. */
    fun build(apps: List<InstalledApp>, ownPackage: String): AppCatalog {
        val all = apps
            .filter { it.packageName != ownPackage && it.packageName.isNotBlank() }
            .distinctBy { it.packageName }
            .map { if (it.label.isBlank()) it.copy(label = it.packageName) else it.copy(label = it.label.trim()) }
            .sortedWith { a, b -> collator.compare(a.label, b.label).takeIf { it != 0 } ?: a.packageName.compareTo(b.packageName) }
        val byPackage = all.associateBy { it.packageName }
        return AppCatalog(
            all = all,
            sensitive = all.filter(SensitiveAppRules::looksSensitive).mapTo(LinkedHashSet()) { it.packageName },
            recommended = RecommendedApps.PACKAGES.mapNotNull { byPackage[it] },
        )
    }

    /** Part of the app name, ignoring spaces and case ("카카오 톡" finds 카카오톡). Blank finds all. */
    fun search(apps: List<InstalledApp>, query: String): List<InstalledApp> {
        val wanted = normalize(query)
        if (wanted.isEmpty()) return apps
        return apps.filter { wanted in normalize(it.label) }
    }

    private fun normalize(text: String) = text.filterNot { it.isWhitespace() }.lowercase(Locale.KOREAN)
}

/**
 * The picker's unsaved state. [chosen] is used in [HelpAppsMode.CHOSEN]; [addedBack] holds the
 * excluded apps the person added back in [HelpAppsMode.ALL].
 */
data class HelpAppsDraft(
    val mode: HelpAppsMode = HelpAppsMode.ALL,
    val chosen: Set<String> = emptySet(),
    val addedBack: Set<String> = emptySet(),
) {
    fun toggleChosen(packageName: String, on: Boolean) =
        copy(chosen = if (on) chosen + packageName else chosen - packageName)

    fun toggleAddedBack(packageName: String, on: Boolean) =
        copy(addedBack = if (on) addedBack + packageName else addedBack - packageName)

    companion object {
        /**
         * Opening the picker again shows what A already allows (plus what still waits for consent)
         * as ticked. The first time ([savedMode] null) starts at [HelpAppsMode.ALL] (recommended).
         * Unlisted packages may be in the sets; [HelpAppsPlan] only ever counts listed ones.
         */
        fun from(savedMode: HelpAppsMode?, allowed: Set<String>, pending: Set<String>): HelpAppsDraft {
            val on = allowed + pending
            return HelpAppsDraft(mode = savedMode ?: HelpAppsMode.ALL, chosen = on, addedBack = on)
        }
    }
}

/** What saving a draft changes in A's list. */
data class HelpAppsChange(val add: Set<String>, val remove: Set<String>)

object HelpAppsPlan {
    /** Apps that should be helped after saving [draft]. Only listed apps count. */
    fun target(draft: HelpAppsDraft, catalog: AppCatalog): Set<String> = when (draft.mode) {
        HelpAppsMode.ALL -> catalog.helpable.mapTo(LinkedHashSet()) { it.packageName } +
            (draft.addedBack intersect catalog.sensitive)
        HelpAppsMode.CHOSEN -> draft.chosen intersect catalog.packages
    }

    /** Excluded apps added back in [HelpAppsMode.ALL] (for "뺀 앱 중 N개는 다시 넣었어요"). */
    fun addedBackCount(draft: HelpAppsDraft, catalog: AppCatalog): Int = (draft.addedBack intersect catalog.sensitive).size

    /**
     * Adds what is missing; removes only listed apps the person left unticked. Packages A allows
     * that the picker does not list (typed in A's debug field, or hidden) are left alone.
     */
    fun change(draft: HelpAppsDraft, catalog: AppCatalog, allowed: Set<String>): HelpAppsChange {
        val target = target(draft, catalog)
        return HelpAppsChange(
            add = target - allowed,
            remove = (allowed intersect catalog.packages) - target,
        )
    }
}

/** A's allowed-app list as C sees it. Android: [CoreAllowedApps] over `CorePreferences`. */
interface AllowedAppsTarget {
    val consentGranted: Boolean
    val allowed: Set<String>
    /** A's `addAllowedPackage`: false before collection consent or for a malformed name. */
    fun add(packageName: String): Boolean
    fun remove(packageName: String)
}

/** Counts only, so the result line can be shown and tested without app names. */
data class HelpAppsOutcome(
    val added: Int = 0,
    val failed: Int = 0,
    val removed: Int = 0,
    /** Still waiting for A's collection consent (kept in C settings). */
    val waiting: Int = 0,
) {
    val nothingChanged: Boolean get() = added == 0 && failed == 0 && removed == 0 && waiting == 0
}

/**
 * Applies a change through A. Removals happen at once (A allows them without consent). Additions
 * need consent: before it, they are returned as `stillPending` for C to keep and retry after
 * consent (or when settings open). After consent every addition is tried once; one A refuses is
 * counted as failed and dropped, because trying the same name again would fail the same way.
 */
object HelpAppsApplier {
    data class Result(val outcome: HelpAppsOutcome, val stillPending: Set<String>)

    fun apply(change: HelpAppsChange, target: AllowedAppsTarget): Result {
        var removed = 0
        change.remove.forEach {
            if (it in target.allowed) removed++
            target.remove(it)
        }
        val adds = change.add - target.allowed
        if (!target.consentGranted) {
            return Result(HelpAppsOutcome(removed = removed, waiting = adds.size), stillPending = adds)
        }
        var added = 0
        var failed = 0
        adds.forEach { if (target.add(it)) added++ else failed++ }
        return Result(HelpAppsOutcome(added = added, failed = failed, removed = removed), stillPending = emptySet())
    }

    /** The saved pending additions, retried (e.g. right after A's consent). */
    fun applyPending(pending: Set<String>, target: AllowedAppsTarget): Result =
        apply(HelpAppsChange(add = pending, remove = emptySet()), target)
}
