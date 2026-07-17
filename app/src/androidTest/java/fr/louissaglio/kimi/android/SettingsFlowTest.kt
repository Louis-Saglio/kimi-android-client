package fr.louissaglio.kimi.android

import android.content.Context
import android.view.inputmethod.InputMethodManager
import androidx.compose.ui.test.assert
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTextReplacement
import androidx.test.ext.junit.runners.AndroidJUnit4
import java.util.concurrent.TimeUnit
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Feature tests for ticket 003, run on-device against a loopback MockWebServer:
 * Firebase Test Lab devices cannot reach the Tailscale-only real server.
 */
@RunWith(AndroidJUnit4::class)
class SettingsFlowTest {

    @get:Rule
    val composeTestRule = createAndroidComposeRule<MainActivity>()

    private val server = MockWebServer()

    @Before
    fun setUp() = server.start()

    @After
    fun tearDown() = server.shutdown()

    @Test
    fun validCredentialsReportConnectionSuccess() {
        server.enqueue(MockResponse().setBody("""{"code":0,"msg":"success","data":{"ok":true}}"""))
        openSettingsAndType(serverBaseUrl(), username = "louis", password = "pw123")

        clickTestConnection()

        waitForText("Connection successful")
        val recorded = server.takeRequest(5, TimeUnit.SECONDS)!!
        assertEquals("/api/v1/healthz", recorded.path)
        assertEquals("Basic bG91aXM6cHcxMjM=", recorded.getHeader("Authorization"))
    }

    @Test
    fun wrongPasswordReportsAuthenticationFailure() {
        server.enqueue(MockResponse().setResponseCode(401))
        openSettingsAndType(serverBaseUrl(), username = "louis", password = "wrong")

        clickTestConnection()

        waitForText("Authentication failed: wrong username or password")
    }

    @Test
    fun unreachableHostReportsNetworkError() {
        val deadServer = MockWebServer()
        deadServer.start()
        val deadUrl = deadServer.url("/").toString().trimEnd('/')
        deadServer.shutdown()
        openSettingsAndType(deadUrl, username = "louis", password = "pw123")

        clickTestConnection()

        waitForText("Server unreachable", substring = true)
    }

    @Test
    fun savedSettingsAreRestoredAfterActivityRecreation() {
        val baseUrl = serverBaseUrl()
        val password = "pw123"
        openSettingsAndType(baseUrl, username = "louis", password = password)

        hideKeyboard()
        composeTestRule.onNodeWithText("Save").performClick()
        waitForText("Settings saved")

        composeTestRule.activityRule.scenario.recreate()

        composeTestRule.waitUntil(10_000) {
            composeTestRule.onAllNodes(hasTestTag("usernameField") and hasText("louis"))
                .fetchSemanticsNodes().isNotEmpty()
        }
        composeTestRule.onNodeWithTag("serverUrlField").assert(hasText(baseUrl))
        // Password semantics expose only the masked value by design; its length proves the restore.
        composeTestRule.onNodeWithTag("passwordField").assert(hasText("•".repeat(password.length)))
    }

    private fun serverBaseUrl() = server.url("/").toString().trimEnd('/')

    private fun openSettingsAndType(baseUrl: String, username: String, password: String) {
        composeTestRule.onNodeWithContentDescription("Settings").performClick()
        composeTestRule.onNodeWithTag("serverUrlField").performTextReplacement(baseUrl)
        composeTestRule.onNodeWithTag("usernameField").performTextReplacement(username)
        composeTestRule.onNodeWithTag("passwordField").performTextReplacement(password)
    }

    private fun clickTestConnection() {
        hideKeyboard()
        composeTestRule.onNodeWithText("Test connection").performClick()
    }

    private fun waitForText(text: String, substring: Boolean = false) {
        composeTestRule.waitUntil(10_000) {
            composeTestRule.onAllNodesWithText(text, substring = substring)
                .fetchSemanticsNodes().isNotEmpty()
        }
    }

    // The IME can cover the buttons after typing; performClick would land on it instead.
    private fun hideKeyboard() {
        composeTestRule.runOnIdle {
            val imm = composeTestRule.activity
                .getSystemService(Context.INPUT_METHOD_SERVICE) as InputMethodManager
            imm.hideSoftInputFromWindow(composeTestRule.activity.window.decorView.windowToken, 0)
        }
    }
}
