package kr.closeforyou.popuptest

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * tools/qa_metrics.py가 읽는 정답지 CSV가 ScenarioCatalog와 같은지 확인한다.
 * 시나리오를 바꾸면 docs/templates/popup_answer_key.csv도 함께 고쳐야 이 시험이 통과한다.
 */
class AnswerKeyCsvTest {
    private val csv = listOf(
        File("../docs/templates/popup_answer_key.csv"),
        File("docs/templates/popup_answer_key.csv"),
    ).firstOrNull { it.exists() }

    @Test
    fun answerKeyCsvMatchesTheCatalog() {
        assertTrue("popup_answer_key.csv not found", csv != null)
        val lines = csv!!.readLines(Charsets.UTF_8).filter { it.isNotBlank() }
        val header = lines.first().split(',')
        val rows = lines.drop(1).map { line -> header.zip(line.split(',')).toMap() }
        assertEquals(ScenarioCatalog.all.map { it.id }, rows.map { it.getValue("scenario_id") })

        rows.forEach { row ->
            val scenario = ScenarioCatalog.find(row.getValue("scenario_id"))!!
            val id = scenario.id
            assertEquals(id, scenario.title, row["title"])
            assertEquals(id, scenario.kind.name, row["kind"])
            assertEquals(id, scenario.expected.name, row["expected_handling"])
            assertEquals(id, scenario.closeEvidence.name, row["close_evidence"])
            assertEquals(id, scenario.correctTargetId.orEmpty(), row["correct_target"])
            assertEquals(id, scenario.forbiddenTargetIds, split(row["forbidden_targets"]))
            assertEquals(id, scenario.rememberable.toString(), row["rememberable"])
            assertEquals(id, scenario.memoryReusableFrom.orEmpty(), row["memory_reusable_from"])
            assertEquals(id, scenario.experiments, split(row["experiments"]))
        }
    }

    private fun split(value: String?): Set<String> =
        value.orEmpty().split(';').filter { it.isNotEmpty() }.toSet()
}
