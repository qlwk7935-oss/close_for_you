package kr.closeforyou.simpleclose

import kr.closeforyou.core.model.ButtonLabelRules
import kr.closeforyou.core.model.ButtonRole
import kr.closeforyou.core.model.CloseCandidate
import kr.closeforyou.core.model.EvidenceLevel
import kr.closeforyou.core.model.NodeSnapshot
import kr.closeforyou.core.model.ScreenBounds
import kr.closeforyou.decision.DecisionKeywords
import kr.closeforyou.decision.DecisionVideoAd

/** What [VideoAdSkip.find] saw on a video screen. */
sealed interface VideoAdSkipResult {
    /** One named skip button of an ad, ready to press. */
    data class Found(val candidate: CloseCandidate) : VideoAdSkipResult

    /** An ad is counting down to its skip button ("5초 후 건너뛰기"); read again when the screen changes. */
    data object Waiting : VideoAdSkipResult
}

/**
 * F20: why there is nothing to press (debug log `VIDEO_*`, a code only, never the words). [NONE]: no ad
 * mark and nothing like a skip button: not a video ad screen, not logged.
 */
enum class VideoAdDiag {
    FOUND, WAITING, NONE,
    /** An ad mark, but no skip-like node at all. */
    NO_CANDIDATE,
    /** A skip-like node only by its view id, with no name (and not clickable, or the id is not a skip-ad id). */
    LABEL_UNNAMED,
    /** A skip name, but neither the node nor anything it names is clickable and enabled. */
    NOT_CLICKABLE,
    /** A skip name, but the node is off screen or has no size. */
    OFFSCREEN,
    /** A skip name without any ad mark on screen (a tutorial's "건너뛰기"): never pressed. */
    NO_AD_MARKER,
    /** Two or more skip buttons: a guess, none pressed. */
    SEVERAL,
    /** F28: outside YouTube, an ad mark on screen but not in the skip's own group: never pressed. */
    AD_MARK_NOT_NEAR,
}

/** F34: a video ad's cover outside YouTube ([VideoAdSkip.cover]). */
sealed interface VideoCover {
    /** Its skip is shown: the cover is a popup root, asked like one (never skipped by itself here). */
    data class Shown(val root: PopupRoot) : VideoCover

    /** Still counting down to its skip: nothing to ask yet, read again on the player's next change. */
    data object Waiting : VideoCover
}

/** F36: why a screen with a video surface had no video ad cover (debug `VIDEO_COVER_MISS_*`). */
enum class VideoCoverMiss { NO_AD_MARK, SEVERAL_SKIPS, NO_SKIP_OR_COUNTDOWN, NO_HOLDER }

/** One read's verdict: [result] to act on (null = nothing), [diag] for the log, [adMarker] for the log too. */
class VideoAdScan(val result: VideoAdSkipResult?, val diag: VideoAdDiag, val adMarker: Boolean)

/**
 * F18 (F5 video ads, YouTube app and web): the ad's own skip button, pressed only when its name says skip
 * ([SimpleLabelRules]: "건너뛰기", "광고 건너뛰기", "Skip", "Skip ad"; plus "Skip ads") **and** the screen
 * says ad: the name itself ("광고 건너뛰기", "Skip ad"), a skip-ad view id, or an ad mark elsewhere on the
 * screen ("광고", "Ad", "Sponsored", "스폰서"). A tutorial's "건너뛰기" without an ad mark is never pressed.
 * F20: a clickable node whose view id says skip-ad (`skip_ad_button`; YouTube may give no text or
 * description) counts as named by that id: the developer's own name for it, never a guess from looks.
 *
 * Nothing else on a video screen is a candidate: 구독, 구매, links and every risky name are refused
 * ([SimpleLabelRules.isDecision]), and two skip buttons at once are a guess, so none. Waiting is event-driven:
 * the engine reads again on the player's own changes and its re-check chain, no polling of its own.
 * Pure Kotlin; JVM tested. Device unverified.
 */
object VideoAdSkip {
    const val YOUTUBE_APP = "com.google.android.youtube"

    private val EXTRA_SKIP = setOf("skip ads", "광고 건너뛰기 ›", "광고 건너뛰기 >")
    private val AD_MARKS_KO = listOf("광고", "스폰서")
    private val AD_MARKS_EN = setOf("ad", "ads", "sponsored")
    private val SKIP_AD_IDS = listOf("skip_ad", "ad_skip", "skip-ad", "ad-skip", "skipad")
    private val COUNTDOWN = listOf(
        Regex("""\d+\s*초\s*후\s*(광고\s*)?건너뛰"""),
        // F34 (시험 앱 P16 "3초 후 건너뛸 수 있어요"): "뛸" is not "뛰".
        Regex("""\d+\s*초\s*(후|뒤)\s*(광고\s*)?건너뛸"""),
        Regex("""건너뛰기\s*가능"""),
        Regex("""skip\s*(ad|ads)?\s*in\s*\d+"""),
    )
    private val NON_TOKEN = Regex("[^a-z0-9']+")
    private val AD_LIKE = Regex("광고\\s?성")

    fun find(nodes: List<NodeSnapshot>, screen: ScreenBounds, youtube: Boolean = true): VideoAdSkipResult? = scan(nodes, screen, youtube).result

    /** F28: a browser's address bar showing youtube.com (read in memory only). */
    fun isYouTubeWeb(nodes: List<NodeSnapshot>): Boolean =
        nodes.any { node -> node.viewId?.endsWith(":id/url_bar") == true && node.text?.contains("youtube.com", ignoreCase = true) == true }

    /**
     * F22 (LG 11/1: a shopping app's banner "AD" ran the video path 64 times): only the YouTube app, or a browser
     * showing youtube.com (its address bar, read in memory only), or a screen with a skip-ad view id.
     */
    fun isVideoContext(packageName: String, nodes: List<NodeSnapshot>): Boolean =
        packageName == YOUTUBE_APP || nodes.any { isSkipAdId(it.viewId) } ||
            isYouTubeWeb(nodes) ||
            // F23 (m.youtube.com with the address bar scrolled away): a clickable skip whose own name says ad ("광고 건너뛰기", "Skip ad").
            nodes.any { node -> node.clickable && CloseButtonFinder.nameOf(node, nodes).let { isSkip(it) && isAdSkipName(it) } }

    /**
     * [youtube] (F28, B_DEMO_RULES.md 4-1): the YouTube app or youtube.com, where an ad mark anywhere on screen
     * counts, as before. Elsewhere (a shopping list full of "광고" labels, a tutorial's "건너뛰기") the ad mark must be
     * in the skip's own group: the skip's name or view id, or a node under the skip's parent.
     */
    fun scan(nodes: List<NodeSnapshot>, screen: ScreenBounds, youtube: Boolean = true): VideoAdScan {
        val visible = nodes.filter { PopupRootFinder.isVisible(it, screen) }
        val names = visible.associateWith { CloseButtonFinder.nameOf(it, visible) }
        val adOnScreen = visible.any { node -> listOfNotNull(node.text, node.contentDescription).any(::isAdMark) || isSkipAdId(node.viewId) }
        val skipLike = visible.filter { it.clickable && it.enabled && (isSkip(names[it]) || isSkipAdId(it.viewId) && !SimpleLabelRules.isDecision(names[it])) }
        val skips = skipLike.filter { isAdSkipName(names[it]) || isSkipAdId(it.viewId) || adOnScreen && (youtube || adMarkNear(it, visible)) }
        if (skips.size == 1) {
            val node = skips.single()
            val candidate = CloseCandidate("node:${node.path}", node.path, node.viewId, names[node], node.bounds, EvidenceLevel.E2_LABELED_NODE, ButtonRole.SKIP)
            return VideoAdScan(VideoAdSkipResult.Found(candidate), VideoAdDiag.FOUND, adOnScreen)
        }
        if (skips.size > 1) return VideoAdScan(null, VideoAdDiag.SEVERAL, adOnScreen)
        if (skipLike.isNotEmpty()) return VideoAdScan(null, if (adOnScreen) VideoAdDiag.AD_MARK_NOT_NEAR else VideoAdDiag.NO_AD_MARKER, adOnScreen)
        val countingDown = adOnScreen && visible.any { node ->
            listOfNotNull(node.text, node.contentDescription).any { text -> COUNTDOWN.any { it.containsMatchIn(ButtonLabelRules.normalize(text)) } }
        }
        if (countingDown) return VideoAdScan(VideoAdSkipResult.Waiting, VideoAdDiag.WAITING, true)
        // Why not: a skip name somewhere, but off screen / not clickable; or a skip-ad id with no name.
        val namedAnywhere = nodes.filter { node -> isSkip(listOfNotNull(node.text, node.contentDescription).firstOrNull()) }
        val diag = when {
            namedAnywhere.any { !PopupRootFinder.isVisible(it, screen) } && namedAnywhere.none { PopupRootFinder.isVisible(it, screen) } -> VideoAdDiag.OFFSCREEN
            namedAnywhere.isNotEmpty() || visible.any { isSkip(names[it]) } -> VideoAdDiag.NOT_CLICKABLE
            nodes.any { isSkipAdId(it.viewId) } -> VideoAdDiag.LABEL_UNNAMED
            adOnScreen -> VideoAdDiag.NO_CANDIDATE
            else -> VideoAdDiag.NONE
        }
        return VideoAdScan(null, diag, adOnScreen)
    }

    /**
     * F34 (LG 11/2 시험 앱 P16 "영상 광고 건너뛰기 카운트다운", 정답 ASK, 우리 NONE): outside YouTube the skip path does
     * not run (F22/F28: an ad mark must be in the skip's own group). A cover that holds all three of
     * - a video surface (VideoView, SurfaceView, TextureView, or a view id saying video/player),
     * - an ad mark ("광고", "Ad", "스폰서"),
     * - the ad's skip (one named skip) or its countdown ("5초 후 건너뛸 수 있어요"),
     * is a popup (its own group, by construction): [VideoCover.Shown] with the skip, [VideoCover.Waiting] before it. The
     * cover is the deepest node holding them, not the tree root, at least [COVER_MIN_RATIO] of the screen. The engine asks
     * (the skip is a press option of the prompt); nothing here presses.
     */
    fun cover(nodes: List<NodeSnapshot>, screen: ScreenBounds): VideoCover? = coverCheck(nodes, screen).first

    /**
     * F36 (LG 11/2 P16: VIDEO_SKIP_WAITING ×3, then nothing): [cover] with why not ([VideoCoverMiss], debug
     * `VIDEO_COVER_MISS_*`); both null = no video surface on screen (not logged).
     */
    fun coverCheck(nodes: List<NodeSnapshot>, screen: ScreenBounds): Pair<VideoCover?, VideoCoverMiss?> {
        val visible = nodes.filter { PopupRootFinder.isVisible(it, screen) }
        val surface = visible.firstOrNull(::isVideoSurface) ?: return null to null
        val marks = visible.filter { node -> listOfNotNull(node.text, node.contentDescription).any(::isAdMark) }
        if (marks.isEmpty()) return null to VideoCoverMiss.NO_AD_MARK
        val skips = visible.filter { it.clickable && it.enabled && isSkip(CloseButtonFinder.nameOf(it, visible)) }
        if (skips.size > 1) return null to VideoCoverMiss.SEVERAL_SKIPS
        val countdown = visible.firstOrNull { node ->
            listOfNotNull(node.text, node.contentDescription).any { text -> COUNTDOWN.any { it.containsMatchIn(ButtonLabelRules.normalize(text)) } }
        }
        val anchor = skips.singleOrNull() ?: countdown ?: return null to VideoCoverMiss.NO_SKIP_OR_COUNTDOWN
        val byPath = nodes.associateBy { it.path }
        val screenArea = screen.width.toLong() * screen.height
        val holder = marks.mapNotNull { mark -> holding(byPath, listOf(surface, anchor, mark)) }
            .filter { it.path != "0" && it.bounds.width.toLong() * it.bounds.height >= screenArea * COVER_MIN_RATIO }
            .maxByOrNull { it.path.count { c -> c == '/' } } ?: return null to VideoCoverMiss.NO_HOLDER
        return (if (skips.size == 1) VideoCover.Shown(PopupRoot(holder.path, holder.bounds, PopupRootKind.VIDEO_COVER)) else VideoCover.Waiting) to null
    }

    private const val COVER_MIN_RATIO = 0.4

    private fun isVideoSurface(node: NodeSnapshot): Boolean {
        val cls = node.className.orEmpty()
        val id = node.viewId?.substringAfter(":id/")?.lowercase().orEmpty()
        return cls.endsWith("VideoView") || cls.endsWith("SurfaceView") || cls.endsWith("TextureView") || "video" in id || "player" in id
    }

    /** The deepest node in the tree whose path is a prefix of all of [members]' paths. */
    private fun holding(byPath: Map<String, NodeSnapshot>, members: List<NodeSnapshot>): NodeSnapshot? {
        var common = members.first().path.split('/')
        for (node in members.drop(1)) common = common.zip(node.path.split('/')).takeWhile { (a, b) -> a == b }.map { it.first }
        for (length in common.size downTo 1) {
            val node = byPath[common.take(length).joinToString("/")] ?: continue
            if (members.all { it.path == node.path || it.path.startsWith("${node.path}/") }) return node
        }
        return null
    }

    /**
     * B_DEMO_RULES.md 8-4: seconds until the skip button, for C to say once. Only a countdown actually read on screen
     * ([DecisionVideoAd.countdownIn]: skip wording and a time in one text, no decision word, 1~300 s); null when none was
     * read or two readings disagree. Never guessed or counted down by us.
     */
    fun waitSeconds(nodes: List<NodeSnapshot>, screen: ScreenBounds): Int? = nodes.asSequence()
        .filter { PopupRootFinder.isVisible(it, screen) }
        .flatMap { listOfNotNull(it.text, it.contentDescription) }
        .mapNotNull(DecisionVideoAd::countdownIn).distinct().toList().singleOrNull()

    /** A skip name and nothing risky in it. */
    fun isSkip(name: String?): Boolean {
        if (name.isNullOrBlank() || SimpleLabelRules.isDecision(name)) return false
        return SimpleLabelRules.roleOf(name) == ButtonRole.SKIP || ButtonLabelRules.normalize(name) in EXTRA_SKIP
    }

    private fun isAdSkipName(name: String?): Boolean = name != null && isAdMark(name)

    /** F28: an ad mark in the skip's own group: under the skip's parent (the skip's siblings and what they hold). */
    private fun adMarkNear(skip: NodeSnapshot, visible: List<NodeSnapshot>): Boolean {
        if ('/' !in skip.path) return false
        val parent = skip.path.substringBeforeLast('/')
        return visible.any { node ->
            node !== skip && node.path.startsWith("$parent/") && listOfNotNull(node.text, node.contentDescription).any(::isAdMark)
        }
    }

    /**
     * F28 (B_DEMO_RULES.md 4-1 (d)): outside YouTube, B's decision and scam words anywhere on the screen read
     * (B's wide forms, [DecisionKeywords]) keep the skip unpressed: a sign-up's "건너뛰기" next to a consent.
     */
    fun deniedByB(nodes: List<NodeSnapshot>, screen: ScreenBounds): Boolean =
        nodes.filter { PopupRootFinder.isVisible(it, screen) }
            .flatMap { listOfNotNull(it.text, it.contentDescription) }
            .filter { it.isNotBlank() }
            .any { DecisionKeywords.isSuspicious(it) || DecisionKeywords.isDecisionWide(it) }

    fun isSkipAdId(viewId: String?): Boolean {
        val id = viewId?.substringAfter(":id/")?.lowercase() ?: return false
        return SKIP_AD_IDS.any { it in id }
    }

    private fun isAdMark(text: String): Boolean {
        // F28: "광고성 정보 수신" (a consent to marketing) is not an ad's mark.
        val normalized = ButtonLabelRules.normalize(text).replace(AD_LIKE, " ")
        return AD_MARKS_KO.any { it in normalized } || normalized.split(NON_TOKEN).any { it in AD_MARKS_EN }
    }
}
