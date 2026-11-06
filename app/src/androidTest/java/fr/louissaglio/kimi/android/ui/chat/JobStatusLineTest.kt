package fr.louissaglio.kimi.android.ui.chat

import androidx.compose.ui.semantics.ProgressBarRangeInfo
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.test.ext.junit.runners.AndroidJUnit4
import fr.louissaglio.kimi.android.ui.theme.KimiTheme
import java.time.Instant
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/** Semantics tests for the job screen's status line (ticket 097): the budget text
 * (`elapsed / budget`, default labeled), the determinate progress fraction, and the
 * "timed out" terminal label vs a plain "failed". */
@RunWith(AndroidJUnit4::class)
class JobStatusLineTest {

    @get:Rule
    val composeTestRule = createComposeRule()

    private val now: Instant = Instant.parse("2026-08-05T10:00:00.000Z")

    private fun statusLine(state: JobDetailViewModel.UiState) {
        composeTestRule.setContent { KimiTheme { JobStatusLine(state, now) } }
    }

    private fun runningState(
        elapsedSeconds: Long,
        budgetSeconds: Int?,
        budgetIsDefault: Boolean = false,
    ) = JobDetailViewModel.UiState(
        taskId = "bash-1",
        status = Job.Status.Running,
        startedAt = now.minusSeconds(elapsedSeconds),
        budgetSeconds = budgetSeconds,
        budgetIsDefault = budgetIsDefault,
    )

    @Test
    fun runningWithAnExplicitBudgetShowsElapsedOverBudgetAndTheFraction() {
        statusLine(runningState(elapsedSeconds = 15, budgetSeconds = 30))

        composeTestRule.onNodeWithText("running · 00:15 / 00:30").assertIsDisplayed()
        composeTestRule
            .onNode(
                SemanticsMatcher.expectValue(
                    SemanticsProperties.ProgressBarRangeInfo,
                    ProgressBarRangeInfo(0.5f, 0f..1f),
                ),
            )
            .assertIsDisplayed()
    }

    @Test
    fun runningWithTheDefaultBudgetLabelsItAsDefault() {
        statusLine(runningState(elapsedSeconds = 15, budgetSeconds = 600, budgetIsDefault = true))

        composeTestRule.onNodeWithText("running · 00:15 / 10:00 · default").assertIsDisplayed()
    }

    @Test
    fun runningWithoutBudgetShowsPlainElapsedAndNoProgressBar() {
        // disable_timeout (or an unknown budget — deep link): the 096 behavior.
        statusLine(runningState(elapsedSeconds = 15, budgetSeconds = null))

        composeTestRule.onNodeWithText("running · 00:15").assertIsDisplayed()
        // Newer M3 reports an indeterminate ProgressBarRangeInfo (0.0..0.0) on the
        // running glyph's CircularProgressIndicator — only the determinate budget bar
        // (0.0..1.0) is forbidden here.
        composeTestRule
            .onAllNodes(
                SemanticsMatcher("has a determinate ProgressBarRangeInfo") {
                    val info = it.config.getOrNull(SemanticsProperties.ProgressBarRangeInfo)
                    info != null && info.range.endInclusive > info.range.start
                },
            )
            .assertCountEquals(0)
    }

    @Test
    fun aLiveRecordedTimeoutReadsTimedOutAfterTheBudget() {
        statusLine(
            JobDetailViewModel.UiState(
                taskId = "bash-1",
                status = Job.Status.Failed,
                startedAt = now.minusSeconds(30),
                completedAt = now,
                budgetSeconds = 30,
                timedOut = true,
            ),
        )

        composeTestRule.onNodeWithText("timed out after 00:30").assertIsDisplayed()
        composeTestRule.onNodeWithContentDescription("Job timed out").assertIsDisplayed()
    }

    @Test
    fun aPlainFailureReadsFailed() {
        // A failed (not timed_out) outcome — without the flag nothing is claimed.
        statusLine(
            JobDetailViewModel.UiState(
                taskId = "bash-1",
                status = Job.Status.Failed,
                startedAt = now.minusSeconds(5),
                completedAt = now,
            ),
        )

        composeTestRule.onNodeWithText("failed · 00:05").assertIsDisplayed()
        composeTestRule.onNodeWithText("timed out after 00:05", substring = true).assertDoesNotExist()
    }
}
