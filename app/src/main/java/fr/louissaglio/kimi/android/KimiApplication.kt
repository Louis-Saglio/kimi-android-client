package fr.louissaglio.kimi.android

import android.app.Application
import fr.louissaglio.kimi.android.data.KimiApi
import fr.louissaglio.kimi.android.data.SessionRepository
import fr.louissaglio.kimi.android.data.UploadRepository
import fr.louissaglio.kimi.android.data.WebSocketManager
import fr.louissaglio.kimi.android.data.auth.CredentialStore

class KimiApplication : Application() {

    val credentialStore: CredentialStore by lazy { CredentialStore(this) }

    fun api(serverUrl: String, username: String, password: String): KimiApi {
        return KimiApi(serverUrl, username, password)
    }

    fun sessionRepository(serverUrl: String, username: String, password: String): SessionRepository {
        val api = api(serverUrl, username, password)
        val ws = WebSocketManager(serverUrl, username, password)
        return SessionRepository(api, ws)
    }

    fun uploadRepository(serverUrl: String, username: String, password: String): UploadRepository {
        return UploadRepository(api(serverUrl, username, password))
    }
}
