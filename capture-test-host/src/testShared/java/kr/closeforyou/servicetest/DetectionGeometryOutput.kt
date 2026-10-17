package kr.closeforyou.servicetest

/** F1 relation codes only. Does not parse or render any actual bounds. */
internal object DetectionGeometryOutput {
    val ages = setOf("UNOBSERVED", "UNSUPPORTED", "UNKNOWN", "WITHIN_ONE_SECOND", "OLDER", "READ_FAILED")
    private val results = setOf("VALID", "NONPOSITIVE_FRAME", "WINDOW_OUTSIDE_FRAME", "NONPOSITIVE_WINDOW")
    private val extents = setOf("POSITIVE", "NONPOSITIVE_WIDTH", "NONPOSITIVE_HEIGHT", "NONPOSITIVE_BOTH")
    private val edges = listOf("LEFT", "TOP", "RIGHT", "BOTTOM")
    fun format(fields: List<String>): String? {
        if (fields.size != 5 || fields[0] !in results || fields[1] !in extents || fields[2] !in extents ||
            fields[3] !in setOf("WITHIN_FRAME", "WINDOW_OUTSIDE_FRAME")) return null
        val outside = fields[4].split(',')
        if (fields[4] != "NONE" && (outside.any { it !in edges } || outside != edges.filter { it in outside })) return null
        return "GEOMETRY=${fields[0]};FRAME_EXTENT=${fields[1]};WINDOW_EXTENT=${fields[2]};WINDOW_RELATION=${fields[3]};OUTSIDE_EDGES=${fields[4]}"
    }
}
