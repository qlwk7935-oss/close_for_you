package kr.closeforyou.servicetest

internal enum class GeometryPhase { LAUNCH, STABLE }
internal enum class GeometryState { VALID, OUTSIDE, MIXED, INCONCLUSIVE }
internal enum class GeometryContrast { LAUNCH_ONLY, STABLE_OUTSIDE, STABLE_VALID, MIXED, INCONCLUSIVE }
internal enum class GeometryIssue {
    NONE, OPT_IN_REQUIRED, SESSION_REJECTED, HOST_UNAVAILABLE, WINDOW_MISMATCH,
    DISPLAY_CHANGED, CONFIGURATION_CHANGED, LAYOUT_PENDING, MINIMAL_TREE_REQUIRED,
    FRAME_UNAVAILABLE, TREE_LIMIT, ROOT_UNAVAILABLE, REMOTE_UNAVAILABLE,
    STABLE_UNOBSERVED, STABLE_GEOMETRY_CHANGED, OBSERVATION_TIMEOUT,
    INVALID_DIAGNOSTIC, OVERFLOW, UNOBSERVED, COLLECTION_BLOCKED, RESPONSE_REJECTED,
    UNSUPPORTED_GEOMETRY, RELEASE_FAILED, READ_CANCELLED, MAIN_FAILED, OBSERVATION_FAILED,
    SESSION_CLEANUP_FAILED, HOST_CLEANUP_FAILED, FLAGS_RESTORE_FAILED,
}
internal class GeometryDiagnosticFailure(val issue: GeometryIssue) : RuntimeException(issue.name)
internal data class GeometryServiceObservation(val issue: GeometryIssue, val geometry: GeometryState)

internal data class GeometryReadFailure(val inspection: GeometryIssue, val release: PriorityReleaseFailure)

/** Reader-lifetime latch. Keeps only the first paired failure codes, never an exception/resource.
 * The original inspection exception/cancellation still propagates. No reset/recovery API exists.
 */
internal class GeometryReadSafety {
    private var firstFailure: GeometryReadFailure? = null

    @Synchronized fun failure(): GeometryReadFailure? = firstFailure
    @Synchronized fun requireClean() {
        if (firstFailure != null) throw GeometryDiagnosticFailure(GeometryIssue.RELEASE_FAILED)
    }
    @Synchronized private fun remember(inspection: GeometryIssue, release: PriorityReleaseFailure) {
        if (release != PriorityReleaseFailure.NONE && firstFailure == null)
            firstFailure = GeometryReadFailure(inspection, release)
    }

    fun <T> inspect(read: (PriorityReleaseScope) -> T): T {
        requireClean()
        val releases = PriorityReleaseScope()
        var inspection = GeometryIssue.NONE
        try {
            return releases.protect { scope ->
                try { read(scope) }
                catch (cancelled: java.util.concurrent.CancellationException) {
                    inspection = GeometryIssue.READ_CANCELLED
                    throw cancelled
                } catch (failure: GeometryDiagnosticFailure) {
                    inspection = failure.issue
                    throw failure
                } catch (failure: Exception) {
                    inspection = GeometryIssue.OBSERVATION_FAILED
                    throw failure
                }
            }
        } finally {
            // Includes releases performed while the body was already failing. Does not throw
            // or replace that failure; the initial retry loop reads this independent latch.
            remember(inspection, releases.failure)
        }
    }

    override fun toString() = "GeometryReadSafety(codes_only)"
}

/** Diagnostic completeness only, never F0/F1/authorization. No source data is returned. */
internal object MinimalGeometryContract {
    fun assess(code: String?, attempts: List<String>, overflow: Boolean): GeometryServiceObservation {
        fun reject(issue: GeometryIssue) = GeometryServiceObservation(issue, GeometryState.INCONCLUSIVE)
        if (code != "OBSERVED") return reject(GeometryIssue.RESPONSE_REJECTED)
        if (overflow || attempts.size > 256) return reject(GeometryIssue.OVERFLOW)
        if (attempts.isEmpty()) return reject(GeometryIssue.UNOBSERVED)
        val ordinals = mutableSetOf<String>()
        val geometries = mutableSetOf<GeometryState>()
        for (encoded in attempts) {
            if (encoded.length > 2_048) return reject(GeometryIssue.INVALID_DIAGNOSTIC)
            val formatted = DetectionAttemptOutput.format(encoded)
            if (formatted == "ATTEMPT_DIAGNOSTIC_INVALID") return reject(GeometryIssue.INVALID_DIAGNOSTIC)
            val pairs = formatted.split(';').map { it.split('=', limit = 2) }
            if (pairs.any { it.size != 2 } || pairs.map { it[0] }.toSet().size != pairs.size)
                return reject(GeometryIssue.INVALID_DIAGNOSTIC)
            val row = pairs.associate { it[0] to it[1] }
            if (!ordinals.add(row.getValue("ATTEMPT"))) return reject(GeometryIssue.INVALID_DIAGNOSTIC)
            if (row["RESULT"] == "BLOCKED") return reject(GeometryIssue.COLLECTION_BLOCKED)
            if (row["FRAME_EXTENT"] != "POSITIVE" || row["WINDOW_EXTENT"] != "POSITIVE")
                return reject(GeometryIssue.UNSUPPORTED_GEOMETRY)
            val state = when (row["GEOMETRY"]) {
                "VALID" -> {
                    if (row["WINDOW_RELATION"] != "WITHIN_FRAME" || row["OUTSIDE_EDGES"] != "NONE")
                        return reject(GeometryIssue.INVALID_DIAGNOSTIC)
                    val consistent = when (row["DISPOSITION"]) {
                        "NO_POPUP" -> row["REASON"] in setOf("BASELINE_ONLY", "UNCHANGED", "ORDINARY_CONTENT_CHANGE")
                        "UNCERTAIN" -> row["REASON"] in setOf("UNCONFIRMED_TRANSITION", "UNCONFIRMED_STRUCTURE")
                        "POPUP_CANDIDATE" -> row["REASON"] in setOf("BOUNDED_WINDOW", "SEMANTIC_DIALOG", "PANE_WITH_UNDERLAY")
                        else -> false
                    }
                    if (!consistent) return reject(GeometryIssue.INVALID_DIAGNOSTIC)
                    GeometryState.VALID
                }
                "WINDOW_OUTSIDE_FRAME" -> {
                    if (row["WINDOW_RELATION"] != "WINDOW_OUTSIDE_FRAME" || row["OUTSIDE_EDGES"] in setOf(null, "NONE") ||
                        row["DISPOSITION"] != "UNCERTAIN" || row["REASON"] != "INVALID_GEOMETRY")
                        return reject(GeometryIssue.INVALID_DIAGNOSTIC)
                    GeometryState.OUTSIDE
                }
                else -> return reject(GeometryIssue.UNSUPPORTED_GEOMETRY)
            }
            geometries += state
        }
        return GeometryServiceObservation(GeometryIssue.NONE, geometries.singleOrNull() ?: GeometryState.MIXED)
    }

    fun requireComplete(observation: GeometryServiceObservation) {
        if (observation.issue != GeometryIssue.NONE) throw GeometryDiagnosticFailure(observation.issue)
        if (observation.geometry == GeometryState.INCONCLUSIVE) throw GeometryDiagnosticFailure(GeometryIssue.UNOBSERVED)
    }

    fun host(window: IsolationRect, frame: IsolationRect): GeometryState = when {
        !window.positive || !frame.positive -> GeometryState.INCONCLUSIVE
        window.outside(frame) == "NONE" -> GeometryState.VALID
        else -> GeometryState.OUTSIDE
    }

    /** Sources are classified separately; never use a host sample to fill a service row. */
    fun contrast(launch: GeometryState?, stable: GeometryState?): GeometryContrast = when {
        launch == null || stable == null || launch == GeometryState.INCONCLUSIVE || stable == GeometryState.INCONCLUSIVE -> GeometryContrast.INCONCLUSIVE
        launch == GeometryState.MIXED || stable == GeometryState.MIXED -> GeometryContrast.MIXED
        stable == GeometryState.OUTSIDE -> GeometryContrast.STABLE_OUTSIDE
        launch == GeometryState.OUTSIDE -> GeometryContrast.LAUNCH_ONLY
        else -> GeometryContrast.STABLE_VALID
    }

    fun completion(launch: GeometryServiceObservation?, stable: GeometryServiceObservation?,
        hostLaunch: GeometryState?, hostStable: GeometryState?, issue: GeometryIssue, cleanupOk: Boolean): String =
        if (issue == GeometryIssue.NONE && cleanupOk && launch?.issue == GeometryIssue.NONE && stable?.issue == GeometryIssue.NONE &&
            contrast(launch.geometry, stable.geometry) != GeometryContrast.INCONCLUSIVE &&
            contrast(hostLaunch, hostStable) != GeometryContrast.INCONCLUSIVE) "OBSERVATIONS_COMPLETE" else "INCONCLUSIVE"
}

/** Shared by real IPC instrumentation and deterministic JVM fake-clock tests.
 * A hard interrupt of non-cooperative framework/Binder calls is not provided. */
internal object MinimalGeometryPolling {
    /** Actual launch preparation loop, shared with JVM fake reads. A paired release failure
     * always ends this observation, even if the primary failure would normally allow retry. */
    suspend fun <T : Any> firstCoherent(now: () -> Long, safety: GeometryReadSafety,
        read: suspend () -> T, coherent: (T) -> Boolean, pause: suspend (Long) -> Unit): T {
        val start = now()
        while (now() - start < 3_000) {
            safety.requireClean()
            val sample = try { read() } catch (failure: GeometryDiagnosticFailure) {
                if (safety.failure() != null || failure.issue !in setOf(
                        GeometryIssue.REMOTE_UNAVAILABLE, GeometryIssue.ROOT_UNAVAILABLE, GeometryIssue.LAYOUT_PENDING)) throw failure
                null
            }
            safety.requireClean()
            if (now() - start >= 3_000) break
            if (sample != null && coherent(sample)) return sample
            pause(150)
        }
        throw GeometryDiagnosticFailure(GeometryIssue.STABLE_UNOBSERVED)
    }

    suspend fun await(now: () -> Long, read: suspend () -> GeometryServiceObservation,
        pause: suspend (Long) -> Unit): GeometryServiceObservation {
        val start = now()
        while (true) {
            if (now() - start >= 3_000) throw GeometryDiagnosticFailure(GeometryIssue.OBSERVATION_TIMEOUT)
            val observation = read()
            val elapsed = now() - start
            if (elapsed >= 3_000) throw GeometryDiagnosticFailure(GeometryIssue.OBSERVATION_TIMEOUT)
            if (observation.issue != GeometryIssue.NONE && observation.issue != GeometryIssue.UNOBSERVED)
                throw GeometryDiagnosticFailure(observation.issue)
            if (observation.issue == GeometryIssue.NONE && elapsed >= 2_000) {
                MinimalGeometryContract.requireComplete(observation)
                return observation
            }
            pause(200)
        }
    }

    /** Before/after service observation use this same exact-value gate. An anchor forbids
     * accepting a different geometry after a change and calling it the original stable phase. */
    suspend fun <T : Any> stable(now: () -> Long, read: suspend () -> T, ready: (T) -> Boolean,
        pause: suspend (Long) -> Unit, anchor: T? = null): T {
        val start = now()
        val gate = ObservedGeometryStability<T>()
        while (true) {
            if (now() - start >= 3_000) throw GeometryDiagnosticFailure(GeometryIssue.STABLE_UNOBSERVED)
            val sample = read()
            val time = now()
            if (time - start >= 3_000) throw GeometryDiagnosticFailure(GeometryIssue.STABLE_UNOBSERVED)
            if (anchor != null && (sample != anchor || !ready(sample)))
                throw GeometryDiagnosticFailure(GeometryIssue.STABLE_GEOMETRY_CHANGED)
            if (gate.observe(sample, ready(sample), time)) return sample
            pause(200)
        }
    }
}
