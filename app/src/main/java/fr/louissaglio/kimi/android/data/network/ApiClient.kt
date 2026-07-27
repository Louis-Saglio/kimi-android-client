package fr.louissaglio.kimi.android.data.network

import fr.louissaglio.kimi.android.data.DataError
import fr.louissaglio.kimi.android.data.settings.ServerSettings
import java.io.IOException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.SerializationException
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObjectBuilder
import kotlinx.serialization.json.buildJsonObject
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.Response

/**
 * The one scaffold every REST call of the data layer goes through: base-URL
 * resolution, request execution on the IO dispatcher, and the error boundary —
 * invalid URLs, transport failures, HTTP statuses and decode failures all come
 * back as [Result.failure] of [DataError], so each endpoint is a few lines of
 * actual business. Deliberately dumb: no caching, no retries, no framework.
 */
class ApiClient(
    private val client: OkHttpClient,
    val json: Json,
) {
    /** The server URL every endpoint resolves its path against (surrounding
     * whitespace and trailing slashes trimmed). */
    fun baseUrl(settings: ServerSettings): String = settings.serverUrl.trim().trimEnd('/')

    /** A JSON POST body; every JSON endpoint of the server posts an object. */
    fun jsonBody(build: JsonObjectBuilder.() -> Unit): RequestBody =
        buildJsonObject(build).toString().toRequestBody(JSON_MEDIA_TYPE)

    /** GETs [path] and parses the successful response's body string. */
    suspend fun <T> get(settings: ServerSettings, path: String, parse: (String) -> Result<T>): Result<T> =
        when (val request = request(settings, path)) {
            null -> Result.failure(DataError.InvalidUrl)
            else -> execute(request, parse)
        }

    /** POSTs [body] to [path] and parses the successful response's body string. */
    suspend fun <T> post(
        settings: ServerSettings,
        path: String,
        body: RequestBody,
        parse: (String) -> Result<T>,
    ): Result<T> =
        when (val request = request(settings, path, body)) {
            null -> Result.failure(DataError.InvalidUrl)
            else -> execute(request, parse)
        }

    /** GETs [path] and reads the successful response directly (binary downloads). */
    suspend fun <T> getResponse(settings: ServerSettings, path: String, read: (Response) -> Result<T>): Result<T> =
        when (val request = request(settings, path)) {
            null -> Result.failure(DataError.InvalidUrl)
            else -> executeResponse(request, read)
        }

    /** The request for [path] against the trimmed server URL; null = invalid URL. */
    fun request(settings: ServerSettings, path: String, body: RequestBody? = null): Request? = try {
        Request.Builder()
            .url(baseUrl(settings) + path)
            .apply { if (body != null) post(body) }
            .build()
    } catch (e: IllegalArgumentException) {
        null
    }

    /** Executes a ready-built [request] and parses the successful body string. */
    suspend fun <T> execute(request: Request, parse: (String) -> Result<T>): Result<T> =
        executeResponse(request) { response -> parse(response.body?.string().orEmpty()) }

    /**
     * The core: executes [request] on the IO dispatcher and maps failures to
     * [DataError] — 401 → Auth, other non-2xx → Http, IOException → Network, and a
     * [SerializationException] out of [read] → MalformedResponse.
     */
    suspend fun <T> executeResponse(request: Request, read: (Response) -> Result<T>): Result<T> =
        withContext(Dispatchers.IO) {
            try {
                client.newCall(request).execute().use { response ->
                    when {
                        response.code == 401 -> Result.failure(DataError.Auth)
                        !response.isSuccessful -> Result.failure(DataError.Http(response.code))
                        else -> try {
                            read(response)
                        } catch (e: SerializationException) {
                            Result.failure(DataError.MalformedResponse(e))
                        }
                    }
                }
            } catch (e: IOException) {
                Result.failure(DataError.Network(e))
            }
        }

    private companion object {
        val JSON_MEDIA_TYPE = "application/json; charset=utf-8".toMediaType()
    }
}
