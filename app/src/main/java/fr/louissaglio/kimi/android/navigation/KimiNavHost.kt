package fr.louissaglio.kimi.android.navigation

import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import androidx.navigation.toRoute
import fr.louissaglio.kimi.android.ui.chat.ChatScreen
import fr.louissaglio.kimi.android.ui.chat.EditDiffScreen
import fr.louissaglio.kimi.android.ui.chat.FileContentRoute
import fr.louissaglio.kimi.android.ui.chat.JobDetailScreen
import fr.louissaglio.kimi.android.ui.chat.SubagentScreen
import fr.louissaglio.kimi.android.ui.chat.ToolContentStore
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
 * fetch stays authoritative and overwrites both. [ownerAgentId] (ticket 098) selects
 * the data mode: null/"main" is the REST-backed mode; a sub-agent id is the
 * store-backed mode — REST `/tasks` serves only the main agent's task store, so a
 * sub-agent-owned task gets its status from the SubagentStreamStore's live frames
 * (no output tail, no Stop). */
@Serializable
data class JobDetail(
    val sessionId: String,
    val taskId: String,
    val budgetSeconds: Int? = null,
    val budgetIsDefault: Boolean = false,
    val timedOut: Boolean = false,
    val description: String? = null,
    val command: String? = null,
    val ownerAgentId: String? = null,
)

/** The Edit call's unified diff screen (ticket 119): only the tool call id rides
 * the route — the payload itself (potentially large old/new strings) goes through
 * the process-lifetime [ToolContentStore], staged by the navigating tap and
 * consumed by the screen's ViewModel. */
@Serializable
data class EditDiff(val toolCallId: String)

/** The Read/Write file content screen (ticket 120): same shape as [EditDiff] — only
 * the tool call id rides the route; the payload and the call's output snapshot go
 * through the [ToolContentStore]. */
@Serializable
data class FileContent(val toolCallId: String)

@Composable
fun KimiNavHost(toolContentStore: ToolContentStore) {
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
                onNavigateToEditDiff = { toolCallId, payload ->
                    toolContentStore.put(toolCallId, payload)
                    navController.navigate(EditDiff(toolCallId)) {
                        launchSingleTop = true
                    }
                },
                onNavigateToFileContent = { toolCallId, payload, output ->
                    toolContentStore.put(toolCallId, payload, output)
                    navController.navigate(FileContent(toolCallId)) {
                        launchSingleTop = true
                    }
                },
                // Ticket 127: the fork opens as a new Chat on top — back returns to
                // the source session (same argument hand-off as the session list).
                onNavigateToChat = { session ->
                    navController.navigate(Chat(session.id, session.title, session.cwd)) {
                        launchSingleTop = true
                    }
                },
            )
        }
        composable<Subagent> { entry ->
            // Nested sub-agents (ticket 071): the page can navigate one level deeper,
            // into another sub-agent of the SAME session (from this entry's route).
            val route = entry.toRoute<Subagent>()
            SubagentScreen(
                onNavigateBack = { navController.popBackStack() },
                onOpenSubagent = { subagentId ->
                    navController.navigate(Subagent(route.sessionId, subagentId)) {
                        launchSingleTop = true
                    }
                },
                // The page's background bash jobs are owned by its sub-agent
                // (ticket 098): the owner stamp puts the job screen in its
                // store-backed mode.
                onNavigateToJob = { sessionId, taskId, budgetSeconds, budgetIsDefault, timedOut, description, command ->
                    navController.navigate(
                        JobDetail(
                            sessionId, taskId, budgetSeconds, budgetIsDefault, timedOut,
                            description, command, ownerAgentId = route.subagentId,
                        ),
                    ) {
                        launchSingleTop = true
                    }
                },
                // Same store hand-off as the chat's Edit rows (ticket 119).
                onNavigateToEditDiff = { toolCallId, payload ->
                    toolContentStore.put(toolCallId, payload)
                    navController.navigate(EditDiff(toolCallId)) {
                        launchSingleTop = true
                    }
                },
                // Same store hand-off as the chat's Read/Write rows (ticket 120).
                onNavigateToFileContent = { toolCallId, payload, output ->
                    toolContentStore.put(toolCallId, payload, output)
                    navController.navigate(FileContent(toolCallId)) {
                        launchSingleTop = true
                    }
                },
            )
        }
        composable<JobDetail> {
            JobDetailScreen(onNavigateBack = { navController.popBackStack() })
        }
        composable<EditDiff> {
            EditDiffScreen(onNavigateBack = { navController.popBackStack() })
        }
        composable<FileContent> { backStackEntry ->
            val route = backStackEntry.toRoute<FileContent>()
            // No ViewModel for this static snapshot screen (ticket 120): the staged
            // entry is consumed once per destination instance — the remember is what
            // keeps later recompositions from re-consuming an already-served id. A
            // recreation after the store lost the entry (process death with this
            // screen on top) finds nothing and renders the missing-content state —
            // the Edit diff screen's store-miss recovery.
            val staged = remember { toolContentStore.consume(route.toolCallId) }
            FileContentRoute(route.toolCallId, staged, onNavigateBack = { navController.popBackStack() })
        }
    }
}
