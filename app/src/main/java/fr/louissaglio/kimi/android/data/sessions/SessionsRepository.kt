package fr.louissaglio.kimi.android.data.sessions

import fr.louissaglio.kimi.android.data.DataError
import fr.louissaglio.kimi.android.data.network.Envelope
import fr.louissaglio.kimi.android.data.settings.ServerSettings
import java.io.IOException
import java.time.Instant
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.SerializationException
import kotlinx.serialization.json.Json
import okhttp3.OkHttpClient
import okhttp3.Request

@Singleton
class SessionsRepository @Inject constructor(
    private val client: OkHttpClient,
    private val json: Json,
) {
    /**
     * Fetches the session list from `GET /api/v1/sessions`. Settings are passed per
     * call (same pattern as ConnectionChecker) so saved-settings edits apply immediately.
     * This is the error boundary: failures come back as [Result.failure] of [DataError].
     */
    suspend fun listSessions(settings: ServerSettings): Result<List<Session>> =
        withContext(Dispatchers.IO) {
            val request = try {
                Request.Builder()
                    .url(settings.serverUrl.trim().trimEnd('/') + "/api/v1/sessions")
                    .build()
            } catch (e: IllegalArgumentException) {
                return@withContext Result.failure(DataError.InvalidUrl)
            }
            try {
                client.newCall(request).execute().use { response ->
                    when {
                        response.code == 401 -> Result.failure(DataError.Auth)
                        !response.isSuccessful -> Result.failure(DataError.Http(response.code))
                        else -> parseBody(response.body?.string().orEmpty())
                    }
                }
            } catch (e: IOException) {
                Result.failure(DataError.Network(e))
            }
        }

    private fun parseBody(body: String): Result<List<Session>> = try {
        val envelope = json.decodeFromString<Envelope<SessionListData>>(body)
        when {
            envelope.code != 0 -> Result.failure(DataError.Api(envelope.code, envelope.msg))
            envelope.data == null -> Result.failure(DataError.Api(envelope.code, "missing data"))
            else -> Result.success(envelope.data.items.map { it.toDomain() })
        }
    } catch (e: SerializationException) {
        Result.failure(DataError.MalformedResponse(e))
    }

    private fun SessionDto.toDomain(): Session = Session(
        id = id,
        title = title?.takeIf { it.isNotBlank() },
        cwd = metadata?.cwd,
        updatedAt = runCatching { Instant.parse(updatedAt) }.getOrDefault(Instant.EPOCH),
        isRunning = status == "running",
    )
}
