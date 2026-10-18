package kr.closeforyou.accessibility

import java.util.concurrent.CancellationException
import kotlinx.collections.immutable.toPersistentList
import kotlinx.collections.immutable.toPersistentSet

internal enum class AttemptState {
    STARTED, SCOPE_FIXED, PROTECTION_REVIEWED, TARGET_RECORDED, FINAL_REVIEWED, CONSUMED, DISCARDED,
}
internal enum class AttemptFailure {
    INVALID_ORDER, TERMINATED, SCOPE_INVALID, SCOPE_CHANGED, DUPLICATE_ROOT, PROTECTION_INCOMPLETE,
    MIXED_ATTEMPT, RECEIPT_INVALID, TARGET_INCOMPLETE, PROJECTION_REJECTED, REVIEW_FAILED,
    CANCELLED, INVALIDATED, DISCARDED,
}
internal enum class SyntheticConsumption { REVIEW_CONSUMED, REJECTED }

internal data class ProtectionRootKey(val windowId: Int, val role: EvidenceTreeRole) {
    override fun toString(): String = "ProtectionRootKey(redacted)"
}

/** Opaque SYNTHETIC handles. Neither is a runtime authorization or Android F0 evidence. */
internal sealed interface SyntheticProtectionReceipt
internal sealed interface SyntheticReviewCompletion

/** Owner/state-machine contract experiment ONLY. No Android reader or production factory exists.
 * Synthetic flags describe test observations, never actual F0. Even a fully reviewed attempt has
 * productionReady=false; consume returns a fixed code, NEVER a snapshot/frame/action capability.
 * Identity and issued-object membership prevent mixing/replay; UUIDs/DTO equality are not authority.
 * Monitor synchronization orders this object's transitions, not Android events or screen changes.
 */
internal class CollectionAttempt private constructor() {
    private val identity = Any()
    private var phase = AttemptState.STARTED
    private var failure: AttemptFailure? = null
    private var projectionFailure: ProjectionRejection? = null
    private var scope: List<ProtectionRootKey> = emptyList()
    private val issued = mutableMapOf<Int, OwnedReceipt>()
    private var projected: ProjectionOutcome.StructuralMatch? = null
    private var completion: OwnedCompletion? = null

    private class OwnedReceipt(val owner: Any, val observation: ProtectionRootEvidence) : SyntheticProtectionReceipt {
        override fun toString(): String = "SyntheticProtectionReceipt(redacted)"
    }
    private class OwnedCompletion(val owner: Any) : SyntheticReviewCompletion {
        override fun toString(): String = "SyntheticReviewCompletion(redacted)"
    }

    val productionReady: Boolean get() = false
    @get:Synchronized val state: AttemptState get() = phase
    @get:Synchronized val firstFailure: AttemptFailure? get() = failure
    @get:Synchronized val firstProjectionFailure: ProjectionRejection? get() = projectionFailure

    /** Caller-supplied synthetic roster; NOT actual Android scope discovery. Copied once, <=32. */
    @Synchronized fun fixSyntheticScope(roots: List<ProtectionRootKey>): Boolean {
        if (!at(AttemptState.STARTED)) return false
        val fixed = boundedCopy(roots) ?: return false
        if (!at(AttemptState.STARTED)) return false
        if (fixed.isEmpty() || fixed.map { it.windowId }.toSet().size != fixed.size ||
            fixed.count { it.role == EvidenceTreeRole.TARGET } != 1) return reject(AttemptFailure.SCOPE_INVALID)
        scope = fixed
        phase = AttemptState.SCOPE_FIXED
        return true
    }

    /** Reviews a synthetic completion observation. Does not perform or attest real F0 checks. */
    @Synchronized fun reviewSyntheticRoot(observation: ProtectionRootEvidence): SyntheticProtectionReceipt? {
        if (!at(AttemptState.SCOPE_FIXED)) return null
        if (ProtectionRootKey(observation.windowId, observation.role) !in scope) {
            reject(AttemptFailure.SCOPE_CHANGED); return null
        }
        if (observation.windowId in issued) { reject(AttemptFailure.DUPLICATE_ROOT); return null }
        if (observation.protection != ProtectionCompletion.COMPLETED_WITHOUT_BLOCK ||
            observation.traversal != TreeCompletion.COMPLETE) {
            reject(AttemptFailure.PROTECTION_INCOMPLETE); return null
        }
        return OwnedReceipt(identity, observation).also { issued[observation.windowId] = it }
    }

    @Synchronized fun finishSyntheticProtection(receipts: List<SyntheticProtectionReceipt>): Boolean {
        if (!at(AttemptState.SCOPE_FIXED)) return false
        val fixed = boundedCopy(receipts) ?: return false
        if (!at(AttemptState.SCOPE_FIXED)) return false
        for (receipt in fixed) {
            if (receipt !is OwnedReceipt) return reject(AttemptFailure.RECEIPT_INVALID)
            if (receipt.owner !== identity) return reject(AttemptFailure.MIXED_ATTEMPT)
            if (issued[receipt.observation.windowId] !== receipt) return reject(AttemptFailure.RECEIPT_INVALID)
        }
        if (fixed.size != scope.size || issued.size != scope.size || fixed.toSet().size != fixed.size)
            return reject(AttemptFailure.SCOPE_INVALID)
        phase = AttemptState.PROTECTION_REVIEWED
        return true
    }

    @Synchronized fun recordSyntheticTarget(target: CollectedRootEvidence, metadata: CollectionProjectionMetadata): Boolean {
        if (!at(AttemptState.PROTECTION_REVIEWED)) return false
        val expected = scope.single { it.role == EvidenceTreeRole.TARGET }
        if (target.role != EvidenceTreeRole.TARGET || target.windowId != expected.windowId ||
            target.traversal != TreeCompletion.COMPLETE || target.nodes.isEmpty())
            return reject(AttemptFailure.TARGET_INCOMPLETE)
        // Data is immutable; receipts/roster come from this owner, never supplied alongside the target.
        // The flags here summarize accepted SYNTHETIC observations only; no real proof is minted.
        val evidence = CollectedTreeEvidence(scope.map { it.windowId }.toPersistentSet(),
            scope.map { issued.getValue(it.windowId).observation }.toPersistentList(), target,
            ProtectionCompletion.COMPLETED_WITHOUT_BLOCK, TreeCompletion.COMPLETE)
        val result = try {
            CollectionProjection.project(evidence, metadata)
        } catch (cancelled: CancellationException) {
            reject(AttemptFailure.CANCELLED)
            throw CancellationException("COLLECTION_REVIEW_CANCELLED")
        } catch (_: Exception) {
            reject(AttemptFailure.REVIEW_FAILED)
            return false
        }
        if (!at(AttemptState.PROTECTION_REVIEWED)) return false
        if (result is ProjectionOutcome.Rejected) {
            if (projectionFailure == null) projectionFailure = result.reason
            return reject(AttemptFailure.PROJECTION_REJECTED)
        }
        projected = result as ProjectionOutcome.StructuralMatch
        phase = AttemptState.TARGET_RECORDED
        return true
    }

    @Synchronized fun finishSyntheticReview(): SyntheticReviewCompletion? {
        if (!at(AttemptState.TARGET_RECORDED)) return null
        if (projected == null || issued.size != scope.size) { reject(AttemptFailure.TARGET_INCOMPLETE); return null }
        return OwnedCompletion(identity).also {
            completion = it
            phase = AttemptState.FINAL_REVIEWED
        }
    }

    @Synchronized fun consumeSyntheticReview(handle: SyntheticReviewCompletion): SyntheticConsumption {
        if (!at(AttemptState.FINAL_REVIEWED)) return SyntheticConsumption.REJECTED
        if (handle !is OwnedCompletion || handle.owner !== identity || handle !== completion) {
            reject(AttemptFailure.MIXED_ATTEMPT)
            return SyntheticConsumption.REJECTED
        }
        phase = AttemptState.CONSUMED
        clearReferences()
        return SyntheticConsumption.REVIEW_CONSUMED
    }

    @Synchronized fun cancel() { reject(AttemptFailure.CANCELLED) }
    @Synchronized fun invalidate() { reject(AttemptFailure.INVALIDATED) }
    @Synchronized fun discard() { reject(AttemptFailure.DISCARDED) }

    private fun at(expected: AttemptState): Boolean {
        if (phase == expected) return true
        return reject(if (phase == AttemptState.CONSUMED || phase == AttemptState.DISCARDED)
            AttemptFailure.TERMINATED else AttemptFailure.INVALID_ORDER)
    }
    /** At most32 reads; validate the same copied observations even if a caller mutates its list. */
    private fun <T> boundedCopy(input: List<T>): List<T>? = try {
        val size = input.size
        if (size !in 0..CollectionProjectionLimits.MAX_WINDOWS) {
            reject(AttemptFailure.SCOPE_INVALID); null
        } else {
            val fixed = List(size) { input[it] }
            if (input.size != size) { reject(AttemptFailure.SCOPE_CHANGED); null } else fixed
        }
    } catch (cancelled: CancellationException) {
        reject(AttemptFailure.CANCELLED)
        throw CancellationException("COLLECTION_REVIEW_CANCELLED")
    } catch (_: Exception) {
        reject(AttemptFailure.REVIEW_FAILED)
        null
    }
    private fun reject(reason: AttemptFailure): Boolean {
        if (failure == null) failure = reason
        if (phase != AttemptState.CONSUMED) phase = AttemptState.DISCARDED
        clearReferences()
        return false
    }
    private fun clearReferences() {
        scope = emptyList()
        issued.clear()
        projected = null
        completion = null
    }
    override fun toString(): String = "CollectionAttempt(SYNTHETIC_ONLY, redacted)"

    companion object {
        /** The only factory. Cannot select PRODUCTION, import identity or set state/completion. */
        fun syntheticReview(): CollectionAttempt = CollectionAttempt()
    }
}
