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
 * F18 (F5 video ads, YouTube app and web): the ad's own skip button, pressed only when its name says skip
 * ([SimpleLabelRules]: "건너뛰기", "광고 건너뛰기", "Skip", "Skip ad"; plus "Skip ads") **and** the screen
 * says ad: the name itself ("광고 건너뛰기", "Skip ad"), a skip-ad view id, or an ad mark elsewhere on the
 * screen ("광고", "Ad", "Sponsored", "스폰서"). A tutorial's "건너뛰기" without an ad mark is never pressed.
 *
 * Nothing else on a video screen is a candidate: 구독, 구매, links and every risky name are refused
 * ([SimpleLabelRules.isRisky]), and two skip buttons at once are a guess, so none. Waiting is event-driven:
 * the engine reads again on the player's own changes and its re-check chain, no polling of its own.
 * Pure Kotlin; JVM tested. Device unverified.
 */
object VideoAdSkip {
    private val EXTRA_SKIP = setOf("skip ads", "광고 건너뛰기 ›", "광고 건너뛰기 >")
    private val AD_MARKS_KO = listOf("광고", "스폰서")
    private val AD_MARKS_EN = setOf("ad", "ads", "sponsored")
    private val COUNTDOWN = listOf(
        Regex("""\d+\s*초\s*후\s*(광고\s*)?건너뛰"""),
        Regex("""건너뛰기\s*가능"""),
        Regex("""skip\s*(ad|ads)?\s*in\s*\d+"""),
    )
    private val NON_TOKEN = Regex("[^a-z0-9']+")

    fun find(nodes: List<NodeSnapshot>, screen: ScreenBounds): VideoAdSkipResult? {
        val visible = nodes.filter { PopupRootFinder.isVisible(it, screen) }
        val names = visible.associateWith { CloseButtonFinder.nameOf(it, visible) }
        val adOnScreen = visible.any { node -> listOfNotNull(node.text, node.contentDescription).any(::isAdMark) || isSkipAdId(node.viewId) }
        val skips = visible.filter { it.clickable && it.enabled && isSkip(names[it]) && (adOnScreen || isAdSkipName(names[it])) }
        skips.singleOrNull()?.let { node ->
            return VideoAdSkipResult.Found(
                CloseCandidate("node:${node.path}", node.path, node.viewId, names[node], node.bounds, EvidenceLevel.E2_LABELED_NODE, ButtonRole.SKIP),
            )
        }
        if (skips.isNotEmpty()) return null
        val countingDown = adOnScreen && visible.any { node ->
            listOfNotNull(node.text, node.contentDescription).any { text -> COUNTDOWN.any { it.containsMatchIn(ButtonLabelRules.normalize(text)) } }
        }
        return if (countingDown) VideoAdSkipResult.Waiting else null
    }

    /** A skip name and nothing risky in it. */
    fun isSkip(name: String?): Boolean {
        if (name.isNullOrBlank() || SimpleLabelRules.isRisky(name)) return false
        return SimpleLabelRules.roleOf(name) == ButtonRole.SKIP || ButtonLabelRules.normalize(name) in EXTRA_SKIP
    }

    private fun isAdSkipName(name: String?): Boolean = name != null && isAdMark(name)

    private fun isSkipAdId(viewId: String?): Boolean = viewId?.contains("skip_ad", ignoreCase = true) == true

    private fun isAdMark(text: String): Boolean {
        val normalized = ButtonLabelRules.normalize(text)
        return AD_MARKS_KO.any { it in normalized } || normalized.split(NON_TOKEN).any { it in AD_MARKS_EN }
    }
}
