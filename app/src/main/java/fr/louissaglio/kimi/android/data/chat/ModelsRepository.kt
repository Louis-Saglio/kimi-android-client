package fr.louissaglio.kimi.android.data.chat

import fr.louissaglio.kimi.android.data.network.ApiClient
import fr.louissaglio.kimi.android.data.network.Envelope
import fr.louissaglio.kimi.android.data.network.toAckResult
import fr.louissaglio.kimi.android.data.network.toNullableResult
import fr.louissaglio.kimi.android.data.network.toResult
import fr.louissaglio.kimi.android.data.settings.ServerSettings
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneOffset
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonObject

/** Models/config REST (`GET /api/v1/models`, `GET`/`POST /api/v1/config`) with the
 * per-server-URL caches the send path and the Settings "Models" section (109) read.
 * A cache entry lives until the server URL changes or a global
 * `event.config.changed`/`event.model_catalog.changed` frame drops it (110). */
@Singleton
class ModelsRepository @Inject constructor(
    private val api: ApiClient,
) {
    @Volatile
    private var cachedModels: Pair<String, List<ServerModel>>? = null

    @Volatile
    private var cachedConfig: Pair<String, ServerConfig>? = null

    /**
     * The models the server offers (`GET /api/v1/models`), for the chat screen's model
     * picker and the Settings default-model dialog. Cached per server URL exactly like
     * [config] (a settings change refetches); fetch failures are not cached, so the
     * next open retries.
     */
    suspend fun models(settings: ServerSettings): Result<List<ServerModel>> {
        val baseUrl = api.baseUrl(settings)
        cachedModels?.let { if (it.first == baseUrl) return Result.success(it.second) }
        val result = fetchModels(settings)
        result.onSuccess { cachedModels = baseUrl to it }
        return result
    }

    /**
     * The server-wide config projection (`GET /api/v1/config`): default model, default
     * thinking effort, default permission mode and providers. Cached per server URL like
     * [models]; fetch failures are not cached.
     */
    suspend fun config(settings: ServerSettings): Result<ServerConfig> {
        val baseUrl = api.baseUrl(settings)
        cachedConfig?.let { if (it.first == baseUrl) return Result.success(it.second) }
        val result = fetchConfig(settings)
        result.onSuccess { cachedConfig = baseUrl to it }
        return result
    }

    /**
     * The model to send with prompts: 0.28.0+ no longer falls back to the server's
     * `default_model` for sessions with an empty `agent_config.model` (the turn fails
     * with `model.not_configured`), so the app passes the server default explicitly.
     * Reads the [config] cache; fetch failures degrade to the pre-0.28 behavior: the
     * prompt goes out without a model.
     */
    suspend fun defaultModel(settings: ServerSettings): String? =
        config(settings).getOrNull()?.defaultModel

    /**
     * Sets the server-wide default model (`POST /api/v1/config`, snake_case body —
     * verified live 2026-09-01). On success the cached config is patched so subsequent
     * reads serve the new value without a refetch.
     */
    suspend fun setDefaultModel(settings: ServerSettings, model: String): Result<Unit> {
        val result = api.post(settings, "/api/v1/config", api.jsonBody { put("default_model", model) }) { body ->
            api.json.decodeFromString<Envelope<JsonObject>>(body).toAckResult()
        }
        result.onSuccess { patchCachedConfig(settings) { it.copy(defaultModel = model) } }
        return result
    }

    /** Sets the server-wide default permission mode (ticket 142 — the wire value:
     * manual/yolo/auto); same contract as [setDefaultModel]. */
    suspend fun setDefaultPermissionMode(settings: ServerSettings, mode: String): Result<Unit> {
        val result = api.post(settings, "/api/v1/config", api.jsonBody { put("default_permission_mode", mode) }) { body ->
            api.json.decodeFromString<Envelope<JsonObject>>(body).toAckResult()
        }
        result.onSuccess { patchCachedConfig(settings) { it.copy(defaultPermissionMode = mode) } }
        return result
    }

    /** Sets the server-wide default thinking effort; same contract as [setDefaultModel]. */
    suspend fun setDefaultEffort(settings: ServerSettings, effort: String): Result<Unit> {
        val body = api.jsonBody { putJsonObject("thinking") { put("effort", effort) } }
        val result = api.post(settings, "/api/v1/config", body) { responseBody ->
            api.json.decodeFromString<Envelope<JsonObject>>(responseBody).toAckResult()
        }
        result.onSuccess { patchCachedConfig(settings) { it.copy(defaultEffort = effort) } }
        return result
    }

    /**
     * The cached `support_efforts` of [model], or null when the models list is not
     * cached for this server or the model has no effort levels. The cache is read,
     * never populated here: the send path must not block on a models fetch.
     */
    fun cachedSupportEfforts(settings: ServerSettings, model: String?): List<String>? {
        val models = cachedModels?.takeIf { it.first == api.baseUrl(settings) }?.second ?: return null
        return models.firstOrNull { it.model == model }?.supportEfforts
    }

    /**
     * The `model_switch_confirm` cloud policy (2.3.0, ticket 167): fetched fresh on
     * every chat model-data load — the server re-reads the cloud value per request
     * and the app deliberately invents no TTL. A null config (unregistered name,
     * absent cloud value) means no gate.
     */
    suspend fun modelSwitchConfirm(settings: ServerSettings): Result<SwitchConfirm?> =
        fetchClientConfig(settings, "model_switch_confirm") { it.toSwitchConfirm() }

    /**
     * The `effort_switch_confirm` cloud policy (2.3.3, ticket 170): effort
     * descriptions plus the optional change-confirmation rules. Same fresh-fetch,
     * null-means-no-policy contract as [modelSwitchConfirm].
     */
    suspend fun effortSwitchConfig(settings: ServerSettings): Result<EffortSwitchConfig?> =
        fetchClientConfig(settings, "effort_switch_confirm") { it.toEffortSwitchConfig() }

    private suspend fun <T> fetchClientConfig(
        settings: ServerSettings,
        name: String,
        map: (ClientConfigDto) -> T,
    ): Result<T?> =
        api.post(settings, "/api/v1/client_configs", api.jsonBody { put("name", name) }) { body ->
            api.json.decodeFromString<Envelope<ClientConfigData>>(body).toNullableResult()
                .map { data -> data?.config?.let(map) }
        }

    /** Applies [patch] to the cached config of [settings]' server only; an empty or
     * other-server cache is left alone — the next [config] read refetches anyway. */
    private fun patchCachedConfig(settings: ServerSettings, patch: (ServerConfig) -> ServerConfig) {
        val baseUrl = api.baseUrl(settings)
        cachedConfig?.let { if (it.first == baseUrl) cachedConfig = it.first to patch(it.second) }
    }

    /**
     * Drops the caches (models list + config projection) of [settings]' server, so the
     * next read refetches. Called on the global `event.config.changed` /
     * `event.model_catalog.changed` frames (0.39.1, docs/server-api.md — ticket 110):
     * the catalog and config drift under any client's writes, and the frames' payloads
     * are not applied — invalidation only.
     */
    fun invalidate(settings: ServerSettings) {
        val baseUrl = api.baseUrl(settings)
        cachedModels?.let { if (it.first == baseUrl) cachedModels = null }
        cachedConfig?.let { if (it.first == baseUrl) cachedConfig = null }
    }

    /** GET /api/v1/models → `data.items` (docs/server-api.md). */
    private suspend fun fetchModels(settings: ServerSettings): Result<List<ServerModel>> =
        api.get(settings, "/api/v1/models") { body ->
            api.json.decodeFromString<Envelope<ModelListData>>(body).toResult().map { data ->
                data.items.map { it.toDomain() }
            }
        }

    /** GET /api/v1/config → `data` (null when the server serves a data-less success). */
    private suspend fun fetchConfig(settings: ServerSettings): Result<ServerConfig> =
        api.get(settings, "/api/v1/config") { body ->
            api.json.decodeFromString<Envelope<ConfigData>>(body).toNullableResult().map { data ->
                ServerConfig(
                    defaultModel = data?.defaultModel?.takeIf { it.isNotBlank() },
                    defaultEffort = data?.thinking?.effort?.takeIf { it.isNotBlank() },
                    defaultPermissionMode = data?.defaultPermissionMode?.takeIf { it.isNotBlank() },
                    providers = data?.providers.orEmpty().map { (name, dto) ->
                        ServerProvider(
                            name = name,
                            type = dto.type,
                            baseUrl = dto.baseUrl,
                            hasApiKey = dto.hasApiKey,
                        )
                    },
                    modelAliases = data?.models.orEmpty().mapNotNull { (alias, dto) ->
                        dto.model?.takeIf { it.isNotBlank() }?.let { alias to it }
                    }.toMap(),
                )
            }
        }

    private fun ModelDto.toDomain(): ServerModel = ServerModel(
        provider = provider,
        model = model,
        displayName = displayName,
        maxContextSize = maxContextSize,
        supportEfforts = supportEfforts,
        defaultEffort = defaultEffort,
        upgrade = upgrade?.let { dto ->
            ModelUpgrade(
                model = dto.model,
                migrationMarkdown = dto.migrationMarkdown,
                // Tolerated as absent: a malformed date is the same non-guidance as none.
                // The schema says ISO instant; a date-only stamp degrades to UTC midnight.
                retirementAt = dto.retirementAt?.let { raw ->
                    runCatching { Instant.parse(raw) }.getOrNull()
                        ?: runCatching { LocalDate.parse(raw).atStartOfDay(ZoneOffset.UTC).toInstant() }.getOrNull()
                },
            )
        },
    )
}
