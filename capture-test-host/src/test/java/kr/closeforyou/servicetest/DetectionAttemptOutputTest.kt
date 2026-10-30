package kr.closeforyou.servicetest

import org.junit.Assert.*
import org.junit.Test

class DetectionAttemptOutputTest {
    private val blocked = "2:BLOCKED:UNREADABLE_TREE:SNAPSHOT_PASS:COLLECTION_FAILED:TARGET:NONE:COLLECTION_EXCEPTION:NONE:VERIFIED:NODE_ACTION_READ:ILLEGAL_STATE:UNOBSERVED:UNOBSERVED:UNOBSERVED:UNOBSERVED:UNOBSERVED:UNOBSERVED"
    @Test fun `separate results cannot turn an assessment reason into a collection cause`() {
        assertEquals("ATTEMPT=1;RESULT=ASSESSED;DISPOSITION=UNCERTAIN;REASON=INVALID_GEOMETRY;GEOMETRY=UNOBSERVED;WINDOW_BOUNDS_AGE=UNOBSERVED",
            DetectionAttemptOutput.format("1:ASSESSED:UNCERTAIN:INVALID_GEOMETRY:UNOBSERVED:UNOBSERVED"))
        val formatted = DetectionAttemptOutput.format(blocked)
        assertTrue(formatted.contains("ATTEMPT=2;RESULT=BLOCKED;BLOCK=UNREADABLE_TREE"))
        assertTrue(formatted.contains("OP=NODE_ACTION_READ;EXCEPTION=ILLEGAL_STATE;"))
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
    @Test fun `node and frame codes preserve separate typed observations`() {
        val assessed = DetectionAttemptOutput.format("1:ASSESSED:UNCERTAIN:INVALID_GEOMETRY:WINDOW_OUTSIDE_FRAME:POSITIVE:POSITIVE:WINDOW_OUTSIDE_FRAME:LEFT,TOP:OLDER")
        assertTrue(assessed.contains("GEOMETRY=WINDOW_OUTSIDE_FRAME;FRAME_EXTENT=POSITIVE;WINDOW_EXTENT=POSITIVE"))
        assertTrue(assessed.endsWith("OUTSIDE_EDGES=LEFT,TOP;WINDOW_BOUNDS_AGE=OLDER"))
        val rejected = DetectionAttemptOutput.format("2:BLOCKED:UNREADABLE_TREE:SNAPSHOT_PASS:TREE_PROTECTION:TARGET:NONE:MALFORMED_NODE_BOUNDS:NONE:VERIFIED:NODE_BOUNDS_VALIDATE:NONE:REVERSED_Y:NOT_VISIBLE:SCROLL_ANCESTOR:DESCENDANT:WITHIN_ONE_SECOND:CONTENT_CHANGED")
        assertTrue(rejected.contains("NODE_RECT=REVERSED_Y;NODE_VISIBILITY=NOT_VISIBLE;NODE_ANCESTRY=SCROLL_ANCESTOR;NODE_ROLE=DESCENDANT"))
        assertFalse(rejected.contains("WINDOW_OUTSIDE_FRAME"))
    }
    @Test fun `unknown relations repeated edges raw coordinates and unknown context cannot be echoed`() {
        val good = "1:ASSESSED:UNCERTAIN:INVALID_GEOMETRY:WINDOW_OUTSIDE_FRAME:POSITIVE:POSITIVE:WINDOW_OUTSIDE_FRAME:LEFT,TOP:OLDER"
        for (payload in listOf(good.replace("LEFT,TOP", "1,2"), good.replace("LEFT,TOP", "LEFT,LEFT"),
            good.replace("LEFT,TOP", "TOP,LEFT"), good.replace("OLDER", "PRIVATE"), good.replace("POSITIVE", "PRIVATE"),
            blocked.replace("UNOBSERVED", "PRIVATE"))) {
            assertEquals("ATTEMPT_DIAGNOSTIC_INVALID", DetectionAttemptOutput.format(payload))
        }
    }
}
