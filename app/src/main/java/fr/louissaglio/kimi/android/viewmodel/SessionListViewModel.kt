package fr.louissaglio.kimi.android.viewmodel

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import fr.louissaglio.kimi.android.data.KimiApi
import fr.louissaglio.kimi.android.data.auth.CredentialStore
import fr.louissaglio.kimi.android.data.model.Session
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

class SessionListViewModel(
    private val api: KimiApi,
    private val credentialStore: CredentialStore
) : ViewModel() {

    private val _uiState = MutableStateFlow(SessionListUiState())
    val uiState: StateFlow<SessionListUiState> = _uiState.asStateFlow()

    init {
        loadSessions()
    }

    fun loadSessions() {
        viewModelScope.launch {
            _uiState.value = _uiState.value.copy(isLoading = true, error = null)
            try {
                val sessions = api.listSessions().filter { !it.archived }
                _uiState.value = _uiState.value.copy(
                    isLoading = false,
                    sessions = sessions
                )
            } catch (e: Exception) {
                _uiState.value = _uiState.value.copy(
                    isLoading = false,
                    error = e.message ?: "Failed to load sessions"
                )
            }
        }
    }

    fun createSession(onCreated: (String) -> Unit) {
        viewModelScope.launch {
            _uiState.value = _uiState.value.copy(isLoading = true, error = null)
            try {
                val session = api.createSession()
                _uiState.value = _uiState.value.copy(
                    isLoading = false,
                    sessions = listOf(session) + _uiState.value.sessions
                )
                onCreated(session.id)
            } catch (e: Exception) {
                _uiState.value = _uiState.value.copy(
                    isLoading = false,
                    error = e.message ?: "Failed to create session"
                )
            }
        }
    }

    fun logout() {
        credentialStore.clear()
    }

    data class SessionListUiState(
        val sessions: List<Session> = emptyList(),
        val isLoading: Boolean = false,
        val error: String? = null
    )

    class Factory(
        private val api: KimiApi,
        private val credentialStore: CredentialStore
    ) : ViewModelProvider.Factory {
        @Suppress("UNCHECKED_CAST")
        override fun <T : ViewModel> create(modelClass: Class<T>): T {
            return SessionListViewModel(api, credentialStore) as T
        }
    }
}
