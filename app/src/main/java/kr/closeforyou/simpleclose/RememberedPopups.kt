package kr.closeforyou.simpleclose

import android.content.Context
import androidx.core.content.edit
import kr.closeforyou.core.model.CloseCandidate
import kr.closeforyou.core.model.ScreenBounds
import java.security.MessageDigest

/**
 * One "앞으로 항상 닫기" the person chose in the engine's prompt (F22, AGENTS 6-4): which exact popup (a hash of
 * the app, the popup's words and its structure; no text, no package is kept), where its root was, and which
 * button closed it (its path and place). Matching needs all of them again: the same popup in the same place.
 */
data class RememberedPopup(val key: String, val root: ScreenBounds, val buttonPath: String, val button: ScreenBounds) {
    fun encode(): String = listOf(root.left, root.top, root.right, root.bottom, button.left, button.top, button.right, button.bottom)
        .joinToString(",") + "|" + buttonPath

    companion object {
        fun keyOf(packageName: String, popup: SimplePopup): String = sha256(
            listOf(packageName, popup.root.kind.name, popup.snapshot.token.contentDigest, popup.snapshot.token.structureDigest).joinToString("\u0000"),
        )

        fun decode(key: String, value: String): RememberedPopup? {
            val (numbers, path) = value.split('|', limit = 2).takeIf { it.size == 2 } ?: return null
            val n = numbers.split(',').mapNotNull(String::toIntOrNull).takeIf { it.size == 8 } ?: return null
            return RememberedPopup(key, ScreenBounds(n[0], n[1], n[2], n[3]), path, ScreenBounds(n[4], n[5], n[6], n[7]))
        }

        private fun sha256(text: String): String =
            MessageDigest.getInstance("SHA-256").digest(text.toByteArray(Charsets.UTF_8)).joinToString("") { "%02x".format(it) }
    }
}

/** Where remembered popups live; [SharedPrefsRememberedPopups] on the device, a map in JVM tests. */
interface RememberedPopupStore {
    fun get(key: String): RememberedPopup?
    fun put(rule: RememberedPopup)
    fun all(): List<RememberedPopup>
    fun remove(key: String)
    fun clear()
}

/**
 * F22: matching a remembered popup on screen now. Pure; JVM tested. The button must be the same node again:
 * same path and same place inside the same root place; an X keeps being X-shaped ([ButtonScan.xButton]) or
 * the named close is still a close ([ButtonScan.candidates]). Anything else and the popup is asked as usual.
 */
object RememberedPopupRules {
    const val MAX_RULES = 50

    /**
     * B_DEMO_RULES.md 8-2: only a popup with words of its own beside its buttons can be remembered. An image-only popup
     * has one hash for every picture in that layout, so "the same popup" could not be told apart next time
     * ([kr.closeforyou.ui.voice.choice.RememberChoiceRules.offers] says the same for named buttons).
     */
    fun canRemember(popup: SimplePopup): Boolean {
        val buttons = popup.scan.candidates.mapNotNull { it.label }.toSet() + listOfNotNull(popup.scan.xButton?.label)
        return popup.analysis.texts.any { it.text.isNotBlank() && it.text !in buttons }
    }

    fun ruleFor(packageName: String, popup: SimplePopup, button: CloseCandidate): RememberedPopup? {
        val path = button.nodePath ?: return null
        return RememberedPopup(RememberedPopup.keyOf(packageName, popup), popup.root.bounds, path, button.bounds)
    }

    fun match(store: RememberedPopupStore, packageName: String, popup: SimplePopup): CloseCandidate? {
        val rule = store.get(RememberedPopup.keyOf(packageName, popup)) ?: return null
        if (rule.root != popup.root.bounds) return null
        return (popup.scan.candidates + listOfNotNull(popup.scan.xButton))
            .firstOrNull { it.nodePath == rule.buttonPath && it.bounds == rule.button }
    }
}

/** C's own small store (not D's Room): hashes and numbers only. Cleared from settings (F22 E) or with app data. */
class SharedPrefsRememberedPopups(context: Context) : RememberedPopupStore {
    private val prefs = context.applicationContext.getSharedPreferences(FILE, Context.MODE_PRIVATE)

    override fun get(key: String): RememberedPopup? = prefs.getString(key, null)?.let { RememberedPopup.decode(key, it) }

    override fun put(rule: RememberedPopup) {
        val keys = prefs.all.keys
        prefs.edit {
            if (rule.key !in keys && keys.size >= RememberedPopupRules.MAX_RULES) keys.firstOrNull()?.let(::remove)
            putString(rule.key, rule.encode())
        }
    }

    override fun all(): List<RememberedPopup> = prefs.all.mapNotNull { (key, value) -> (value as? String)?.let { RememberedPopup.decode(key, it) } }

    override fun remove(key: String) = prefs.edit { remove(key) }

    override fun clear() = prefs.edit { clear() }

    private companion object {
        const val FILE = "ui_simple_remembered_popups"
    }
}
