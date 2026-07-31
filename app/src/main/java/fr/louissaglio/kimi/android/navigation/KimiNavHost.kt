package fr.louissaglio.kimi.android.navigation

import android.net.Uri
import androidx.compose.runtime.Composable
import androidx.navigation.NavType
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import androidx.navigation.navArgument
import fr.louissaglio.kimi.android.data.sessions.Session
import fr.louissaglio.kimi.android.ui.chat.ChatScreen
import fr.louissaglio.kimi.android.ui.chat.SubagentScreen
import fr.louissaglio.kimi.android.ui.sessions.SessionsScreen
import fr.louissaglio.kimi.android.ui.settings.SettingsScreen

object KimiRoutes {
    const val Sessions = "sessions"
    const val Settings = "settings"

    // title/cwd ride along as optional query args so the chat top bar can show the
    // label the session list already had, before the session fetch lands.
    const val Chat = "chat/{sessionId}?title={title}&cwd={cwd}"

    // Read-only sub-agent stream page (ticket 045); both ids reach the ViewModel
    // through its SavedStateHandle.
    const val Subagent = "subagent/{sessionId}/{subagentId}"

    fun chat(session: Session): String {
        val route = StringBuilder("chat/").append(Uri.encode(session.id))
        val query = buildList {
            session.title?.let { add("title=" + Uri.encode(it)) }
            session.cwd?.let { add("cwd=" + Uri.encode(it)) }
        }
        if (query.isNotEmpty()) route.append('?').append(query.joinToString("&"))
        return route.toString()
    }

    fun subagent(sessionId: String, subagentId: String): String =
        "subagent/${Uri.encode(sessionId)}/${Uri.encode(subagentId)}"
}

@Composable
fun KimiNavHost() {
    val navController = rememberNavController()
    NavHost(navController = navController, startDestination = KimiRoutes.Sessions) {
        composable(KimiRoutes.Sessions) {
            SessionsScreen(
                onNavigateToSettings = { navController.navigate(KimiRoutes.Settings) },
                // launchSingleTop: a fast double-tap must not stack the destination (071).
                onNavigateToChat = { session ->
                    navController.navigate(KimiRoutes.chat(session)) { launchSingleTop = true }
                },
            )
        }
        composable(KimiRoutes.Settings) {
            SettingsScreen(onNavigateBack = { navController.popBackStack() })
        }
        composable(
            KimiRoutes.Chat,
            arguments = listOf(
                navArgument("title") {
                    type = NavType.StringType
                    nullable = true
                    defaultValue = null
                },
                navArgument("cwd") {
                    type = NavType.StringType
                    nullable = true
                    defaultValue = null
                },
            ),
        ) {
            // sessionId/title/cwd reach the ViewModel through its SavedStateHandle;
            // the screen itself needs nothing from the route.
            ChatScreen(
                onNavigateBack = { navController.popBackStack() },
                onNavigateToSubagent = { sessionId, subagentId ->
                    navController.navigate(KimiRoutes.subagent(sessionId, subagentId)) {
                        launchSingleTop = true
                    }
                },
            )
        }
        composable(KimiRoutes.Subagent) { entry ->
            // Nested sub-agents (ticket 071): the page can navigate one level deeper,
            // into another sub-agent of the SAME session (from this entry's arguments).
            val sessionId = entry.arguments?.getString("sessionId").orEmpty()
            SubagentScreen(
                onNavigateBack = { navController.popBackStack() },
                onOpenSubagent = { subagentId ->
                    navController.navigate(KimiRoutes.subagent(sessionId, subagentId)) {
                        launchSingleTop = true
                    }
                },
            )
        }
    }
}
