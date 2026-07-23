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
import fr.louissaglio.kimi.android.ui.sessions.SessionsScreen
import fr.louissaglio.kimi.android.ui.settings.SettingsScreen

object KimiRoutes {
    const val Sessions = "sessions"
    const val Settings = "settings"

    // title/cwd ride along as optional query args so the chat top bar can show the
    // label the session list already had, before the session fetch lands.
    const val Chat = "chat/{sessionId}?title={title}&cwd={cwd}"

    fun chat(session: Session): String {
        val route = StringBuilder("chat/").append(Uri.encode(session.id))
        val query = buildList {
            session.title?.let { add("title=" + Uri.encode(it)) }
            session.cwd?.let { add("cwd=" + Uri.encode(it)) }
        }
        if (query.isNotEmpty()) route.append('?').append(query.joinToString("&"))
        return route.toString()
    }
}

@Composable
fun KimiNavHost() {
    val navController = rememberNavController()
    NavHost(navController = navController, startDestination = KimiRoutes.Sessions) {
        composable(KimiRoutes.Sessions) {
            SessionsScreen(
                onNavigateToSettings = { navController.navigate(KimiRoutes.Settings) },
                onNavigateToChat = { session -> navController.navigate(KimiRoutes.chat(session)) },
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
        ) { backStackEntry ->
            ChatScreen(
                sessionId = backStackEntry.arguments?.getString("sessionId").orEmpty(),
                onNavigateBack = { navController.popBackStack() },
            )
        }
    }
}
