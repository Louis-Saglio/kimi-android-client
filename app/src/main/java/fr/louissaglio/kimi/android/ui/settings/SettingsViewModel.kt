package fr.louissaglio.kimi.android.ui.settings

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import fr.louissaglio.kimi.android.data.network.ConnectionChecker
import fr.louissaglio.kimi.android.data.settings.ServerSettings
import fr.louissaglio.kimi.android.data.settings.SettingsRepository
import javax.inject.Inject
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

@HiltViewModel
class SettingsViewModel @Inject constructor(
    private val settingsRepository: SettingsRepository,
    private val connectionChecker: ConnectionChecker,
) : ViewModel() {

    data class UiState(
        val serverUrl: String = ServerSettings.DEFAULT_SERVER_URL,
        val username: String = "",
        val password: String = "",
        val isTestingConnection: Boolean = false,
        val testResult: TestResult? = null,
    )

    sealed interface TestResult {
        data class Success(val message: String) : TestResult
        data class Failure(val message: String) : TestResult
    }

    sealed interface Effect {
        data object SettingsSaved : Effect
    }

    private val _uiState = MutableStateFlow(UiState())
    val uiState: StateFlow<UiState> = _uiState.asStateFlow()

    private val _effects = Channel<Effect>(Channel.BUFFERED)
    val effects = _effects.receiveAsFlow()

    init {
        viewModelScope.launch {
            val stored = settingsRepository.current()
            _uiState.update {
                it.copy(
                    serverUrl = stored.serverUrl,
                    username = stored.username,
                    password = stored.password,
                )
            }
        }
    }

    fun onServerUrlChange(value: String) =
        _uiState.update { it.copy(serverUrl = value, testResult = null) }

    fun onUsernameChange(value: String) =
        _uiState.update { it.copy(username = value, testResult = null) }

    fun onPasswordChange(value: String) =
        _uiState.update { it.copy(password = value, testResult = null) }

    fun onSave() {
        viewModelScope.launch {
            settingsRepository.save(fieldSettings())
            _effects.send(Effect.SettingsSaved)
        }
    }

    fun onTestConnection() {
        viewModelScope.launch {
            _uiState.update { it.copy(isTestingConnection = true, testResult = null) }
            val testResult = connectionChecker.check(fieldSettings()).toTestResult()
            _uiState.update { it.copy(isTestingConnection = false, testResult = testResult) }
        }
    }

    private fun fieldSettings(): ServerSettings = _uiState.value.let {
        ServerSettings(serverUrl = it.serverUrl, username = it.username, password = it.password)
    }

    private fun ConnectionChecker.Result.toTestResult(): TestResult = when (this) {
        ConnectionChecker.Result.Success ->
            TestResult.Success("Connection successful")
        ConnectionChecker.Result.InvalidUrl ->
            TestResult.Failure("Invalid server URL")
        ConnectionChecker.Result.AuthFailed ->
            TestResult.Failure("Authentication failed: wrong username or password")
        is ConnectionChecker.Result.HttpError ->
            TestResult.Failure("Unexpected server response (HTTP $code)")
        is ConnectionChecker.Result.ApiError ->
            TestResult.Failure("Server error $code: $msg")
        is ConnectionChecker.Result.Unreachable ->
            TestResult.Failure("Server unreachable: $detail")
    }
}
