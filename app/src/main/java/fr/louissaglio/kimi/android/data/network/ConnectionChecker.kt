package fr.louissaglio.kimi.android.data.network

import android.util.Log
import fr.louissaglio.kimi.android.data.DataError
import fr.louissaglio.kimi.android.data.settings.ServerSettings
import javax.inject.Inject
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import okhttp3.Request

class ConnectionChecker @Inject constructor(
    private val api: ApiClient,
    private val json: Json,
) {

    /** Deliberately not [DataError]: the settings screen reports distinct UI states. */
    sealed interface Result {
        data object Success : Result
        data object InvalidUrl : Result
        data object InvalidToken : Result
        data object AuthFailed : Result
        data class HttpError(val code: Int) : Result
        data class ApiError(val code: Int, val msg: String) : Result
        data class Unreachable(val detail: String) : Result
    }

    suspend fun check(settings: ServerSettings): Result {
        val url = (api.baseUrl(settings) + "/api/v1/meta").toHttpUrlOrNull()
            ?: return Result.InvalidUrl
        val request = try {
            Request.Builder().url(url).apply {
                if (settings.hasCredentials) {
                    header("Authorization", "Bearer ${settings.token.trim()}")
                }
            }.build()
        } catch (e: IllegalArgumentException) {
            // OkHttp rejects control/non-latin-1 characters in header values.
            return Result.InvalidToken
        }
        return api.execute(request) { body -> kotlin.Result.success(parseEnvelope(body)) }
            .getOrElse { it.toCheckerResult() }
    }

    private fun Throwable.toCheckerResult(): Result = when (this) {
        DataError.Auth -> Result.AuthFailed
        is DataError.Http -> Result.HttpError(code)
        is DataError.Network -> Result.Unreachable(cause.message ?: cause.javaClass.simpleName)
        else -> Result.Unreachable(message ?: javaClass.simpleName)
    }

    // Auth already passed at the HTTP layer; the envelope only surfaces server-level error codes.
    private fun parseEnvelope(body: String): Result {
        val envelope = runCatching { json.decodeFromString<Envelope<JsonObject>>(body) }
            .onFailure { Log.w(TAG, "Unparseable /api/v1/meta envelope; treating as success", it) }
            .getOrNull()
            ?: return Result.Success
        return if (envelope.code == 0) Result.Success else Result.ApiError(envelope.code, envelope.msg)
    }

    private companion object {
        const val TAG = "ConnectionChecker"
    }
}
