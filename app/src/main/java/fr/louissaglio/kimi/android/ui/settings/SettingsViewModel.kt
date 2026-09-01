package fr.louissaglio.kimi.android.ui.settings

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import fr.louissaglio.kimi.android.data.chat.ModelsRepository
import fr.louissaglio.kimi.android.data.chat.ServerConfig
import fr.louissaglio.kimi.android.data.chat.ServerModel
import fr.louissaglio.kimi.android.data.network.ConnectionChecker
import fr.louissaglio.kimi.android.data.settings.ServerSettings
import fr.louissaglio.kimi.android.data.settings.SettingsRepository
import fr.louissaglio.kimi.android.data.toUserMessage
import javax.inject.Inject
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

@HiltViewModel
class SettingsViewModel @Inject constructor(
    private val settingsRepository: SettingsRepository,
    private val connectionChecker: ConnectionChecker,
    private val modelsRepository: ModelsRepository,
) : ViewModel() {

    data class UiState(
        val serverUrl: String = ServerSettings.DEFAULT_SERVER_URL,
        val token: String = "",
        // False until the stored settings land: the screen renders nothing before that,
        // so the fields never flash the defaults over the user's values.
        val isLoaded: Boolean = false,
        val isTestingConnection: Boolean = false,
        val testResult: TestResult? = null,
        // The server-wide config (ticket 109); null until the fetch lands and after a
        // failure — the Models section only renders on a successful read, the same
        // degradation rule as the chat's model chip.
        val serverConfig: ServerConfig? = null,
        val availableModels: List<ServerModel> = emptyList(),
    ) {
        /** The catalog entry of the server default model, when the models fetch landed. */
        val currentDefaultModel: ServerModel?
            get() = availableModels.firstOrNull { it.model == serverConfig?.defaultModel }

        /** Row value for the default model: its display name when the catalog knows
         * the id, else the raw id. */
        val defaultModelLabel: String?
            get() = serverConfig?.defaultModel?.let { id ->
                availableModels.firstOrNull { it.model == id }?.displayName?.ifBlank { null } ?: id
            }

        /** The picker sheet's effort options — the default model's `support_efforts`;
         * empty hides the effort section entirely (models without effort levels expose
         * no effort UI, same rule as the chat picker). */
        val effortOptions: List<String>
            get() = currentDefaultModel?.supportEfforts.orEmpty()

        /** The single row's value: "K3 · high"; the effort joins only when the model
         * has effort levels and the server reports one. */
        val defaultModelSummary: String?
            get() = defaultModelLabel?.let { label ->
                val effort = serverConfig?.defaultEffort
                if (effortOptions.isNotEmpty() && effort != null) "$label · $effort" else label
            }
    }

    sealed interface TestResult {
        data class Success(val message: String) : TestResult
        data class Failure(val message: String) : TestResult
    }

    sealed interface Effect {
        data object SettingsSaved : Effect
        data class ConfigWriteFailed(val message: String) : Effect
    }

    val uiState: StateFlow<UiState>
        field = MutableStateFlow(UiState())

    private val _effects = Channel<Effect>(Channel.BUFFERED)
    val effects = _effects.receiveAsFlow()

    init {
        viewModelScope.launch {
            val stored = settingsRepository.current()
            uiState.update {
                it.copy(
                    serverUrl = stored.serverUrl,
                    token = stored.token,
                    isLoaded = true,
                )
            }
            loadServerConfig(stored)
        }
    }

    fun onServerUrlChange(value: String) =
        uiState.update { it.copy(serverUrl = value, testResult = null) }

    fun onTokenChange(value: String) =
        uiState.update { it.copy(token = value, testResult = null) }

    fun onSave() {
        viewModelScope.launch {
            settingsRepository.save(fieldSettings())
            _effects.send(Effect.SettingsSaved)
            // The saved server may differ: re-read its config so the Models section
            // never shows the previous server's values.
            loadServerConfig(fieldSettings())
        }
    }

    fun onTestConnection() {
        viewModelScope.launch {
            uiState.update { it.copy(isTestingConnection = true, testResult = null) }
            val testResult = connectionChecker.check(fieldSettings()).toTestResult()
            uiState.update { it.copy(isTestingConnection = false, testResult = testResult) }
        }
    }

    fun onDefaultModelSelected(model: String) {
        applyConfigWrite(
            write = { modelsRepository.setDefaultModel(fieldSettings(), model) },
            apply = { it.copy(defaultModel = model) },
        )
    }

    fun onDefaultEffortSelected(effort: String) {
        applyConfigWrite(
            write = { modelsRepository.setDefaultEffort(fieldSettings(), effort) },
            apply = { it.copy(defaultEffort = effort) },
        )
    }

    /** Server-wide config write (ticket 109): the row only moves on success; a failed
     * write leaves the shown values untouched and surfaces the snackbar. */
    private fun applyConfigWrite(
        write: suspend () -> Result<Unit>,
        apply: (ServerConfig) -> ServerConfig,
    ) {
        viewModelScope.launch {
            write()
                .onSuccess { uiState.update { state -> state.copy(serverConfig = state.serverConfig?.let(apply)) } }
                .onFailure { error -> _effects.send(Effect.ConfigWriteFailed(error.toUserMessage())) }
        }
    }

    private fun loadServerConfig(settings: ServerSettings) {
        viewModelScope.launch {
            val config = modelsRepository.config(settings).getOrNull()
            if (config == null) {
                uiState.update { it.copy(serverConfig = null, availableModels = emptyList()) }
                return@launch
            }
            val models = modelsRepository.models(settings).getOrNull().orEmpty()
            uiState.update { it.copy(serverConfig = config, availableModels = models) }
        }
    }

    private fun fieldSettings(): ServerSettings = uiState.value.let {
        ServerSettings(serverUrl = it.serverUrl.trim(), token = it.token.trim())
    }

    private fun ConnectionChecker.Result.toTestResult(): TestResult = when (this) {
        ConnectionChecker.Result.Success ->
            TestResult.Success("Connection successful")
        ConnectionChecker.Result.InvalidUrl ->
            TestResult.Failure("Invalid server URL")
        ConnectionChecker.Result.InvalidToken ->
            TestResult.Failure("Invalid token: it contains forbidden characters")
        ConnectionChecker.Result.AuthFailed ->
            TestResult.Failure("Authentication failed: wrong token")
        is ConnectionChecker.Result.HttpError ->
            TestResult.Failure("Unexpected server response (HTTP $code)")
        is ConnectionChecker.Result.ApiError ->
            TestResult.Failure("Server error $code: $msg")
        is ConnectionChecker.Result.Unreachable ->
            TestResult.Failure("Server unreachable: $detail")
    }
}
