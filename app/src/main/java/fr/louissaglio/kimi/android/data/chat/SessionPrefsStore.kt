package fr.louissaglio.kimi.android.data.chat

import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.emptyPreferences
import androidx.datastore.preferences.core.stringPreferencesKey
import java.io.IOException
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.first

/**
 * Per-session model/effort choices (tickets 031/032) and permission-mode picks
 * (ticket 141), persisted in DataStore as
 * plain `model_for_session_<sessionId>` / `effort_for_session_<sessionId>` /
 * `permission_for_session_<sessionId>` string
 * preferences (SettingsRepository's pattern). No stored value = follow the server
 * default. DataStore reads are already suspending — no extra dispatcher.
 */
@Singleton
class SessionPrefsStore @Inject constructor(
    private val dataStore: DataStore<Preferences>,
) {
    /** The session's stored model choice, null when the session follows the server default. */
    suspend fun model(sessionId: String): String? =
        dataStore.data
            .catch { e -> if (e is IOException) emit(emptyPreferences()) else throw e }
            .first()[stringPreferencesKey("model_for_session_$sessionId")]
            ?.takeIf { it.isNotBlank() }

    /** Persists the session's model choice; subsequent prompts carry it. */
    suspend fun setModel(sessionId: String, model: String) {
        dataStore.edit { it[stringPreferencesKey("model_for_session_$sessionId")] = model.trim() }
    }

    /** The session's stored effort choice, null when the session follows the server default. */
    suspend fun effort(sessionId: String): String? =
        dataStore.data
            .catch { e -> if (e is IOException) emit(emptyPreferences()) else throw e }
            .first()[stringPreferencesKey("effort_for_session_$sessionId")]
            ?.takeIf { it.isNotBlank() }

    /** Persists the session's effort choice; subsequent prompts carry it. */
    suspend fun setEffort(sessionId: String, effort: String) {
        dataStore.edit { it[stringPreferencesKey("effort_for_session_$sessionId")] = effort.trim() }
    }

    /** The session's stored permission-mode pick (ticket 141), null when the session
     * follows the server. Only explicit local picks land here — the server-reported
     * mode is never written through (unlike the model binding, the server always
     * reports one, and a stored value stamps every prompt). */
    suspend fun permission(sessionId: String): String? =
        dataStore.data
            .catch { e -> if (e is IOException) emit(emptyPreferences()) else throw e }
            .first()[stringPreferencesKey("permission_for_session_$sessionId")]
            ?.takeIf { it.isNotBlank() }

    /** Persists the session's permission-mode pick; subsequent prompts carry it. */
    suspend fun setPermission(sessionId: String, permission: String) {
        dataStore.edit { it[stringPreferencesKey("permission_for_session_$sessionId")] = permission.trim() }
    }
}
