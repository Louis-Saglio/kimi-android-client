package fr.louissaglio.kimi.android.data.settings

import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.emptyPreferences
import androidx.datastore.preferences.core.stringPreferencesKey
import fr.louissaglio.kimi.android.di.ApplicationScope
import java.io.IOException
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn

@Singleton
class SettingsRepository @Inject constructor(
    private val dataStore: DataStore<Preferences>,
    @ApplicationScope applicationScope: CoroutineScope,
) {
    private object Keys {
        val SERVER_URL = stringPreferencesKey("server_url")
        val USERNAME = stringPreferencesKey("username")
        val PASSWORD = stringPreferencesKey("password")
    }

    private val settingsFlow = dataStore.data
        .catch { e -> if (e is IOException) emit(emptyPreferences()) else throw e }
        .map { prefs ->
            ServerSettings(
                serverUrl = prefs[Keys.SERVER_URL] ?: ServerSettings.DEFAULT_SERVER_URL,
                username = prefs[Keys.USERNAME] ?: "",
                password = prefs[Keys.PASSWORD] ?: "",
            )
        }

    // Hot snapshot for the synchronous OkHttp interceptor; always current after DataStore loads.
    val settings: StateFlow<ServerSettings> =
        settingsFlow.stateIn(applicationScope, SharingStarted.Eagerly, ServerSettings())

    // Unlike settings.value, this suspends until the first disk read, so callers never see defaults in place of stored values.
    suspend fun current(): ServerSettings = settingsFlow.first()

    suspend fun save(settings: ServerSettings) {
        dataStore.edit { prefs ->
            prefs[Keys.SERVER_URL] = settings.serverUrl
            prefs[Keys.USERNAME] = settings.username
            prefs[Keys.PASSWORD] = settings.password
        }
    }
}
