package fr.louissaglio.kimi.android.data.settings

import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
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
        val TOKEN = stringPreferencesKey("token")
        val SESSION_LIST_GROUPED = booleanPreferencesKey("session_list_grouped")
    }

    private val settingsFlow = dataStore.data
        .catch { e -> if (e is IOException) emit(emptyPreferences()) else throw e }
        .map { prefs ->
            ServerSettings(
                serverUrl = (prefs[Keys.SERVER_URL] ?: ServerSettings.DEFAULT_SERVER_URL).trim(),
                token = (prefs[Keys.TOKEN] ?: "").trim(),
            )
        }

    // Hot snapshot for the synchronous OkHttp interceptor; always current after DataStore loads.
    val settings: StateFlow<ServerSettings> =
        settingsFlow.stateIn(applicationScope, SharingStarted.Eagerly, ServerSettings())

    // Unlike settings.value, this suspends until the first disk read, so callers never see defaults in place of stored values.
    suspend fun current(): ServerSettings = settingsFlow.first()

    suspend fun save(settings: ServerSettings) {
        dataStore.edit { prefs ->
            prefs[Keys.SERVER_URL] = settings.serverUrl.trim()
            prefs[Keys.TOKEN] = settings.token.trim()
        }
    }

    private val sessionListGroupedFlow = dataStore.data
        .catch { e -> if (e is IOException) emit(emptyPreferences()) else throw e }
        .map { prefs -> prefs[Keys.SESSION_LIST_GROUPED] ?: false }

    /**
     * The persisted flat/grouped toggle of the session list (ticket 115); flat is the
     * default. Suspends until the first disk read, like [current].
     */
    suspend fun isSessionListGrouped(): Boolean = sessionListGroupedFlow.first()

    suspend fun setSessionListGrouped(grouped: Boolean) {
        dataStore.edit { prefs -> prefs[Keys.SESSION_LIST_GROUPED] = grouped }
    }
}
