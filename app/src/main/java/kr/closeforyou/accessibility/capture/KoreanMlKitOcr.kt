package kr.closeforyou.accessibility.capture

import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.text.TextRecognition
import com.google.mlkit.vision.text.korean.KoreanTextRecognizerOptions
import kotlinx.coroutines.suspendCancellableCoroutine
import kr.closeforyou.core.model.ScreenBounds
import java.util.concurrent.Executor
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

/** Bundled Korean model, no Play Services download or remote recognizer fallback. */
internal class KoreanMlKitOcr : OcrPort {
    override suspend fun recognize(frame: CaptureFrame): List<OcrLine> {
        val image = frame as? BitmapFrame ?: throw EvidenceFailure(EvidenceIssue.OCR_FAILED)
        val lease = image.retain()
        val recognizer = try {
            TextRecognition.getClient(KoreanTextRecognizerOptions.Builder().build())
        } catch (_: Exception) {
            lease.close()
            throw EvidenceFailure(EvidenceIssue.OCR_FAILED)
        }
        val closed = AtomicBoolean()
        fun closeRecognizer() { if (closed.compareAndSet(false, true)) runCatching { recognizer.close() } }
        return suspendCancellableCoroutine { continuation ->
            continuation.invokeOnCancellation { closeRecognizer() }
            try {
                recognizer.process(InputImage.fromBitmap(image.pixels(), 0)).addOnCompleteListener(
                    Executor { it.run() },
                ) { task ->
                    try {
                        if (!continuation.isActive) return@addOnCompleteListener
                        if (!task.isSuccessful) throw EvidenceFailure(EvidenceIssue.OCR_FAILED)
                        val lines = task.result.textBlocks.flatMap { it.lines }
                        if (lines.size > 200 || lines.sumOf { it.text.length } > 16_384) {
                            throw EvidenceFailure(EvidenceIssue.PRIVACY_UNCERTAIN)
                        }
                        val result = lines.map { line ->
                            val b = line.boundingBox ?: throw EvidenceFailure(EvidenceIssue.PRIVACY_UNCERTAIN)
                            OcrLine(line.text, ScreenBounds(image.bounds.left + b.left, image.bounds.top + b.top,
                                image.bounds.left + b.right, image.bounds.top + b.bottom), confidence = null)
                        }
                        continuation.resume(result)
                    } catch (failure: EvidenceFailure) {
                        if (continuation.isActive) continuation.resumeWithException(failure)
                    } catch (_: Exception) {
                        if (continuation.isActive) continuation.resumeWithException(EvidenceFailure(EvidenceIssue.OCR_FAILED))
                    } finally {
                        closeRecognizer()
                        lease.close()
                    }
                }
            } catch (_: Exception) {
                closeRecognizer()
                lease.close()
                if (continuation.isActive) continuation.resumeWithException(EvidenceFailure(EvidenceIssue.OCR_FAILED))
            }
        }
    }
}
