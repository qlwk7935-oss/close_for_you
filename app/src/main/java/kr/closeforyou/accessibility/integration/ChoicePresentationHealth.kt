package kr.closeforyou.accessibility.integration

import java.util.concurrent.atomic.AtomicBoolean

/** #29 sticky uncertainty, without a reset API within this service session. */
internal class ChoicePresentationHealth {
    private val uncertain = AtomicBoolean()
    val failed: Boolean get() = uncertain.get()
    fun fail() { uncertain.set(true) }
    override fun toString() = "ChoicePresentationHealth(redacted)"
}
