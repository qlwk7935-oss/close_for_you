package kr.closeforyou.accessibility

import kr.closeforyou.core.model.ScreenBounds
import org.junit.Assert.*
import org.junit.Test

class ScreenFramePolicyTest {
    private val frame = ScreenBounds(0, 0, 1080, 2400)
    @Test fun frameMustAgreeExactlyWithLogicalDisplayWithoutRepair() {
        assertEquals(ScreenFrameStatus.VERIFIED, ScreenFramePolicy.validate(frame, 1080, 2400))
        assertEquals(ScreenFrameStatus.EMPTY, ScreenFramePolicy.validate(ScreenBounds(0, 0, 0, 0), 1080, 2400))
        assertEquals(ScreenFrameStatus.EMPTY, ScreenFramePolicy.validate(frame, 0, 0))
        assertEquals(ScreenFrameStatus.OFFSET, ScreenFramePolicy.validate(ScreenBounds(0, 80, 1080, 2480), 1080, 2400))
        assertEquals(ScreenFrameStatus.SIZE_MISMATCH, ScreenFramePolicy.validate(ScreenBounds(0, 0, 1080, 2320), 1080, 2400))
        assertEquals(ScreenFrameStatus.SIZE_MISMATCH, ScreenFramePolicy.validate(frame, 2400, 1080))
        assertEquals(ScreenBounds(0, 0, 1080, 2400), frame)
    }
    @Test fun oldEmptyOrStaleMetricsAreDiagnosedRatherThanUsedAsFallback() {
        assertEquals(FrameRelation.EMPTY, ScreenFramePolicy.compare(ScreenBounds(0, 0, 0, 0), frame))
        assertEquals(FrameRelation.DIFFERENT, ScreenFramePolicy.compare(ScreenBounds(0, 0, 2400, 1080), frame))
        assertEquals(FrameRelation.UNAVAILABLE, ScreenFramePolicy.compare(null, frame))
        assertEquals(FrameRelation.SAME, ScreenFramePolicy.compare(frame, frame))
    }
    @Test fun outsideEdgesPreserveOnePixelViolationsAndInsetOrigin() {
        assertEquals(setOf(OutsideEdge.LEFT), ScreenFramePolicy.outside(ScreenBounds(-1, 900, 1000, 1320), frame))
        assertEquals(setOf(OutsideEdge.TOP), ScreenFramePolicy.outside(ScreenBounds(10, -1, 1000, 1320), frame))
        assertEquals(setOf(OutsideEdge.RIGHT), ScreenFramePolicy.outside(ScreenBounds(10, 900, 1081, 1320), frame))
        assertEquals(setOf(OutsideEdge.BOTTOM), ScreenFramePolicy.outside(ScreenBounds(10, 900, 1000, 2401), frame))
        assertTrue(ScreenFramePolicy.outside(ScreenBounds(10, 900, 1000, 1320), frame).isEmpty())
    }
    @Test fun onlyFixedSyntheticTitlesProduceRolesAndNeverAuthorizeCapture() {
        assertEquals(SyntheticWindowRole.SYNTHETIC_DIALOG, ScreenFramePolicy.syntheticRole("합성 이미지 창"))
        assertEquals(SyntheticWindowRole.SYNTHETIC_ACTIVITY, ScreenFramePolicy.syntheticRole("합성 배경 창"))
        assertEquals(SyntheticWindowRole.UNKNOWN, ScreenFramePolicy.syntheticRole("합성 알 수 없는 제목"))
        assertEquals(SyntheticWindowRole.UNKNOWN, ScreenFramePolicy.syntheticRole(null))
    }
}
