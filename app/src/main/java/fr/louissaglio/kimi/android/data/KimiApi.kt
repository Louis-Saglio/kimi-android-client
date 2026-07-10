package fr.louissaglio.kimi.android.data

import fr.louissaglio.kimi.android.data.model.ApiResponse
import fr.louissaglio.kimi.android.data.model.FileUploadResponse
import fr.louissaglio.kimi.android.data.model.Message
import fr.louissaglio.kimi.android.data.model.MessageListResponse
import fr.louissaglio.kimi.android.data.model.PromptRequest
import fr.louissaglio.kimi.android.data.model.PromptResponse
import fr.louissaglio.kimi.android.data.model.Session
import fr.louissaglio.kimi.android.data.model.SessionListResponse
import fr.louissaglio.kimi.android.data.model.SessionMetadata
import kotlinx.serialization.KSerializer
import kotlinx.serialization.SerialName
import kotlinx.serialization.builtins.serializer
import kotlinx.serialization.serializer
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.Credentials
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.MultipartBody
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import java.io.IOException
import java.util.concurrent.TimeUnit

class KimiApi(
    serverUrl: String,
    username: String,
    password: String
) {
    private val baseUrl = serverUrl.trimEnd('/')
    private val credentials = Credentials.basic(username, password)

    private val client = OkHttpClient.Builder()
        .connectTimeout(30, TimeUnit.SECONDS)
        .readTimeout(60, TimeUnit.SECONDS)
        .writeTimeout(60, TimeUnit.SECONDS)
        .build()

    private val jsonMediaType = "application/json; charset=utf-8".toMediaType()

    @Throws(IOException::class)
    suspend fun meta(): ServerMeta {
        val body = get("/api/v1/meta")
        return parseWrapped(body, ServerMeta.serializer())
    }

    @Throws(IOException::class)
    suspend fun listSessions(): List<Session> {
        val body = get("/api/v1/sessions")
        val wrapped = parseWrapped(body, SessionListResponse.serializer())
        return wrapped.items
    }

    @Throws(IOException::class)
    suspend fun createSession(cwd: String = "/home/ubuntu"): Session {
        val json = kimiJson.encodeToString(
            kotlinx.serialization.serializer<SessionMetadata>(),
            SessionMetadata(cwd = cwd)
        )
        val payload = """{"metadata":$json}"""
        val body = post("/api/v1/sessions", payload)
        return parseWrapped(body, Session.serializer())
    }

    @Throws(IOException::class)
    suspend fun getSession(sessionId: String): Session {
        val body = get("/api/v1/sessions/${urlEncode(sessionId)}")
        return parseWrapped(body, Session.serializer())
    }

    @Throws(IOException::class)
    suspend fun listMessages(sessionId: String): List<Message> {
        val body = get("/api/v1/sessions/${urlEncode(sessionId)}/messages")
        val wrapped = parseWrapped(body, MessageListResponse.serializer())
        return wrapped.items
    }

    @Throws(IOException::class)
    suspend fun submitPrompt(sessionId: String, request: PromptRequest): PromptResponse {
        val payload = kimiJson.encodeToString(PromptRequest.serializer(), request)
        val body = post(
            "/api/v1/sessions/${urlEncode(sessionId)}/prompts",
            payload
        )
        return parseWrapped(body, PromptResponse.serializer())
    }

    @Throws(IOException::class)
    suspend fun abortPrompt(sessionId: String, promptId: String) {
        post(
            "/api/v1/sessions/${urlEncode(sessionId)}/prompts/${urlEncode(promptId)}:abort",
            ""
        )
    }

    @Throws(IOException::class)
    suspend fun archiveSession(sessionId: String) {
        post("/api/v1/sessions/${urlEncode(sessionId)}:archive", "")
    }

    @Throws(IOException::class)
    suspend fun uploadFile(name: String, bytes: ByteArray, mediaType: String): FileUploadResponse {
        val body = MultipartBody.Builder()
            .setType(MultipartBody.FORM)
            .addFormDataPart(
                "file",
                name,
                bytes.toRequestBody(mediaType.toMediaType())
            )
            .addFormDataPart("name", name)
            .build()
        val responseBody = post("/api/v1/files", body)
        return parseWrapped(responseBody, FileUploadResponse.serializer())
    }

    fun fileUrl(fileId: String): String {
        return "$baseUrl/api/v1/files/${urlEncode(fileId)}"
    }

    @Throws(IOException::class)
    private suspend fun get(path: String): String = withContext(Dispatchers.IO) {
        val request = Request.Builder()
            .url(buildUrl(path))
            .header("Authorization", credentials)
            .header("Accept", "application/json")
            .get()
            .build()
        val response = client.newCall(request).execute().also { checkResponse(it) }
        response.body?.string() ?: throw IOException("empty response")
    }

    @Throws(IOException::class)
    private suspend fun post(path: String, jsonBody: String): String {
        return post(path, jsonBody.toRequestBody(jsonMediaType))
    }

    @Throws(IOException::class)
    private suspend fun post(path: String, body: okhttp3.RequestBody): String = withContext(Dispatchers.IO) {
        val request = Request.Builder()
            .url(buildUrl(path))
            .header("Authorization", credentials)
            .header("Accept", "application/json")
            .post(body)
            .build()
        val response = client.newCall(request).execute().also { checkResponse(it) }
        response.body?.string() ?: throw IOException("empty response")
    }

    @Throws(IOException::class)
    private fun checkResponse(response: okhttp3.Response) {
        if (response.isSuccessful) return
        when (response.code) {
            401 -> throw IOException("Authentication failed: check username and password")
            403 -> throw IOException("Access denied")
            404 -> throw IOException("Server endpoint not found")
            in 500..599 -> throw IOException("Server error ${response.code}")
            else -> throw IOException("HTTP error ${response.code}")
        }
    }

    private fun buildUrl(path: String): String {
        return "$baseUrl$path"
    }

    private fun urlEncode(value: String): String {
        return java.net.URLEncoder.encode(value, "UTF-8")
    }

    private fun <T> parseWrapped(json: String, serializer: KSerializer<T>): T {
        val wrapped = kimiJson.decodeFromString(ApiResponse.serializer(serializer), json)
        if (wrapped.code != 0) {
            throw IOException("api error: ${wrapped.msg} (code ${wrapped.code})")
        }
        return wrapped.data ?: throw IOException("api response missing data")
    }

    @kotlinx.serialization.Serializable
    data class ServerMeta(
        @SerialName("server_version") val serverVersion: String,
        val capabilities: Map<String, Boolean> = emptyMap()
    )
}
