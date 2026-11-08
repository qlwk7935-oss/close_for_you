package kr.closeforyou.ui.voice

/**
 * C/F10: turns a popup's own text into what the TTS should read, without changing its meaning.
 *
 * Only symbols and formats that TTS engines read badly are rewritten ("02:00~04:00" was read as
 * "공둘 공공 물결표 공넷" on the demo app). Words are never summarized, reordered or reworded, and
 * plain numbers ("12,900원") are left to the engine, which reads them well. What is on screen does
 * not change: this is for speech only. Input and output are screen text: never log either.
 *
 * Rules, in this order (each line is handled on its own, then lines are joined with a pause):
 * 1. web and e-mail addresses → "웹 주소" / "이메일 주소"
 * 2. date and time ranges → "…부터 …까지"; one side only ("~10/31", "10/28~") → "…까지" / "…부터"
 * 3. dates ("2026.10.28", "10/28(수)") and clock times ("02:00", "오후 2:30") → "10월 28일 수요일", "오전 2시"
 * 4. weekday ranges ("월~금") and number ranges ("1~3일") → "월요일부터 금요일까지", "1에서 3일"
 * 5. "70%" → "70퍼센트", "1+1" → "원 플러스 원", "2×3" → "2 곱하기 3", "2x" → "2배"
 * 6. "&" → "그리고" ("앤" between Latin letters: "H&M"), "#태그 #태그" → ", 태그, 태그"
 * 7. "/" and "·" between words → ", "; "기간:" → "기간, "
 * 8. decorative marks and emoji (★ ▶ ※ ♥ 【】 …), a stray "~" or "×", and private-use icon glyphs ([PrivateUse], F62) are dropped
 * 9. spaces collapsed; every line but the last ends with a pause (".")
 *
 * Clock times need evidence: a two-digit hour ("02:00", "14:30"), 오전/오후/AM/PM, a range, or a
 * following "부터/까지/에". "2:15" alone stays as it is, because it may be a video position.
 * 24-hour reading: when an hour is 0, 13–23 or written with a leading zero, every time in that
 * expression gets 오전/오후 ("02:00" → "오전 2시", "18:00" → "오후 6시", 12 → "낮 12시");
 * otherwise only "N시" ("10:30" → "10시 30분").
 * Dates need evidence too: a year, a weekday, or "/" with a two-digit month or day ("10/28", not
 * "1/2"). "4.5" alone is a number, not a date.
 */
object SpeakableText {

    /** The text to give the TTS; blank when nothing readable is left (only emoji, for example). */
    fun of(raw: String): String =
        raw.lines().map(::line).filter { it.isNotEmpty() }.let { lines ->
            lines.mapIndexed { i, l -> if (i < lines.lastIndex) withPause(l) else l }.joinToString("\n")
        }

    /** One line for a short title ("'제목' 광고를 닫았어요"): lines joined with a space, no pause added. */
    fun inline(raw: String): String = raw.lines().map(::line).filter { it.isNotEmpty() }.joinToString(" ")

    /** [text] ending in a sentence pause, so a following sentence is not run into it. */
    fun withPause(text: String): String =
        if (text.isEmpty() || text.last() in PAUSE_ENDINGS) text else "$text."

    private fun line(raw: String): String {
        var s = raw
        s = replaceKeepingParticle(s, URL, Words.WEB_ADDRESS)
        s = replaceKeepingParticle(s, EMAIL, Words.EMAIL_ADDRESS)
        s = POINT_RANGE.replace(s, ::pointRange)
        s = OPEN_START.replace(s) { m -> openRange(m, m.groupValues[1], Words.UNTIL, m.groupValues[2]) }
        s = OPEN_END.replace(s) { m -> openRange(m, m.groupValues[1], Words.FROM, "") }
        s = POINT.replace(s, ::singlePoint)
        s = WEEKDAY_RANGE.replace(s) { m ->
            "${m.groupValues[1]}${Words.WEEKDAY_SUFFIX}${Words.FROM} ${m.groupValues[2]}${Words.WEEKDAY_SUFFIX}${Words.UNTIL}"
        }
        s = NUMBER_RANGE.replace(s) { m -> "${m.groupValues[1]}${Words.NUMBER_FROM} ${m.groupValues[2]}" }
        s = PERCENT.replace(s) { m -> "${m.groupValues[1]}${Words.PERCENT}" }
        s = SMALL_PLUS.replace(s) { m ->
            "${Words.ENGLISH_DIGITS[m.groupValues[1].toInt()]} ${Words.PLUS} ${Words.ENGLISH_DIGITS[m.groupValues[2].toInt()]}"
        }
        s = PLUS.replace(s, " ${Words.PLUS} ")
        s = TIMES.replace(s) { m -> "${m.groupValues[1]} ${Words.TIMES} ${m.groupValues[2]}" }
        s = MULTIPLE.replace(s) { m -> "${m.groupValues[1]}${Words.MULTIPLE}" }
        s = LATIN_AND.replace(s) { m -> "${m.groupValues[1]} ${Words.LATIN_AND} ${m.groupValues[2]}" }
        s = AND.replace(s, " ${Words.AND} ")
        s = HASHTAGS.replace(s) { m -> ", " + TAG.findAll(m.value).joinToString(", ") { it.groupValues[1] } + " " }
        s = NUMBER_TAG.replace(s, "")
        s = WORD_SLASH.replace(s, ", ")
        s = WORD_DOT.replace(s, ", ")
        s = LABEL_COLON.replace(s, ", ")
        s = REPEATED_MARK.replace(s) { m -> m.value.first().toString() }
        s = dropDecorations(s)
        return tidy(s)
    }

    /** A particle right after the address stays attached: "…com에서" → "웹 주소에서". */
    private fun replaceKeepingParticle(s: String, pattern: Regex, words: String): String =
        pattern.replace(s) { m -> " $words" + if (s.getOrNull(m.range.last + 1)?.let { it in '가'..'힣' } == true) "" else " " }

    // ---- dates and times ----

    private class Point(
        val year: Int?, val month: Int?, val day: Int?, val weekday: String?,
        val meridiem: String?, val hour: Int?, val minute: Int?,
        val dateSep: Char?, val dayDigits: Int, val monthDigits: Int, val hourDigits: Int,
    ) {
        val hasDate get() = month != null
        val hasTime get() = hour != null

        /** Hour 0, 13–23 or a leading zero ("02") can only be a 24-hour clock. */
        val clock24 get() = hour != null && meridiem == null && (hour == 0 || hour >= 13 || (hourDigits == 2 && hour < 10))

        fun valid(): Boolean {
            if (month != null && (month !in 1..12 || day == null || day !in 1..31)) return false
            if (dateSep == '-' && year == null) return false
            if (hour != null && (hour > 24 || minute == null || minute > 59 || (hour == 24 && minute != 0))) return false
            if (hour != null && meridiem != null && hour > 12) return false
            return month != null || hour != null
        }

        /** Evidence enough to be a date or time on its own (outside a range). */
        fun standalone(followedByMarker: Boolean): Boolean {
            if (!valid()) return false
            val dateOk = !hasDate || year != null || weekday != null ||
                (dateSep == '/' && (dayDigits == 2 || monthDigits == 2))
            val timeOk = !hasTime || hasDate || meridiem != null || hourDigits == 2 || followedByMarker
            return dateOk && timeOk
        }

        fun spoken(use24: Boolean): String = buildList {
            if (month != null) {
                if (year != null) add("$year${Words.YEAR}")
                add("$month${Words.MONTH}")
                add("$day${Words.DAY}")
                if (weekday != null) add("$weekday${Words.WEEKDAY_SUFFIX}")
            }
            if (hour != null) {
                val m = minute ?: 0
                when {
                    meridiem != null -> add(Words.meridiemOf(meridiem) + " $hour${Words.HOUR}")
                    use24 -> add(clock24Words(hour))
                    else -> add("$hour${Words.HOUR}")
                }
                if (m > 0) add("$m${Words.MINUTE}")
            }
        }.joinToString(" ")

        private fun clock24Words(h: Int): String = when (h) {
            in 0..11 -> "${Words.AM} $h${Words.HOUR}"
            12 -> "${Words.NOON} 12${Words.HOUR}"
            24 -> "24${Words.HOUR}"
            else -> "${Words.PM} ${h - 12}${Words.HOUR}"
        }
    }

    private fun parse(text: String): Point? {
        val m = POINT_PARTS.matchEntire(text.trim()) ?: return null
        val g = m.groups
        val month = g[2]?.value
        val hour = g[7]?.value
        return Point(
            year = g[1]?.value?.toInt(),
            month = month?.toInt(),
            day = g[4]?.value?.toInt(),
            weekday = g[5]?.value,
            meridiem = g[6]?.value,
            hour = hour?.toInt(),
            minute = g[8]?.value?.toInt(),
            dateSep = g[3]?.value?.first(),
            dayDigits = g[4]?.value?.length ?: 0,
            monthDigits = month?.length ?: 0,
            hourDigits = hour?.length ?: 0,
        ).takeIf { it.valid() }
    }

    private fun pointRange(m: MatchResult): String {
        val a = parse(m.groupValues[1]) ?: return m.value
        val b = parse(m.groupValues[3]) ?: return m.value
        val dash = m.groupValues[2].trim() in DASHES
        // A dash is a range only between times ("09:00-18:00"); "10-28" stays.
        if (dash && !(a.hasTime && b.hasTime)) return m.value
        // "1.5~2.5" is a number range: a dotted date needs a two-digit day or a weekday.
        if (!rangeDateOk(a) || !rangeDateOk(b)) return m.value
        val use24 = a.clock24 || b.clock24
        val until = m.groupValues[4].ifEmpty { Words.UNTIL }
        return "${a.spoken(use24)}${Words.FROM} ${b.spoken(use24)}${until.trim()}"
    }

    private fun rangeDateOk(p: Point) =
        !p.hasDate || p.year != null || p.weekday != null || p.dateSep == '/' || p.dayDigits == 2

    private fun openRange(m: MatchResult, pointText: String, marker: String, tail: String): String {
        val p = parse(pointText) ?: return m.value
        if (!rangeDateOk(p)) return m.value
        val lead = m.value.takeWhile { it == ' ' }
        return "$lead${p.spoken(p.clock24)}${tail.ifEmpty { marker }.trim()} "
    }

    /** Group 2 (inside a lookahead, so not replaced) is a following "부터/까지/에/~". */
    private fun singlePoint(m: MatchResult): String {
        val p = parse(m.groupValues[1]) ?: return m.value
        return if (p.standalone(followedByMarker = m.groupValues[2].isNotEmpty())) p.spoken(p.clock24) else m.value
    }

    // ---- decorations and tidying ----

    private fun dropDecorations(s: String): String {
        val out = StringBuilder(s.length)
        var i = 0
        while (i < s.length) {
            val cp = s.codePointAt(i)
            i += Character.charCount(cp)
            if (isDecoration(cp)) out.append(' ') else out.appendCodePoint(cp)
        }
        return out.toString()
    }

    private fun isDecoration(cp: Int): Boolean =
        cp in DECORATION_POINTS || DECORATION_RANGES.any { cp in it } || PrivateUse.isPrivateUse(cp)

    private fun tidy(s: String): String = s
        .replace(SPACES, " ")
        .replace(SPACE_BEFORE_PUNCT, "$1")
        .replace(REPEATED_COMMA, ",")
        .trim()
        .trimStart(',', ' ')
        .trimEnd(',', ' ')

    /**
     * The words the rules insert. They are Korean reading units for the TTS, not UI sentences, and
     * each belongs to the rule beside it (a translation would need different rules too), so they
     * live here as a table instead of in ui_voice_strings.xml.
     */
    internal object Words {
        const val WEB_ADDRESS = "웹 주소"
        const val EMAIL_ADDRESS = "이메일 주소"
        const val FROM = "부터"
        const val UNTIL = "까지"
        const val NUMBER_FROM = "에서"
        const val YEAR = "년"
        const val MONTH = "월"
        const val DAY = "일"
        const val WEEKDAY_SUFFIX = "요일"
        const val HOUR = "시"
        const val MINUTE = "분"
        const val AM = "오전"
        const val PM = "오후"
        const val NOON = "낮"
        const val PERCENT = "퍼센트"
        const val PLUS = "플러스"
        const val TIMES = "곱하기"
        const val MULTIPLE = "배"
        const val AND = "그리고"
        const val LATIN_AND = "앤"

        /** "1+1" is said "원 플러스 원" in Korean shops, not "일 더하기 일". Index = digit. */
        val ENGLISH_DIGITS = listOf("", "원", "투", "쓰리", "포", "파이브", "식스", "세븐", "에잇", "나인")

        fun meridiemOf(written: String): String = when (written.lowercase().replace(".", "")) {
            "오후", "pm" -> PM
            else -> AM
        }
    }

    // ---- patterns ----

    private val PAUSE_ENDINGS = setOf('.', '!', '?', '。', '…', ',', ':', ';')
    private val DASHES = setOf("-", "–")

    /** Stops at Korean, so "www.example.com에서" keeps "에서". */
    private val URL = Regex("""(?i)(?:https?://|www\.)[^\s가-힣]+""")
    private val EMAIL = Regex("""[\w.+-]+@[\w-]+(?:\.[\w-]+)+""")

    private const val WEEKDAYS = "월화수목금토일"
    private const val DATE = """(?:\d{4}[./-])?\d{1,2}[./-]\d{1,2}(?:\s*\([$WEEKDAYS](?:요일)?\))?"""
    private const val TIME = """(?:(?:오전|오후|[AaPp]\.?[Mm]\.?)\s*)?\d{1,2}:\d{2}"""
    private const val POINT_TEXT = """(?:$DATE(?:\s+$TIME)?|$TIME)"""

    /** Start and end guards: not inside a longer number, time ("1:05:30") or decimal. */
    private const val BEFORE = """(?<![\d./:\-])"""
    private const val AFTER = """(?![\d:]|\.\d)"""

    private val POINT_PARTS = Regex(
        """(?:(?:(\d{4})[./-])?(\d{1,2})([./-])(\d{1,2})(?:\s*\(([$WEEKDAYS])(?:요일)?\))?)?\s*""" +
            """(?:(오전|오후|[AaPp]\.?[Mm]\.?)?\s*(\d{1,2}):(\d{2}))?""",
    )
    private val POINT_RANGE = Regex("""$BEFORE($POINT_TEXT)$AFTER(\s*[~∼〜\-–]\s*)($POINT_TEXT)$AFTER(\s*까지)?""")
    private val OPEN_START = Regex("""(?<![\d)])\s*[~∼〜]\s*($POINT_TEXT)$AFTER(\s*까지)?""")
    private val OPEN_END = Regex("""$BEFORE($POINT_TEXT)$AFTER\s*[~∼〜](?!\s*\d)(\s*부터)?""")
    /** "0:15 / 1:30" (a video position and length) is left alone. */
    private val POINT = Regex("""$BEFORE($POINT_TEXT)$AFTER(?!\s*/\s*\d)(?=(\s*(?:부터|까지|에|~))?)""")

    private val WEEKDAY_RANGE = Regex("""(?<![가-힣])([$WEEKDAYS])(?:요일)?\s*[~∼〜\-]\s*([$WEEKDAYS])(?:요일)?(?![가-힣])""")
    private val NUMBER_RANGE = Regex("""(\d[\d,.]*)\s*[~∼〜]\s*(\d)""")

    private val PERCENT = Regex("""(\d)\s*[%％]""")
    private val SMALL_PLUS = Regex("""(?<![\d+])([1-9])\s*\+\s*([1-9])(?![\d+])""")
    private val PLUS = Regex("""(?<=[\d가-힣])\s*\+\s*(?=\d)""")
    private val TIMES = Regex("""(\d)\s*[×✕✖]\s*(\d)""")
    private val MULTIPLE = Regex("""(\d)\s*[×xX](?=\s|$|[가-힣])""")
    private val LATIN_AND = Regex("""([A-Za-z])&([A-Za-z])""")
    private val AND = Regex("""\s*&\s*""")
    private val HASHTAGS = Regex("""(?<![\w&])(?:#[^\s#\d][^\s#]*\s*)+""")
    private val TAG = Regex("""#([^\s#]+)""")
    private val NUMBER_TAG = Regex("""(?<![\w&])#(?=\d)""")
    private val WORD_SLASH = Regex("""(?<=[가-힣A-Za-z)])\s*/\s*(?=[가-힣A-Za-z(])""")
    private val WORD_DOT = Regex("""(?<=[가-힣A-Za-z0-9)])\s*[·ㆍ∙•]\s*(?=[가-힣A-Za-z0-9(])""")
    private val LABEL_COLON = Regex("""(?<=[가-힣A-Za-z)\]])\s*:\s*""")
    private val REPEATED_MARK = Regex("""([!?])\1+""")

    private val SPACES = Regex("""\s+""")
    private val SPACE_BEFORE_PUNCT = Regex("""\s+([,.!?])""")
    private val REPEATED_COMMA = Regex(""",(\s*,)+""")

    /** Single marks read aloud as their names ("별표", "물결표") or noise; a stray "~"/"×" too. */
    private val DECORATION_POINTS: Set<Int> = (
        "~∼〜×✕✖※·ㆍ∙•|‖〓" + "【】「」『』〈〉《》" + "‍️⃣"
        ).codePoints().toArray().toSet()

    /** Arrows, geometric shapes (▶ ● ■ ▲), misc symbols and dingbats (★ ♥ ♪ ✔ ❤), emoji. */
    private val DECORATION_RANGES = listOf(
        0x2190..0x21FF, 0x2500..0x25FF, 0x2600..0x27BF, 0x2B00..0x2BFF, 0x1F000..0x1FAFF,
    )
}
