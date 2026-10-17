package kr.closeforyou.accessibility

import kr.closeforyou.core.model.PrivacyBlockReason
import kr.closeforyou.core.model.ScreenBounds
import org.junit.Assert.*
import org.junit.Test
import java.security.NoSuchAlgorithmException

class CollectionProgressTest {
    @Test fun `all classified exceptions rethrow unchanged without exposing their message`() {
        val cases = listOf(
            SecurityException("private") to CollectionExceptionKind.SECURITY,
            IllegalArgumentException("private") to CollectionExceptionKind.ILLEGAL_ARGUMENT,
            IllegalStateException("private") to CollectionExceptionKind.ILLEGAL_STATE,
            IndexOutOfBoundsException("private") to CollectionExceptionKind.INDEX_OUT_OF_BOUNDS,
            UnsupportedOperationException("private") to CollectionExceptionKind.UNSUPPORTED_OPERATION,
            NullPointerException("private") to CollectionExceptionKind.NULL_POINTER,
            ClassCastException("private") to CollectionExceptionKind.CLASS_CAST,
            NoSuchAlgorithmException("private") to CollectionExceptionKind.DIGEST_UNAVAILABLE,
            Exception("private") to CollectionExceptionKind.OTHER_EXCEPTION,
        )
        cases.forEach { (exception, kind) ->
            val progress = CollectionProgress()
            val thrown = assertThrows(Exception::class.java) {
                progress.at(CollectionOperation.NODE_ACTION_READ) { throw exception }
            }
            assertSame(exception, thrown)
            assertEquals(CollectionFailureLocation(CollectionOperation.NODE_ACTION_READ, CollectionField.NONE, kind), progress.failure)
            assertFalse(progress.failure.toString().contains("private"))
        }
    }
    @Test fun `innermost failing boundary and field survive outer propagation`() {
        val progress = CollectionProgress()
        assertThrows(IllegalStateException::class.java) {
            progress.at(CollectionOperation.NODE_DTO) {
                progress.at(CollectionOperation.NODE_PROPERTIES_READ, CollectionField.CLASS_NAME) { throw IllegalStateException("private") }
            }
        }
        assertEquals(CollectionOperation.NODE_PROPERTIES_READ, progress.failure?.operation)
        assertEquals(CollectionField.CLASS_NAME, progress.failure?.field)
    }
    @Test fun `real bounds constructor rejection is distinguished from a node getter exception`() {
        val progress = CollectionProgress()
        assertThrows(IllegalArgumentException::class.java) {
            progress.at(CollectionOperation.NODE_BOUNDS_DTO) { ScreenBounds(2, 0, 1, 1) }
        }
        assertEquals(CollectionOperation.NODE_BOUNDS_DTO, progress.failure?.operation)
        assertEquals(CollectionExceptionKind.ILLEGAL_ARGUMENT, progress.failure?.exception)
        // Demonstrates classification only: no evidence that this caused the API 37 device failure.
    }
    @Test fun `protection failures retain their reason and are not runtime exceptions in diagnostics`() {
        val progress = CollectionProgress()
        val failure = assertThrows(CollectionGuardFailure::class.java) {
            progress.at(CollectionOperation.NODE_FIELD_CHECK) { CollectionProtectionCheck.field(true, false, 0) }
        }
        assertEquals(PrivacyBlockReason.PASSWORD, failure.reason)
        assertEquals(CollectionExceptionKind.NONE, progress.failure?.exception)
        assertEquals(CollectionOperation.NODE_FIELD_CHECK, progress.failure?.operation)
    }
    @Test fun `successful boundaries do not leave false failure state`() {
        val progress = CollectionProgress()
        CollectionOperation.entries.forEach { operation -> assertEquals(7, progress.at(operation) { 7 }) }
        assertNull(progress.failure)
    }
}
