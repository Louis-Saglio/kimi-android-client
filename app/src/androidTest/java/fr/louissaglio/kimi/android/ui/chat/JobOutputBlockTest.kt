package fr.louissaglio.kimi.android.ui.chat

import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.test.ext.junit.runners.AndroidJUnit4
import fr.louissaglio.kimi.android.ui.theme.KimiTheme
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/** Semantics tests for the job screen's output block: the loading placeholder that
 * stands in while the entry fetch runs (the shell renders from the route seeds
 * first), then the output tail / the "No output yet" placeholder. */
@RunWith(AndroidJUnit4::class)
class JobOutputBlockTest {

    @get:Rule
    val composeTestRule = createComposeRule()

    private fun outputBlock(state: JobDetailViewModel.UiState) {
        composeTestRule.setContent { KimiTheme { JobOutputBlock(state) } }
    }

    @Test
    fun theEntryFetchInFlightShowsALoadingPlaceholder() {
        outputBlock(JobDetailViewModel.UiState(taskId = "bash-1", isLoading = true))

        composeTestRule.onNodeWithContentDescription("Loading output").assertIsDisplayed()
        composeTestRule.onNodeWithText("No output yet").assertDoesNotExist()
    }

    @Test
    fun aFetchedJobShowsTheOutputTail() {
        outputBlock(
            JobDetailViewModel.UiState(
                taskId = "bash-1",
                status = Job.Status.Running,
                outputPreview = "tick 41\n",
                isLoading = false,
            ),
        )

        composeTestRule.onNodeWithText("tick 41\n").assertIsDisplayed()
        composeTestRule.onNodeWithContentDescription("Loading output").assertDoesNotExist()
    }

    @Test
    fun aFetchedJobWithoutOutputShowsTheEmptyPlaceholder() {
        outputBlock(
            JobDetailViewModel.UiState(
                taskId = "bash-1",
                status = Job.Status.Completed,
                isLoading = false,
            ),
        )

        composeTestRule.onNodeWithText("No output yet").assertIsDisplayed()
        composeTestRule.onNodeWithContentDescription("Loading output").assertDoesNotExist()
    }

    @Test
    fun aSubAgentOwnedJobShowsTheOutputUnavailablePlaceholder() {
        // Store-backed mode (ticket 098): no output channel exists for these tasks —
        // an explicit placeholder, never the poll spinner or the "No output yet" copy.
        outputBlock(
            JobDetailViewModel.UiState(
                taskId = "bash-1",
                status = Job.Status.Running,
                storeBacked = true,
                isLoading = false,
            ),
        )

        composeTestRule.onNodeWithText("Output not available for sub-agent tasks").assertIsDisplayed()
        composeTestRule.onNodeWithContentDescription("Loading output").assertDoesNotExist()
        composeTestRule.onNodeWithText("No output yet").assertDoesNotExist()
    }
}
