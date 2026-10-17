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

/** Independent experiment. Its OK applies to the square controls only; baseline failures remain
 * failures in ServiceSyntheticPatternOptInTest and are reported separately here as well. */
@RunWith(AndroidJUnit4::class)
class ServiceSyntheticRenderControlsOptInTest {
    @Test fun squareOpaqueDialogsValidateStrictPixelsAndReportRenderingContrasts() = runBlocking {
        assumeTrue("WINDOW_CAPTURE_API_REQUIRED", android.os.Build.VERSION.SDK_INT >= 34)
        val args = InstrumentationRegistry.getArguments()
        assumeTrue(args.getString("captureOcrService") == "true" && args.getString("syntheticPattern") == "true" &&
            args.getString("renderControls") == "true")
        assertFalse("CONTROLS_REQUIRE_CAPTURE", args.getString("connectionOnly") == "true")
        SyntheticServiceSession.run(bufferOnly = false, connectionCheckOnly = true)
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        instrumentation.getUiAutomation(UiAutomation.FLAG_DONT_SUPPRESS_ACCESSIBILITY_SERVICES)
        val layout = ControlLayoutReference()
        val matrix = try { RenderControlMatrix.run({ SyntheticPatternObservation.observe(it, layout) }) { delay(3_000) } }
            finally { layout.clear() }
        val details = listOf(
            "BASELINE_RESULT=${matrix.outcome(PatternMode.ZERO_ELEVATION).result.name}",
            "BASELINE_MAPPING=${if (matrix.outcome(PatternMode.ZERO_ELEVATION).passed) "VERIFIED" else "UNVERIFIED"}",
            "ROUND_CLIPPED_RESULT=${matrix.outcome(PatternMode.ROUND_CLIPPED).result.name}",
            "ROUND_UNCLIPPED_RESULT=${matrix.outcome(PatternMode.ROUND_UNCLIPPED).result.name}",
            "SQUARE_CLIPPED_RESULT=${matrix.outcome(PatternMode.SQUARE_CLIPPED).result.name}",
            "SQUARE_UNCLIPPED_RESULT=${matrix.outcome(PatternMode.SQUARE_UNCLIPPED).result.name}",
            "OBSERVATIONS=${if (matrix.complete) "COMPLETE" else "INCOMPLETE"}",
            "SQUARE_CONTROLS=${if (matrix.squareControlsPassed) "PASSED" else "FAILED"}",
            "CLIPPED_RADIUS_CONTRAST=${matrix.contrast(PatternMode.ROUND_CLIPPED, PatternMode.SQUARE_CLIPPED).name}",
            "ROUND_CLIP_CONTRAST=${matrix.contrast(PatternMode.ROUND_CLIPPED, PatternMode.ROUND_UNCLIPPED).name}",
            "SQUARE_CLIP_CONTRAST=${matrix.contrast(PatternMode.SQUARE_CLIPPED, PatternMode.SQUARE_UNCLIPPED).name}",
            "THEME_TO_SQUARE_CONTRAST=${matrix.contrast(PatternMode.ZERO_ELEVATION, PatternMode.SQUARE_CLIPPED).name}",
            "PRODUCTION_POLICY=UNCHANGED").joinToString(";")
        instrumentation.sendStatus(0, Bundle().apply { putString("stream", "SYNTHETIC_RENDER_CONTROL_COMPARISON;$details\n") })
        assertTrue("SQUARE_RENDER_CONTROLS_NOT_VERIFIED;$details", matrix.squareControlsPassed)
    }
}
