package fr.louissaglio.kimi.android.ui.search

import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.test.ext.junit.runners.AndroidJUnit4
import fr.louissaglio.kimi.android.data.search.SearchHit
import fr.louissaglio.kimi.android.ui.theme.KimiTheme
import java.time.Instant
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/** Semantics tests for the global search screen (ticket 146): the stateless
 * [SearchContent] body is driven with hand-made states — no Hilt, no network
 * (the TurnChangesScreenTest construction pattern). */
@RunWith(AndroidJUnit4::class)
class SearchScreenTest {

    @get:Rule
    val composeTestRule = createComposeRule()

    private fun searchContent(
        uiState: SearchViewModel.UiState,
        onHitClick: (SearchHit) -> Unit = {},
    ) {
        composeTestRule.setContent {
            KimiTheme {
                SearchContent(uiState, onNavigateBack = {}, onHitClick = onHitClick)
            }
        }
    }

    private fun hit(sessionId: String, title: String, role: SearchHit.Role, snippet: String) =
        SearchHit(
            sessionId = sessionId,
            sessionTitle = title,
            role = role,
            snippet = snippet,
            time = Instant.ofEpochMilli(1754323200000L),
        )

    @Test
    fun scopedSearchNamesTheSessionInTheTitleAndPrompt() {
        // Ticket 147: the chat top bar's magnifier opens this screen scoped to the
        // session — the title slot's placeholder and the prompt name it.
        searchContent(SearchViewModel.UiState(scopeTitle = "Deploy hotfix"))

        composeTestRule.onNodeWithText("Search in \"Deploy hotfix\"").assertIsDisplayed()
        composeTestRule.onNodeWithText("Search messages and replies in \"Deploy hotfix\"").assertIsDisplayed()
    }

    @Test
    fun resultsRenderTitleSnippetAndRoleIcon() {
        searchContent(
            SearchViewModel.UiState(
                query = "deploy",
                submittedQuery = "deploy",
                results = listOf(
                    hit("session_1", "Deploy hotfix", SearchHit.Role.User, "why did the deploy fail"),
                    hit("session_2", "Infra notes", SearchHit.Role.Assistant, "the deploy failed because"),
                    hit("session_3", "deploy runbook", SearchHit.Role.Title, "deploy runbook"),
                ),
            ),
        )

        composeTestRule.onNodeWithText("Deploy hotfix").assertIsDisplayed()
        composeTestRule.onNodeWithText("why did the deploy fail").assertIsDisplayed()
        composeTestRule.onNodeWithText("Infra notes").assertIsDisplayed()
        composeTestRule.onNodeWithText("the deploy failed because").assertIsDisplayed()
        composeTestRule.onNodeWithContentDescription("User message").assertIsDisplayed()
        composeTestRule.onNodeWithContentDescription("Assistant reply").assertIsDisplayed()
        composeTestRule.onNodeWithContentDescription("Session title").assertIsDisplayed()
    }

    @Test
    fun staleIndexShowsBannerWithoutHidingResults() {
        searchContent(
            SearchViewModel.UiState(
                query = "deploy",
                submittedQuery = "deploy",
                results = listOf(hit("session_1", "Deploy hotfix", SearchHit.Role.User, "snippet")),
                indexStale = true,
            ),
        )

        composeTestRule.onNodeWithText("Search index rebuilding — results may be incomplete").assertIsDisplayed()
        composeTestRule.onNodeWithText("Deploy hotfix").assertIsDisplayed()
    }

    @Test
    fun tappingAResultReportsTheHit() {
        val expected = hit("session_1", "Deploy hotfix", SearchHit.Role.User, "why did the deploy fail")
        val opened = mutableListOf<SearchHit>()
        searchContent(
            SearchViewModel.UiState(
                query = "deploy",
                submittedQuery = "deploy",
                results = listOf(expected),
            ),
            onHitClick = { opened += it },
        )

        composeTestRule.onNodeWithText("Deploy hotfix").performClick()

        assertEquals(listOf(expected), opened)
    }
}
