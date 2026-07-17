package fr.louissaglio.kimi.android.navigation

import androidx.compose.runtime.Composable
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import fr.louissaglio.kimi.android.ui.sessions.SessionsScreen

object KimiRoutes {
    const val Sessions = "sessions"
}

@Composable
fun KimiNavHost() {
    val navController = rememberNavController()
    NavHost(navController = navController, startDestination = KimiRoutes.Sessions) {
        composable(KimiRoutes.Sessions) { SessionsScreen() }
    }
}
