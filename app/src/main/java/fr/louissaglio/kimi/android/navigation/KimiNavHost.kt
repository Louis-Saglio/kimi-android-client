package fr.louissaglio.kimi.android.navigation

import androidx.compose.runtime.Composable
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import fr.louissaglio.kimi.android.ui.sessions.SessionsScreen
import fr.louissaglio.kimi.android.ui.settings.SettingsScreen

object KimiRoutes {
    const val Sessions = "sessions"
    const val Settings = "settings"
}

@Composable
fun KimiNavHost() {
    val navController = rememberNavController()
    NavHost(navController = navController, startDestination = KimiRoutes.Sessions) {
        composable(KimiRoutes.Sessions) {
            SessionsScreen(onNavigateToSettings = { navController.navigate(KimiRoutes.Settings) })
        }
        composable(KimiRoutes.Settings) {
            SettingsScreen(onNavigateBack = { navController.popBackStack() })
        }
    }
}
