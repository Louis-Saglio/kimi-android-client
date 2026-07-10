package fr.louissaglio.kimi.android.viewmodel

import android.util.Log
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import fr.louissaglio.kimi.android.data.KimiApi
import fr.louissaglio.kimi.android.data.auth.CredentialStore
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

class LoginViewModel(
    private val credentialStore: CredentialStore
) : ViewModel() {

    private val _uiState = MutableStateFlow(LoginUiState())
    val uiState: StateFlow<LoginUiState> = _uiState.asStateFlow()

    init {
        val saved = credentialStore.load()
        if (saved != null) {
            _uiState.value = LoginUiState(
                serverUrl = saved.serverUrl,
                username = saved.username,
                password = saved.password
            )
            testCredentials(saved.serverUrl, saved.username, saved.password)
        }
    }

    fun onServerUrlChange(value: String) {
        _uiState.value = _uiState.value.copy(serverUrl = value)
    }

    fun onUsernameChange(value: String) {
        _uiState.value = _uiState.value.copy(username = value)
    }

    fun onPasswordChange(value: String) {
        _uiState.value = _uiState.value.copy(password = value)
    }

    fun connect() {
        val state = _uiState.value
        testCredentials(state.serverUrl, state.username, state.password)
    }

    private fun testCredentials(serverUrl: String, username: String, password: String) {
        viewModelScope.launch {
            _uiState.value = _uiState.value.copy(isLoading = true, error = null)
            try {
                val api = KimiApi(serverUrl, username, password)
                api.meta()
                credentialStore.save(serverUrl, username, password)
                _uiState.value = _uiState.value.copy(isLoading = false, success = true)
            } catch (e: Exception) {
                Log.e("KimiLogin", "Connection test failed", e)
                val detail = buildString {
                    append(e.javaClass.simpleName)
                    e.message?.let { append(": ").append(it) }
                    e.cause?.let { cause ->
                        append(" (caused by ")
                        append(cause.javaClass.simpleName)
                        cause.message?.let { append(": ").append(it) }
                        append(")")
                    }
                }
                _uiState.value = _uiState.value.copy(
                    isLoading = false,
                    error = detail.ifEmpty { "Connection failed" }
                )
            }
        }
    }

    data class LoginUiState(
        val serverUrl: String = "http://100.64.0.1",
        val username: String = "",
        val password: String = "",
        val isLoading: Boolean = false,
        val error: String? = null,
        val success: Boolean = false
    )

    class Factory(private val credentialStore: CredentialStore) : ViewModelProvider.Factory {
        @Suppress("UNCHECKED_CAST")
        override fun <T : ViewModel> create(modelClass: Class<T>): T {
            return LoginViewModel(credentialStore) as T
        }
    }
}
