package kr.closeforyou.servicetest

import org.junit.Assert.*
import org.junit.Test

class DetectionAttemptOutputTest {
    private val blocked = "2:BLOCKED:UNREADABLE_TREE:SNAPSHOT_PASS:COLLECTION_FAILED:TARGET:NONE:COLLECTION_EXCEPTION:NONE:VERIFIED:NODE_ACTION_READ:ILLEGAL_STATE"
    @Test fun `separate results cannot turn an assessment reason into a collection cause`() {
        assertEquals("ATTEMPT=1;RESULT=ASSESSED;DISPOSITION=UNCERTAIN;REASON=INVALID_GEOMETRY",
            DetectionAttemptOutput.format("1:ASSESSED:UNCERTAIN:INVALID_GEOMETRY"))
        val formatted = DetectionAttemptOutput.format(blocked)
        assertTrue(formatted.contains("ATTEMPT=2;RESULT=BLOCKED;BLOCK=UNREADABLE_TREE"))
        assertTrue(formatted.endsWith("OP=NODE_ACTION_READ;EXCEPTION=ILLEGAL_STATE"))
        assertFalse(formatted.contains("INVALID_GEOMETRY"))
    }
    @Test fun `unknown operations types and malformed ordinals fail closed without echo`() {
        for (payload in listOf("private", "0:ASSESSED:NO_POPUP:BASELINE_ONLY", "257:ASSESSED:NO_POPUP:BASELINE_ONLY",
            "01:ASSESSED:NO_POPUP:BASELINE_ONLY", "-1:BLOCKED:PASSWORD:NONE", "1:ASSESSED:NO_POPUP:private",
            blocked.replace("NODE_ACTION_READ", "PRIVATE"), blocked.replace("ILLEGAL_STATE", "PRIVATE"),
            blocked + ":EXTRA", "1:BLOCKED:PRIVATE:NONE")) {
            assertEquals("ATTEMPT_DIAGNOSTIC_INVALID", DetectionAttemptOutput.format(payload))
        }
    }
    @Test fun `missing collection detail remains explicit and never implies success`() {
        assertEquals("ATTEMPT=256;RESULT=BLOCKED;BLOCK=PASSWORD;COLLECTION=NONE",
            DetectionAttemptOutput.format("256:BLOCKED:PASSWORD:NONE"))
    }
}
