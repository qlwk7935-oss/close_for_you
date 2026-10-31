package kr.closeforyou.simpleclose

import kr.closeforyou.core.model.ButtonLabelRules
import kr.closeforyou.core.model.ButtonRole
import kr.closeforyou.core.model.CloseCandidate
import kr.closeforyou.core.model.EvidenceLevel
import kr.closeforyou.core.model.NodeSnapshot
import kr.closeforyou.core.model.ScreenBounds

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
}

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
 * ([SimpleLabelRules.isRisky]), and two skip buttons at once are a guess, so none. Waiting is event-driven:
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
        Regex("""건너뛰기\s*가능"""),
        Regex("""skip\s*(ad|ads)?\s*in\s*\d+"""),
    )
    private val NON_TOKEN = Regex("[^a-z0-9']+")

    fun find(nodes: List<NodeSnapshot>, screen: ScreenBounds): VideoAdSkipResult? = scan(nodes, screen).result

    fun scan(nodes: List<NodeSnapshot>, screen: ScreenBounds): VideoAdScan {
        val visible = nodes.filter { PopupRootFinder.isVisible(it, screen) }
        val names = visible.associateWith { CloseButtonFinder.nameOf(it, visible) }
        val adOnScreen = visible.any { node -> listOfNotNull(node.text, node.contentDescription).any(::isAdMark) || isSkipAdId(node.viewId) }
        val skipLike = visible.filter { it.clickable && it.enabled && (isSkip(names[it]) || isSkipAdId(it.viewId) && !SimpleLabelRules.isRisky(names[it])) }
        val skips = skipLike.filter { adOnScreen || isAdSkipName(names[it]) || isSkipAdId(it.viewId) }
        if (skips.size == 1) {
            val node = skips.single()
            val candidate = CloseCandidate("node:${node.path}", node.path, node.viewId, names[node], node.bounds, EvidenceLevel.E2_LABELED_NODE, ButtonRole.SKIP)
            return VideoAdScan(VideoAdSkipResult.Found(candidate), VideoAdDiag.FOUND, adOnScreen)
        }
        if (skips.size > 1) return VideoAdScan(null, VideoAdDiag.SEVERAL, adOnScreen)
        if (skipLike.isNotEmpty()) return VideoAdScan(null, VideoAdDiag.NO_AD_MARKER, adOnScreen)
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

    /** A skip name and nothing risky in it. */
    fun isSkip(name: String?): Boolean {
        if (name.isNullOrBlank() || SimpleLabelRules.isRisky(name)) return false
        return SimpleLabelRules.roleOf(name) == ButtonRole.SKIP || ButtonLabelRules.normalize(name) in EXTRA_SKIP
    }

    private fun isAdSkipName(name: String?): Boolean = name != null && isAdMark(name)

    fun isSkipAdId(viewId: String?): Boolean {
        val id = viewId?.substringAfter(":id/")?.lowercase() ?: return false
        return SKIP_AD_IDS.any { it in id }
    }

    private fun isAdMark(text: String): Boolean {
        val normalized = ButtonLabelRules.normalize(text)
        return AD_MARKS_KO.any { it in normalized } || normalized.split(NON_TOKEN).any { it in AD_MARKS_EN }
    }
}
