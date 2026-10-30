package kr.closeforyou.accessibility.capture

import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.text.TextRecognition
import com.google.mlkit.vision.text.korean.KoreanTextRecognizerOptions
import kotlinx.coroutines.suspendCancellableCoroutine
import kr.closeforyou.core.model.ScreenBounds
import java.util.concurrent.Executor
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicReference
import kotlinx.coroutines.CancellationException
import kr.closeforyou.accessibility.CollectionResourceSafety
import kr.closeforyou.accessibility.CollectionReleaseBatch
import kr.closeforyou.accessibility.CollectionTaskLease
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

/** Bundled Korean model, no Play Services download or remote recognizer fallback. */
internal class KoreanMlKitOcr : OcrPort {
    override suspend fun recognize(frame: CaptureFrame): List<OcrLine> {
        val image = frame as? BitmapFrame ?: throw EvidenceFailure(EvidenceIssue.OCR_FAILED)
        val lease = image.retain()
        val recognizer = try {
            TextRecognition.getClient(KoreanTextRecognizerOptions.Builder().build())
        } catch (cancelled: CancellationException) {
            try { lease.close() } catch (_: Exception) { /* Preserve cancellation. */ }
            throw cancelled
        } catch (_: Exception) {
            try { lease.close() } catch (_: Exception) { /* Fixed failure below. */ }
            throw EvidenceFailure(EvidenceIssue.OCR_FAILED)
        }
        val closed = AtomicBoolean()
        val closeFailed = AtomicBoolean()
        fun closeRecognizer() {
            // A completion callback must wait for an already-started close, including its failure.
            synchronized(closed) {
                if (closed.compareAndSet(false, true)) try { recognizer.close() }
                catch (_: Exception) { closeFailed.set(true) }
            }
        }
        fun releaseCompletedTask() {
            val releases = CollectionReleaseBatch()
            releases.attempt { closeRecognizer(); if (closeFailed.get()) throw EvidenceFailure(EvidenceIssue.OCR_FAILED) }
            releases.attempt { lease.close() }
            releases.finish()
        }
        lateinit var lifetime: CollectionTaskLease
        lifetime = CollectionTaskLease {
            try { releaseCompletedTask() }
            finally { pendingTask.compareAndSet(lifetime, null) }
        }
        if (!pendingTask.compareAndSet(null, lifetime)) {
            try { releaseCompletedTask() } catch (_: Exception) { /* Fixed failure, no submission. */ }
            throw EvidenceFailure(EvidenceIssue.OCR_FAILED)
        }
        return suspendCancellableCoroutine { continuation ->
            continuation.invokeOnCancellation { closeRecognizer() }
            if (!continuation.isActive) {
                try { lifetime.submissionFailed() } catch (_: Exception) { /* No Task issued and no success. */ }
                return@suspendCancellableCoroutine
            }
            try {
                val input = InputImage.fromBitmap(image.pixels(), 0)
                if (!continuation.isActive) {
                    try { lifetime.submissionFailed() } catch (_: Exception) { /* No native Task issued. */ }
                    return@suspendCancellableCoroutine
                }
                lifetime.beforeSubmit()
                recognizer.process(input).addOnCompleteListener(
                    Executor { it.run() },
                ) { task ->
                    try {
                        val result = CollectionResourceSafety.run(body = {
                            if (!continuation.isActive) null else {
                                if (!task.isSuccessful) throw EvidenceFailure(EvidenceIssue.OCR_FAILED)
                                val lines = task.result.textBlocks.flatMap { it.lines }
                                if (lines.size > 200 || lines.sumOf { it.text.length } > 16_384)
                                    throw EvidenceFailure(EvidenceIssue.PRIVACY_UNCERTAIN)
                                lines.map { line ->
                                    val b = line.boundingBox ?: throw EvidenceFailure(EvidenceIssue.PRIVACY_UNCERTAIN)
                                    OcrLine(line.text, ScreenBounds(image.bounds.left + b.left, image.bounds.top + b.top,
                                        image.bounds.left + b.right, image.bounds.top + b.bottom), confidence = null)
                                }
                            }
                        }, cleanup = lifetime::completed, cleanupFailure = { throw EvidenceFailure(EvidenceIssue.OCR_FAILED) })
                        // Task is complete and resources released before any coroutine can consume text.
                        if (result != null && continuation.isActive) continuation.resume(result)
                    } catch (cancelled: CancellationException) {
                        continuation.cancel(cancelled)
                    } catch (failure: EvidenceFailure) {
                        if (continuation.isActive) continuation.resumeWithException(failure)
                    } catch (_: Exception) {
                        if (continuation.isActive) continuation.resumeWithException(EvidenceFailure(EvidenceIssue.OCR_FAILED))
                    }
                }
            } catch (cancelled: CancellationException) {
                closeRecognizer()
                try { lifetime.submissionFailed() } catch (_: Exception) { /* Preserve cancellation. */ }
                continuation.cancel(cancelled)
            } catch (_: Exception) {
                closeRecognizer()
                try { lifetime.submissionFailed() } catch (_: Exception) { /* Fixed failure below. */ }
                if (continuation.isActive) continuation.resumeWithException(EvidenceFailure(EvidenceIssue.OCR_FAILED))
            }
        }
    }

    companion object {
        // One bounded strong owner. If submission/listener registration throws after dispatch,
        // keep pixels leased: no callback means no proof that native readers finished.
        private val pendingTask = AtomicReference<CollectionTaskLease?>()
    }
}
