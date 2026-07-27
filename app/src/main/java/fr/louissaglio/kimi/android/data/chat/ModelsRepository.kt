package fr.louissaglio.kimi.android.data.chat

import fr.louissaglio.kimi.android.data.DataError
import fr.louissaglio.kimi.android.data.network.ApiClient
import fr.louissaglio.kimi.android.data.network.Envelope
import fr.louissaglio.kimi.android.data.network.toResult
import fr.louissaglio.kimi.android.data.settings.ServerSettings

/** Models/config REST (`GET /api/v1/models`, `GET /api/v1/config`) with the
 * per-server-URL caches the send path reads. */
internal class ModelsRepository(
    private val api: ApiClient,
) {
    @Volatile
    private var cachedModels: Pair<String, List<ServerModel>>? = null

    @Volatile
    private var cachedDefaultModel: Pair<String, String?>? = null

    /**
     * The models the server offers (`GET /api/v1/models`), for the chat screen's model
     * picker. Cached per server URL exactly like [defaultModel] (a settings change
     * refetches); fetch failures are not cached, so the next open retries.
     */
    suspend fun models(settings: ServerSettings): Result<List<ServerModel>> {
        val baseUrl = api.baseUrl(settings)
        cachedModels?.let { if (it.first == baseUrl) return Result.success(it.second) }
        val result = fetchModels(settings)
        result.onSuccess { cachedModels = baseUrl to it }
        return result
    }

    /**
     * The model to send with prompts: 0.28.0+ no longer falls back to the server's
     * `default_model` for sessions with an empty `agent_config.model` (the turn fails
     * with `model.not_configured`), so the app passes the server default explicitly
     * (`GET /api/v1/config`). Resolved values — including "server has no default" — are
     * cached per server URL (a settings change refetches); fetch failures are not
     * cached and degrade to the pre-0.28 behavior: the prompt goes out without a model.
     */
    suspend fun defaultModel(settings: ServerSettings): String? {
        val baseUrl = api.baseUrl(settings)
        cachedDefaultModel?.let { if (it.first == baseUrl) return it.second }
        val result = fetchDefaultModel(settings)
        result.onSuccess { cachedDefaultModel = baseUrl to it }
        return result.getOrNull()
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

    /** GET /api/v1/models → `data.items` (docs/server-api.md). */
    private suspend fun fetchModels(settings: ServerSettings): Result<List<ServerModel>> =
        api.get(settings, "/api/v1/models") { body ->
            api.json.decodeFromString<Envelope<ModelListData>>(body).toResult().map { data ->
                data.items.map { it.toDomain() }
            }
        }

    /** GET /api/v1/config → `data.default_model` (null when the server has none). */
    private suspend fun fetchDefaultModel(settings: ServerSettings): Result<String?> =
        api.get(settings, "/api/v1/config") { body ->
            val envelope = api.json.decodeFromString<Envelope<ConfigData>>(body)
            when {
                envelope.code != 0 -> Result.failure(DataError.Api(envelope.code, envelope.msg))
                else -> Result.success(envelope.data?.defaultModel?.takeIf { it.isNotBlank() })
            }
        }

    private fun ModelDto.toDomain(): ServerModel = ServerModel(
        provider = provider,
        model = model,
        displayName = displayName,
        maxContextSize = maxContextSize,
        supportEfforts = supportEfforts,
        defaultEffort = defaultEffort,
    )
}
