package fr.louissaglio.kimi.android.navigation

import androidx.compose.runtime.Composable
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import androidx.navigation.toRoute
import fr.louissaglio.kimi.android.ui.chat.ChatScreen
import fr.louissaglio.kimi.android.ui.chat.JobDetailScreen
import fr.louissaglio.kimi.android.ui.chat.SubagentScreen
import fr.louissaglio.kimi.android.ui.sessions.SessionsScreen
import fr.louissaglio.kimi.android.ui.settings.SettingsScreen
import kotlinx.serialization.Serializable

// Type-safe routes (ticket 083): the serializers own the route patterns and the Uri
// encoding — no string templates, navArgument blocks, or hand-rolled Uri.encode.
// ViewModels read the args back with SavedStateHandle.toRoute<T>().

@Serializable
object Sessions

@Serializable
object Settings

/** title/cwd ride along as optional query args so the chat top bar can show the
 * label the session list already had, before the session fetch lands. */
@Serializable
data class Chat(val sessionId: String, val title: String? = null, val cwd: String? = null)

/** Read-only sub-agent stream page (ticket 045); both ids reach the ViewModel
 * through its SavedStateHandle. */
@Serializable
data class Subagent(val sessionId: String, val subagentId: String)

/** Background-bash job detail page (ticket 096): information, live output tail and
 * the Stop action; both ids reach the ViewModel through its SavedStateHandle. The
 * timeout budget and the timed-out flag (ticket 097) ride as optional arguments —
 * REST never exposes the timeout and collapses `timed_out` to `failed`, so the chat
 * resolves them from its own state at navigation time; absent (deep link, unknown
 * row), the screen falls back to plain elapsed and "failed". The description and
 * command ride along the same way (the tapped row already carries the call input),
 * so the title and the Command section render before the entry fetch answers; the
 * fetch stays authoritative and overwrites both. */
@Serializable
data class JobDetail(
    val sessionId: String,
    val taskId: String,
    val budgetSeconds: Int? = null,
    val budgetIsDefault: Boolean = false,
    val timedOut: Boolean = false,
    val description: String? = null,
    val command: String? = null,
)

@Composable
fun KimiNavHost() {
    val navController = rememberNavController()
    NavHost(navController = navController, startDestination = Sessions) {
        composable<Sessions> {
            SessionsScreen(
                onNavigateToSettings = { navController.navigate(Settings) },
                // launchSingleTop: a fast double-tap must not stack the destination (071).
                onNavigateToChat = { session ->
                    navController.navigate(Chat(session.id, session.title, session.cwd)) {
                        launchSingleTop = true
                    }
                },
            )
        }
        composable<Settings> {
            SettingsScreen(onNavigateBack = { navController.popBackStack() })
        }
        // sessionId/title/cwd reach the ViewModel through its SavedStateHandle;
        // the screen itself needs nothing from the route.
        composable<Chat> {
            ChatScreen(
                onNavigateBack = { navController.popBackStack() },
                onNavigateToSubagent = { sessionId, subagentId ->
                    navController.navigate(Subagent(sessionId, subagentId)) {
                        launchSingleTop = true
                    }
                },
                onNavigateToJob = { sessionId, taskId, budgetSeconds, budgetIsDefault, timedOut, description, command ->
                    navController.navigate(
                        JobDetail(sessionId, taskId, budgetSeconds, budgetIsDefault, timedOut, description, command),
                    ) {
                        launchSingleTop = true
                    }
                },
            )
        }
        composable<Subagent> { entry ->
            // Nested sub-agents (ticket 071): the page can navigate one level deeper,
            // into another sub-agent of the SAME session (from this entry's route).
            val sessionId = entry.toRoute<Subagent>().sessionId
            SubagentScreen(
                onNavigateBack = { navController.popBackStack() },
                onOpenSubagent = { subagentId ->
                    navController.navigate(Subagent(sessionId, subagentId)) {
                        launchSingleTop = true
                    }
                },
            )
        }
        composable<JobDetail> {
            JobDetailScreen(onNavigateBack = { navController.popBackStack() })
        }
    }
}
