package kr.closeforyou.accessibility

/** Optional #29 fixed-code diagnostics never affect admission or retain a thrown payload. */
internal inline fun diagnosticWrite(write: () -> Unit) {
    try { write() } catch (_: Exception) { /* No screen data or exception logging. */ }
}
