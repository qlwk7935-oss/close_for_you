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
    /** Every launchable app except our own, [SensitiveAppRules] matches and the person's exclusions. */
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
 * Always left out, in both ways (A, 10/24: "[모든 앱]이어도 항상 뺀다"); the list is only shown.
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
 * [PACKAGES] holds only ids we are sure of; apps whose id we are not sure of are matched by their
 * visible name ([NAMES]) instead, so a wrong id never silently drops or adds an app.
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
    )

    /** Visible names (spaces and case ignored, part match). 확인 필요: device labels may differ. */
    val NAMES: List<String> = listOf(
        "쿠팡이츠", "배달의민족", "요기요", "당근", "11번가", "G마켓", "알리익스프레스", "AliExpress",
        "테무", "Temu", "네이버웹툰", "카카오페이지",
    )
}

/** The picker's view of the device: sorted, de-duplicated, our own app removed. */
data class AppCatalog(
    /** Every launchable app, 가나다순. */
    val all: List<InstalledApp>,
    /** Package names [SensitiveAppRules] always leaves out, in both ways. */
    val sensitive: Set<String>,
    /** Installed [RecommendedApps] that are not [sensitive], in that list's order. */
    val recommended: List<InstalledApp>,
) {
    val helpable: List<InstalledApp> get() = all.filter { it.packageName !in sensitive }
    val excluded: List<InstalledApp> get() = all.filter { it.packageName in sensitive }
    val packages: Set<String> get() = all.mapTo(LinkedHashSet()) { it.packageName }
    val helpablePackages: Set<String> get() = helpable.mapTo(LinkedHashSet()) { it.packageName }
    fun isEmpty() = all.isEmpty()
}

object HelpAppsCatalog {
    private val collator: Collator = Collator.getInstance(Locale.KOREAN)

    /**
     * Several launcher activities of one package become one row; our own app is never listed.
     * A blank name becomes [unknownLabel], never the package name (10/30).
     */
    fun build(apps: List<InstalledApp>, ownPackage: String, unknownLabel: String = UNKNOWN_FALLBACK): AppCatalog {
        val all = apps
            .filter { it.packageName != ownPackage && it.packageName.isNotBlank() }
            .distinctBy { it.packageName }
            .map { if (it.label.isBlank()) it.copy(label = unknownLabel) else it.copy(label = it.label.trim()) }
            .sortedWith { a, b -> collator.compare(a.label, b.label).takeIf { it != 0 } ?: a.packageName.compareTo(b.packageName) }
        val sensitive = all.filter(SensitiveAppRules::looksSensitive).mapTo(LinkedHashSet()) { it.packageName }
        val byPackage = all.associateBy { it.packageName }
        val byId = RecommendedApps.PACKAGES.mapNotNull { byPackage[it] }
        val byName = RecommendedApps.NAMES.flatMap { name -> all.filter { normalize(name) in normalize(it.label) } }
        return AppCatalog(
            all = all,
            sensitive = sensitive,
            recommended = (byId + byName).distinctBy { it.packageName }.filter { it.packageName !in sensitive },
        )
    }

    /** Part of the app name, ignoring spaces and case ("카카오 톡" finds 카카오톡). Blank finds all. */
    fun search(apps: List<InstalledApp>, query: String): List<InstalledApp> {
        val wanted = normalize(query)
        if (wanted.isEmpty()) return apps
        return apps.filter { wanted in normalize(it.label) }
    }

    private fun normalize(text: String) = text.filterNot { it.isWhitespace() }.lowercase(Locale.KOREAN)

    /** Same words as R.string.ui_app_unknown, for JVM callers without resources. */
    const val UNKNOWN_FALLBACK = "알 수 없는 앱"
}

/**
 * The picker's unsaved state. [mode] starts null: nothing is chosen for the person (A, 10/24 —
 * analysing every app must never be on without the person picking it). [chosen] is used in
 * [HelpAppsMode.CHOSEN]; [userExcluded] holds the apps the person took out of [HelpAppsMode.ALL].
 */
data class HelpAppsDraft(
    val mode: HelpAppsMode? = null,
    val chosen: Set<String> = emptySet(),
    val userExcluded: Set<String> = emptySet(),
) {
    fun toggleChosen(packageName: String, on: Boolean) =
        copy(chosen = if (on) chosen + packageName else chosen - packageName)

    /** In [HelpAppsMode.ALL]: [helped] false takes the app out, true puts it back. */
    fun toggleHelpedInAll(packageName: String, helped: Boolean) =
        copy(userExcluded = if (helped) userExcluded - packageName else userExcluded + packageName)

    companion object {
        /**
         * Opening the picker again: the saved way, the saved exclusions, and what A already allows
         * (plus what still waits for consent) as ticked. Never saved before → no way chosen.
         * Unlisted packages may be in the sets; [HelpAppsPlan] only ever counts listed ones.
         */
        fun from(savedMode: HelpAppsMode?, savedExcluded: Set<String>, allowed: Set<String>, pending: Set<String>) =
            HelpAppsDraft(mode = savedMode, chosen = allowed + pending, userExcluded = savedExcluded)
    }
}

/** What saving a draft changes in A's list. */
data class HelpAppsChange(val add: Set<String>, val remove: Set<String>)

object HelpAppsPlan {
    /**
     * Apps that should be helped after saving [draft]. Only listed apps count, and
     * [AppCatalog.sensitive] apps are never in it, whichever way. No way chosen → nothing.
     */
    fun target(draft: HelpAppsDraft, catalog: AppCatalog): Set<String> = when (draft.mode) {
        HelpAppsMode.ALL -> catalog.helpablePackages - draft.userExcluded
        HelpAppsMode.CHOSEN -> draft.chosen intersect catalog.helpablePackages
        null -> emptySet()
    }

    /** Apps the person took out of [HelpAppsMode.ALL] (for "직접 뺀 앱 N개"). */
    fun userExcludedCount(draft: HelpAppsDraft, catalog: AppCatalog): Int =
        (draft.userExcluded intersect catalog.helpablePackages).size

    /** [저장]/[시작하기] only once a way is chosen. */
    fun canSave(draft: HelpAppsDraft, catalog: AppCatalog?): Boolean = draft.mode != null && catalog != null

    /**
     * Adds what is missing; removes listed apps that are not in the target (unticked, taken out,
     * or always-excluded). Packages A allows that the picker does not list (typed in A's debug
     * field, or hidden) are left alone.
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

/**
 * Settings' "도와줄 앱" block as one line (10/30: "38개 돕는 중" and "35개 넣었어요" side by side, with
 * the per-app auto-close switches below, read as "every app is off"). One number at most:
 * - [HelpAppsMode.ALL]: "모든 앱을 돕고 있어요" and how many apps are left out ([excludedCount],
 *   null while the app list loads);
 * - [HelpAppsMode.CHOSEN] (or no way saved but A already allows apps): how many apps are helped;
 * - [waiting]: some chosen apps still wait for A's collection consent ("준비 중", no number).
 */
data class HelpAppsStatusLine(val mode: HelpAppsMode?, val helpedCount: Int, val excludedCount: Int?, val waiting: Boolean)

object HelpAppsStatusPlan {
    fun line(
        mode: HelpAppsMode?,
        allowed: Set<String>,
        pending: Set<String>,
        userExcluded: Set<String>,
        catalog: AppCatalog?,
        consentGranted: Boolean,
    ): HelpAppsStatusLine {
        val waiting = !consentGranted && (pending - allowed).isNotEmpty()
        val shownMode = mode ?: HelpAppsMode.CHOSEN.takeIf { allowed.isNotEmpty() || pending.isNotEmpty() }
        val excluded = if (shownMode == HelpAppsMode.ALL) {
            catalog?.let { it.excluded.size + (userExcluded intersect it.helpablePackages).size }
        } else {
            null
        }
        return HelpAppsStatusLine(shownMode, (allowed + pending).size, excluded, waiting)
    }

    /** F19: no mode and no app: nothing is helped (the engine stays idle), so settings says so up front. */
    fun unset(line: HelpAppsStatusLine): Boolean = line.mode == null && line.helpedCount == 0
}

/**
 * "앞으로 항상 닫기를 허용할 앱": only apps where a switch can matter — apps with an "앞으로 항상 닫기"
 * popup remembered, plus apps already switched on — and only apps A still allows. Not every helped
 * app (that list read as "everything is off", 10/30).
 */
object AutoCloseApps {
    fun candidates(rememberedPackages: Collection<String>, autoClosePackages: Set<String>, allowed: Set<String>): Set<String> =
        (rememberedPackages.toSet() + autoClosePackages) intersect allowed
}
