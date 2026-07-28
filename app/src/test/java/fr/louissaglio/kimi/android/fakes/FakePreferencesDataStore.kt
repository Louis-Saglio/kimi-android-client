package fr.louissaglio.kimi.android.fakes

import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow

/** In-memory DataStore for unit tests: no file I/O, updates apply synchronously. */
class FakePreferencesDataStore(prefs: Preferences) : DataStore<Preferences> {
    private val state = MutableStateFlow(prefs)
    override val data: Flow<Preferences> = state

    override suspend fun updateData(transform: suspend (Preferences) -> Preferences): Preferences =
        transform(state.value).also { state.value = it }
}
