package kr.closeforyou.servicetest

import android.app.UiAutomation
import android.os.Bundle
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import kotlinx.coroutines.*
import org.junit.Assert.*
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith

/** Observe both fresh Dialogs before the final assertion. Failed content remains a failed mode;
 * UNKNOWN/UNKNOWN or either failure can never become evidence of an elevation relationship. */
@RunWith(AndroidJUnit4::class)
class ServiceSyntheticPatternOptInTest {
    @Test fun verifiesOwnedPatternTranslationAndComparesFreshDecorElevation() = runBlocking {
        assumeTrue("WINDOW_CAPTURE_API_REQUIRED", android.os.Build.VERSION.SDK_INT >= 34)
        val arguments = InstrumentationRegistry.getArguments()
        assumeTrue(arguments.getString("captureOcrService") == "true" && arguments.getString("syntheticPattern") == "true")
        assertFalse("PATTERN_PROBE_REQUIRES_CAPTURE", arguments.getString("connectionOnly") == "true")
        SyntheticServiceSession.run(bufferOnly = false, connectionCheckOnly = true)
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        instrumentation.getUiAutomation(UiAutomation.FLAG_DONT_SUPPRESS_ACCESSIBILITY_SERVICES)
        val comparison = PatternModeRunner.run({ SyntheticPatternObservation.observe(it) }) { delay(3_000) }
        val summary = "DEFAULT_RESULT=${comparison.normal.result.name};ZERO_RESULT=${comparison.flat.result.name};" +
            "ELEVATION_EFFECT=${comparison.effect.name};PRODUCTION_POLICY=UNCHANGED"
        instrumentation.sendStatus(0, Bundle().apply { putString("stream", "SYNTHETIC_PATTERN_COMPARISON;$summary\n") })
        assertTrue("PATTERN_MAPPING_NOT_VERIFIED;$summary", comparison.passed)
    }

}
