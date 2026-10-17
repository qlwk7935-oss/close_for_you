package kr.closeforyou.accessibility.capture

import android.graphics.ColorSpace
import android.hardware.HardwareBuffer
import kr.closeforyou.core.model.PopupSnapshot

/** Release contains no registration entry point, pixel inspection or synthetic pattern implementation. */
internal object SyntheticBufferInspection {
    @Suppress("UNUSED_PARAMETER")
    fun inspect(snapshot: PopupSnapshot, region: CaptureRegion, buffer: HardwareBuffer, colorSpace: ColorSpace) = Unit
    @Suppress("UNUSED_PARAMETER")
    fun released(snapshot: PopupSnapshot) = Unit
}
