package kr.closeforyou.simpleclose

import kr.closeforyou.ui.voice.settings.HandlingPreset
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** B_DEMO_RULES.md 4-2 (재호 11/4): the image hint names a popup an ad only under "전부 자동 닫기". */
class ImageHintPresetTest {
    @Test fun onlyCloseAll() {
        assertTrue(ImageDialogAdHint.presetAllows(HandlingPreset.CLOSE_ALL.autoCloseKinds()))
        assertFalse(ImageDialogAdHint.presetAllows(HandlingPreset.CLOSE_ADS_AND_COUPONS.autoCloseKinds()))
        assertFalse(ImageDialogAdHint.presetAllows(HandlingPreset.CLOSE_ADS_ONLY.autoCloseKinds()))
    }

    @Test fun switchOffIsNotAllowed() {
        assertFalse(ImageDialogAdHint.presetAllows(emptySet()))
    }
}
