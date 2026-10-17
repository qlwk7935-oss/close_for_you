package kr.closeforyou.servicetest

import androidx.test.ext.junit.runners.AndroidJUnit4
import kotlinx.coroutines.runBlocking
import org.junit.Test
import org.junit.runner.RunWith

/** API 35/37, synthetic-only device and explicit consent. Reports buffer metadata relations,
 * including safe REGION_UNCERTAIN rejection. Passing is NOT a capture/OCR success claim. */
@RunWith(AndroidJUnit4::class)
class ServiceBufferGeometryOptInTest {
    @Test fun observesAndReleasesSyntheticDialogBuffer() = runBlocking {
        SyntheticServiceSession.run(bufferOnly = true)
    }
}
