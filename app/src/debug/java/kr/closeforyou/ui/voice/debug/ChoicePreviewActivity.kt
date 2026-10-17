package kr.closeforyou.ui.voice.debug

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import kr.closeforyou.R
import kr.closeforyou.core.model.UserChoice
import kr.closeforyou.testing.mock.MockPopupFixture
import kr.closeforyou.testing.mock.MockPopupFixtures
import kr.closeforyou.ui.voice.choice.ChoicePromptContent
import kr.closeforyou.ui.voice.choice.ChoicePromptPlanner

/**
 * Debug-only screen to try the "닫을까요?" prompt with synthetic fixtures (TalkBack included).
 * Uses each fixture's expected answer in place of B's classifier. No real screen data, no actions:
 * the produced UserChoice is only displayed, never sent to B or A.
 */
class ChoicePreviewActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent { MaterialTheme { ChoicePreviewScreen() } }
    }
}

private data class Scenario(val labelRes: Int, val fixture: MockPopupFixture)

private val scenarios = listOf(
    Scenario(R.string.ui_debug_scenario_advertisement, MockPopupFixtures.advertisement()),
    Scenario(R.string.ui_debug_scenario_notice, MockPopupFixtures.notice()),
    Scenario(R.string.ui_debug_scenario_changed_notice, MockPopupFixtures.changedDateNotice()),
    Scenario(R.string.ui_debug_scenario_decision, MockPopupFixtures.decision()),
    Scenario(R.string.ui_debug_scenario_unlabeled, MockPopupFixtures.unlabeledButton()),
    Scenario(R.string.ui_debug_scenario_video, MockPopupFixtures.videoWithoutAdEvidence()),
)

@Composable
private fun ChoicePreviewScreen() {
    var selected by remember { mutableStateOf(scenarios.first()) }
    var lastChoice by remember { mutableStateOf<UserChoice?>(null) }
    val prompt = remember(selected) {
        ChoicePromptPlanner.plan(selected.fixture.analysis, selected.fixture.expectedDisplayClassification)
    }

    Scaffold { innerPadding ->
        Column(
            modifier = Modifier
                .padding(innerPadding)
                .verticalScroll(rememberScrollState())
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            SectionTitle(R.string.ui_debug_preview_title)
            SectionTitle(R.string.ui_debug_section_scenario)
            Column(Modifier.selectableGroup()) {
                scenarios.forEach { scenario ->
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .heightIn(min = 48.dp)
                            .selectable(
                                selected = scenario == selected,
                                onClick = {
                                    selected = scenario
                                    lastChoice = null
                                },
                                role = Role.RadioButton,
                            ),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        RadioButton(selected = scenario == selected, onClick = null)
                        Text(stringResource(scenario.labelRes), modifier = Modifier.padding(start = 8.dp))
                    }
                }
            }

            HorizontalDivider()
            SectionTitle(R.string.ui_debug_section_prompt)
            ChoicePromptContent(
                prompt = prompt,
                onOption = { option -> lastChoice = ChoicePromptPlanner.toUserChoice(prompt, option) },
            )

            HorizontalDivider()
            SectionTitle(R.string.ui_debug_section_result)
            // Polite live region: the result is read without moving TalkBack focus.
            Text(
                text = lastChoice?.let { resultText(it) } ?: stringResource(R.string.ui_debug_result_none),
                modifier = Modifier.semantics { liveRegion = LiveRegionMode.Polite },
            )
        }
    }
}

@Composable
private fun SectionTitle(textRes: Int) {
    Text(
        text = stringResource(textRes),
        style = MaterialTheme.typography.titleMedium,
        modifier = Modifier.semantics { heading() },
    )
}

/** Shows only enum values and synthetic IDs, never popup text. */
@Composable
private fun resultText(choice: UserChoice): String = stringResource(
    R.string.ui_debug_result,
    choice.kind.name,
    choice.candidateId ?: stringResource(R.string.ui_debug_none),
    choice.buttonMeaningConfirmed.toString(),
    choice.repeatAutomationExplicitlyAllowed.toString(),
)
