package kr.closeforyou.accessibility.capture

import android.graphics.ColorSpace
import android.hardware.HardwareBuffer
import kr.closeforyou.accessibility.testing.SyntheticPatternProbe
import kr.closeforyou.core.model.PopupSnapshot

/** Debug observation only: cannot return a frame, change region or authorize capture/OCR. */
internal object SyntheticBufferInspection {
    @Volatile var probe: SyntheticPatternProbe? = null
    fun inspect(snapshot: PopupSnapshot, region: CaptureRegion, buffer: HardwareBuffer, colorSpace: ColorSpace) {
        probe?.inspect(snapshot, region, buffer, colorSpace)
    }
    fun released(snapshot: PopupSnapshot) { probe?.released(snapshot) }
}
