package fr.louissaglio.kimi.android

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import fr.louissaglio.kimi.android.data.KimiApi
import fr.louissaglio.kimi.android.data.SessionRepository
import fr.louissaglio.kimi.android.data.UploadRepository
import fr.louissaglio.kimi.android.data.auth.CredentialStore
import fr.louissaglio.kimi.android.ui.screens.ChatScreen
import fr.louissaglio.kimi.android.ui.screens.LoginScreen
import fr.louissaglio.kimi.android.ui.screens.SessionListScreen
import fr.louissaglio.kimi.android.ui.theme.KimiTheme

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            KimiTheme {
                MainContent()
            }
        }
    }

    @Composable
    private fun MainContent() {
        val app = applicationContext as KimiApplication
        val credentialStore = remember { app.credentialStore }
        var credentials by remember { mutableStateOf(credentialStore.load()) }
        var selectedSessionId by remember { mutableStateOf<String?>(null) }

        val deps = credentials?.let { cred ->
            remember(cred.serverUrl, cred.username, cred.password) {
                val api = app.api(cred.serverUrl, cred.username, cred.password)
                val repository = app.sessionRepository(cred.serverUrl, cred.username, cred.password)
                val uploadRepository = app.uploadRepository(cred.serverUrl, cred.username, cred.password)
                Dependencies(api, repository, uploadRepository)
            }
        }

        when {
            deps == null -> {
                LoginScreen(
                    credentialStore = credentialStore,
                    onLoginSuccess = { credentials = credentialStore.load() }
                )
            }

            selectedSessionId != null -> {
                ChatScreen(
                    sessionId = selectedSessionId!!,
                    api = deps.api,
                    repository = deps.repository,
                    uploadRepository = deps.uploadRepository,
                    onNavigateBack = { selectedSessionId = null }
                )
            }

            else -> {
                SessionListScreen(
                    api = deps.api,
                    credentialStore = credentialStore,
                    onSessionSelected = { sessionId -> selectedSessionId = sessionId },
                    onLogout = {
                        deps?.repository?.disconnectWebSocket()
                        credentials = null
                        selectedSessionId = null
                    }
                )
            }
        }
    }

    private data class Dependencies(
        val api: KimiApi,
        val repository: SessionRepository,
        val uploadRepository: UploadRepository
    )
}
