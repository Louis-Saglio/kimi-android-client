package fr.louissaglio.kimi.android.data.network

import fr.louissaglio.kimi.android.data.settings.ServerSettings
import java.io.IOException
import javax.inject.Inject
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import okhttp3.OkHttpClient
import okhttp3.Request

@Serializable
data class ApiEnvelope(
    val code: Int = 0,
    val msg: String = "",
)

class ConnectionChecker @Inject constructor(
    private val client: OkHttpClient,
    private val json: Json,
) {
    sealed interface Result {
        data object Success : Result
        data object InvalidUrl : Result
        data object InvalidToken : Result
        data object AuthFailed : Result
        data class HttpError(val code: Int) : Result
        data class ApiError(val code: Int, val msg: String) : Result
        data class Unreachable(val detail: String) : Result
    }

    suspend fun check(settings: ServerSettings): Result = withContext(Dispatchers.IO) {
        val url = (settings.serverUrl.trim().trimEnd('/') + "/api/v1/meta").toHttpUrlOrNull()
            ?: return@withContext Result.InvalidUrl
        val request = try {
            Request.Builder().url(url).apply {
                if (settings.hasCredentials) {
                    header("Authorization", "Bearer ${settings.token.trim()}")
                }
            }.build()
        } catch (e: IllegalArgumentException) {
            // OkHttp rejects control/non-latin-1 characters in header values.
            return@withContext Result.InvalidToken
        }
        try {
            client.newCall(request).execute().use { response ->
                when {
                    response.code == 401 -> Result.AuthFailed
                    !response.isSuccessful -> Result.HttpError(response.code)
                    else -> parseEnvelope(response.body?.string().orEmpty())
                }
            }
        } catch (e: IOException) {
            Result.Unreachable(e.message ?: e.javaClass.simpleName)
        }
    }

    // Auth already passed at the HTTP layer; the envelope only surfaces server-level error codes.
    private fun parseEnvelope(body: String): Result {
        val envelope = runCatching { json.decodeFromString<ApiEnvelope>(body) }.getOrNull()
            ?: return Result.Success
        return if (envelope.code == 0) Result.Success else Result.ApiError(envelope.code, envelope.msg)
    }
}
