package kr.closeforyou.servicetest

/** Pure parser: consumes a transient dumpsys string, exposes only fixed status codes.
 * It never saves/prints service labels, packages, windows, or the raw dump. Unknown formats stay UNKNOWN. */
internal enum class FrameworkAccessibilityState { BOUND, BINDING, CRASHED, NOT_BOUND, UNKNOWN }

internal object FrameworkAccessibilitySummary {
    fun parse(dump: String): FrameworkAccessibilityState {
        if (Regex("(?im)^\\s*User state").findAll(dump).count() > 1) return FrameworkAccessibilityState.UNKNOWN
        fun section(name: String): String? {
            val marker = Regex("(?im)^\\s*${name} services\\s*[:=]\\s*").find(dump) ?: return null
            val begin = marker.range.last + 1
            if (begin >= dump.length || dump[begin] !in "{[") return null
            val stack = ArrayDeque<Char>()
            for (index in begin until dump.length) {
                when (val ch = dump[index]) {
                    '{', '[' -> stack.addLast(ch)
                    '}', ']' -> {
                        if (stack.isEmpty() || stack.removeLast() != if (ch == '}') '{' else '[') return null
                        if (stack.isEmpty()) return dump.substring(begin, index + 1)
                    }
                }
            }
            return null
        }
        val bound = section("Bound") ?: return FrameworkAccessibilityState.UNKNOWN
        val binding = section("Binding") ?: return FrameworkAccessibilityState.UNKNOWN
        val crashed = section("Crashed") ?: return FrameworkAccessibilityState.UNKNOWN
        val target = Regex("kr\\.closeforyou/(?:kr\\.closeforyou)?\\.accessibility\\.CloseForYouAccessibilityService(?=[\\s,}\\]]|$)")
        return when {
            target.containsMatchIn(crashed) -> FrameworkAccessibilityState.CRASHED
            target.containsMatchIn(bound) -> FrameworkAccessibilityState.BOUND
            target.containsMatchIn(binding) -> FrameworkAccessibilityState.BINDING
            else -> FrameworkAccessibilityState.NOT_BOUND
        }
    }
}
