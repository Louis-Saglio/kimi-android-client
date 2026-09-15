package fr.louissaglio.kimi.android.ui.settings

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import fr.louissaglio.kimi.android.data.chat.ServerConfig
import fr.louissaglio.kimi.android.ui.chat.ModelPickerSheet
import fr.louissaglio.kimi.android.ui.chat.PermissionMode
import fr.louissaglio.kimi.android.ui.chat.PermissionPickerSheet
import fr.louissaglio.kimi.android.ui.theme.KimiTheme

@Composable
fun SettingsScreen(
    onNavigateBack: () -> Unit,
    viewModel: SettingsViewModel = hiltViewModel(),
) {
    val uiState by viewModel.uiState.collectAsStateWithLifecycle()
    val snackbarHostState = remember { SnackbarHostState() }
    var openSheet by remember { mutableStateOf<Sheet?>(null) }
    LaunchedEffect(Unit) {
        viewModel.effects.collect { effect ->
            when (effect) {
                SettingsViewModel.Effect.SettingsSaved ->
                    snackbarHostState.showSnackbar("Settings saved")
                is SettingsViewModel.Effect.ConfigWriteFailed -> {
                    // The picker sheet is modal: dismiss it first or the snackbar
                    // hides behind its scrim.
                    openSheet = null
                    snackbarHostState.showSnackbar(effect.message)
                }
            }
        }
    }
    SettingsContent(
        uiState = uiState,
        snackbarHostState = snackbarHostState,
        openSheet = openSheet,
        onOpenSheetChange = { openSheet = it },
        onNavigateBack = onNavigateBack,
        onServerUrlChange = viewModel::onServerUrlChange,
        onTokenChange = viewModel::onTokenChange,
        onSave = viewModel::onSave,
        onTestConnection = viewModel::onTestConnection,
        onDefaultModelSelected = viewModel::onDefaultModelSelected,
        onDefaultEffortSelected = viewModel::onDefaultEffortSelected,
        onDefaultPermissionModeSelected = viewModel::onDefaultPermissionModeSelected,
    )
}

/** The picker sheet currently open over the server-config rows, if any. */
private enum class Sheet { Model, Permission }

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun SettingsContent(
    uiState: SettingsViewModel.UiState,
    snackbarHostState: SnackbarHostState,
    openSheet: Sheet?,
    onOpenSheetChange: (Sheet?) -> Unit,
    onNavigateBack: () -> Unit,
    onServerUrlChange: (String) -> Unit,
    onTokenChange: (String) -> Unit,
    onSave: () -> Unit,
    onTestConnection: () -> Unit,
    onDefaultModelSelected: (String) -> Unit,
    onDefaultEffortSelected: (String) -> Unit,
    onDefaultPermissionModeSelected: (PermissionMode) -> Unit,
) {
    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Settings") },
                navigationIcon = {
                    IconButton(onClick = onNavigateBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back")
                    }
                },
            )
        },
        snackbarHost = { SnackbarHost(snackbarHostState) },
    ) { innerPadding ->
        // Stored settings land asynchronously: nothing renders until they arrive, so the
        // fields never flash the defaults over the user's values.
        if (!uiState.isLoaded) return@Scaffold
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding)
                .verticalScroll(rememberScrollState())
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            OutlinedTextField(
                value = uiState.serverUrl,
                onValueChange = onServerUrlChange,
                label = { Text("Server URL") },
                singleLine = true,
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri),
                modifier = Modifier.fillMaxWidth().testTag("serverUrlField"),
            )
            OutlinedTextField(
                value = uiState.token,
                onValueChange = onTokenChange,
                label = { Text("Token") },
                singleLine = true,
                visualTransformation = PasswordVisualTransformation(),
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password),
                modifier = Modifier.fillMaxWidth().testTag("tokenField"),
            )
            Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                Button(onClick = onSave) {
                    Text("Save")
                }
                OutlinedButton(onClick = onTestConnection, enabled = !uiState.isTestingConnection) {
                    if (uiState.isTestingConnection) {
                        CircularProgressIndicator(
                            modifier = Modifier.size(16.dp),
                            strokeWidth = 2.dp,
                        )
                    } else {
                        Text("Test connection")
                    }
                }
            }
            when (val result = uiState.testResult) {
                null -> Unit
                is SettingsViewModel.TestResult.Success -> Text(
                    text = result.message,
                    color = MaterialTheme.colorScheme.primary,
                    style = MaterialTheme.typography.bodyMedium,
                )
                is SettingsViewModel.TestResult.Failure -> Text(
                    text = result.message,
                    color = MaterialTheme.colorScheme.error,
                    style = MaterialTheme.typography.bodyMedium,
                )
            }
            uiState.serverConfig?.let { config ->
                ServerConfigSections(
                    uiState = uiState,
                    config = config,
                    openSheet = openSheet,
                    onOpenSheetChange = onOpenSheetChange,
                    onDefaultModelSelected = onDefaultModelSelected,
                    onDefaultEffortSelected = onDefaultEffortSelected,
                    onDefaultPermissionModeSelected = onDefaultPermissionModeSelected,
                )
            }
        }
    }
}

/** The server-wide model settings (ticket 109, revised after Louis's pass): a single
 * "Default model" row opening the chat's model-picker sheet — the check IS the server
 * default here, so no "Default" badge —, the "Default permission" row (ticket 142)
 * opening the chat's permission sheet the same way, then the read-only providers under
 * their own header. A pick writes through the ViewModel; the sheet's check moves on
 * success and stays put on failure (the failure also dismisses the sheet for the
 * snackbar). */
@Composable
private fun ServerConfigSections(
    uiState: SettingsViewModel.UiState,
    config: ServerConfig,
    openSheet: Sheet?,
    onOpenSheetChange: (Sheet?) -> Unit,
    onDefaultModelSelected: (String) -> Unit,
    onDefaultEffortSelected: (String) -> Unit,
    onDefaultPermissionModeSelected: (PermissionMode) -> Unit,
) {
    SectionHeader("Models")
    SettingsRow(
        title = "Default model",
        value = uiState.defaultModelSummary ?: "Not set",
        onClick = { onOpenSheetChange(Sheet.Model) },
    )
    SettingsRow(
        title = "Default permission",
        value = uiState.defaultPermissionLabel ?: "Not set",
        supportingText = "Applies to new sessions",
        onClick = { onOpenSheetChange(Sheet.Permission) },
    )
    if (config.providers.isNotEmpty()) {
        SectionHeader("Providers")
        config.providers.forEach { provider ->
            SettingsRow(
                title = provider.name,
                value = provider.baseUrl +
                    if (provider.hasApiKey) " · API key configured" else " · no key",
                onClick = null,
            )
        }
    }

    if (openSheet == Sheet.Model) {
        ModelPickerSheet(
            availableModels = uiState.availableModels,
            currentModelId = config.defaultModel,
            serverDefaultModel = null,
            supportedEfforts = uiState.effortOptions,
            selectedEffort = config.defaultEffort,
            defaultEffort = uiState.currentDefaultModel?.defaultEffort,
            onModelSelected = onDefaultModelSelected,
            onEffortSelected = onDefaultEffortSelected,
            onDismiss = { onOpenSheetChange(null) },
        )
    }
    if (openSheet == Sheet.Permission) {
        PermissionPickerSheet(
            current = PermissionMode.fromWire(config.defaultPermissionMode),
            onSelected = onDefaultPermissionModeSelected,
            onDismiss = { onOpenSheetChange(null) },
        )
    }
}

@Composable
private fun SectionHeader(text: String) {
    Text(
        text = text,
        style = MaterialTheme.typography.titleSmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
    )
}

/** One settings row: label over current value, then the optional supporting text; a
 * null [onClick] makes it read-only. */
@Composable
private fun SettingsRow(
    title: String,
    value: String,
    onClick: (() -> Unit)?,
    supportingText: String? = null,
) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .let { if (onClick != null) it.clickable(onClick = onClick) else it }
            .padding(vertical = 4.dp),
    ) {
        Text(text = title, style = MaterialTheme.typography.bodyLarge)
        Text(
            text = value,
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        if (supportingText != null) {
            Text(
                text = supportingText,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

@Preview
@Composable
private fun SettingsContentPreview() {
    KimiTheme {
        SettingsContent(
            uiState = SettingsViewModel.UiState(token = "tok123", isLoaded = true),
            snackbarHostState = remember { SnackbarHostState() },
            openSheet = null,
            onOpenSheetChange = {},
            onNavigateBack = {},
            onServerUrlChange = {},
            onTokenChange = {},
            onSave = {},
            onTestConnection = {},
            onDefaultModelSelected = {},
            onDefaultEffortSelected = {},
            onDefaultPermissionModeSelected = {},
        )
    }
}
