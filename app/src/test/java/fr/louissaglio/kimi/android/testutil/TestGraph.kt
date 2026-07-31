package fr.louissaglio.kimi.android.testutil

import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import fr.louissaglio.kimi.android.data.chat.ChatApi
import fr.louissaglio.kimi.android.data.chat.ChatMessageMapper
import fr.louissaglio.kimi.android.data.chat.ChatRepository
import fr.louissaglio.kimi.android.data.chat.ModelsRepository
import fr.louissaglio.kimi.android.data.chat.SessionPrefsStore
import fr.louissaglio.kimi.android.data.chat.SessionSocket
import fr.louissaglio.kimi.android.data.chat.TodoParser
import fr.louissaglio.kimi.android.data.network.ApiClient
import fr.louissaglio.kimi.android.data.network.ConnectionChecker
import fr.louissaglio.kimi.android.data.sessions.SessionsRepository
import fr.louissaglio.kimi.android.ui.chat.ChatTimeline
import fr.louissaglio.kimi.android.ui.chat.SubagentStreamStore
import kotlinx.serialization.json.Json
import okhttp3.OkHttpClient

/**
 * Hand-built twin of the ticket-067 Hilt graph: unit tests drive the real data-layer
 * objects against MockWebServer (the fake strategy is real collaborators, not mocks),
 * so they assemble by hand what AppModule now provides in production. One Json per
 * built graph, like AppModule's singleton.
 */

fun testTodoParser(): TodoParser = TodoParser(Json { ignoreUnknownKeys = true })

fun testChatTimeline(historyBacked: Boolean = true): ChatTimeline =
    ChatTimeline(testTodoParser(), historyBacked)

fun testChatRepository(dataStore: DataStore<Preferences>): ChatRepository {
    val json = Json { ignoreUnknownKeys = true }
    val api = ApiClient(OkHttpClient(), json)
    val mapper = ChatMessageMapper(json)
    return ChatRepository(
        ChatApi(api, mapper),
        ModelsRepository(api),
        SessionPrefsStore(dataStore),
        SessionSocket(OkHttpClient(), json, mapper),
    )
}

fun testSessionsRepository(): SessionsRepository {
    val json = Json { ignoreUnknownKeys = true }
    return SessionsRepository(ApiClient(OkHttpClient(), json), json)
}

fun testConnectionChecker(): ConnectionChecker {
    val json = Json { ignoreUnknownKeys = true }
    return ConnectionChecker(ApiClient(OkHttpClient(), json), json)
}

fun testSubagentStreamStore(streamCoalesceWindowMillis: Long): SubagentStreamStore {
    val json = Json { ignoreUnknownKeys = true }
    return SubagentStreamStore(json, TodoParser(json), streamCoalesceWindowMillis)
}
