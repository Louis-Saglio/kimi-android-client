package fr.louissaglio.kimi.android.testutil

import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.preferencesOf
import androidx.datastore.preferences.core.stringPreferencesKey
import fr.louissaglio.kimi.android.data.chat.ChatApi
import fr.louissaglio.kimi.android.data.chat.ChatMessageMapper
import fr.louissaglio.kimi.android.data.chat.ChatRepository
import fr.louissaglio.kimi.android.data.chat.ModelsRepository
import fr.louissaglio.kimi.android.data.chat.SessionPrefsStore
import fr.louissaglio.kimi.android.data.chat.SessionSocket
import fr.louissaglio.kimi.android.data.chat.TodoParser
import fr.louissaglio.kimi.android.data.network.ApiClient
import fr.louissaglio.kimi.android.data.network.ConnectionChecker
import fr.louissaglio.kimi.android.data.sessions.SessionActivityStore
import fr.louissaglio.kimi.android.data.sessions.SessionsRepository
import fr.louissaglio.kimi.android.data.settings.SettingsRepository
import fr.louissaglio.kimi.android.fakes.FakePreferencesDataStore
import fr.louissaglio.kimi.android.ui.chat.ChatTimeline
import fr.louissaglio.kimi.android.ui.chat.SnapshotSync
import fr.louissaglio.kimi.android.ui.chat.SubagentStreamStore
import javax.inject.Provider
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
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

fun testChatRepository(
    dataStore: DataStore<Preferences>,
    activityStore: SessionActivityStore = SessionActivityStore(),
): ChatRepository {
    val json = Json { ignoreUnknownKeys = true }
    val api = ApiClient(OkHttpClient(), json)
    val mapper = ChatMessageMapper(json)
    return ChatRepository(
        ChatApi(api, mapper),
        ModelsRepository(api),
        SessionPrefsStore(dataStore),
        SessionSocket(OkHttpClient(), json, mapper),
        activityStore,
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

fun testSubagentStreamStore(
    streamCoalesceWindowMillis: Long,
    chatRepository: ChatRepository? = null,
    settingsRepository: SettingsRepository? = null,
    snapshotSync: SnapshotSync? = null,
): SubagentStreamStore {
    val json = Json { ignoreUnknownKeys = true }
    // Fallback data layer for tests that never open a sub-agent page: the store's
    // keep-alive collection (ticket 064) only starts on pageOpened without a chat
    // collection, which those tests never do — this is constructor wiring only,
    // pointed at an unroutable URL so an accidental start still hits no network.
    val dataStore = FakePreferencesDataStore(
        preferencesOf(stringPreferencesKey("server_url") to "http://127.0.0.1:1/"),
    )
    val repository = chatRepository ?: testChatRepository(dataStore)
    val settings = settingsRepository
        ?: SettingsRepository(dataStore, CoroutineScope(SupervisorJob() + Dispatchers.Unconfined))
    lateinit var store: SubagentStreamStore
    return SubagentStreamStore(
        json,
        TodoParser(json),
        repository,
        settings,
        // Tests sharing one cold start per session (the production singleton shape)
        // pass the same SnapshotSync to the store AND the ViewModels.
        snapshotSync ?: SnapshotSync(repository, settings, Provider { store }),
        streamCoalesceWindowMillis,
    ).also { store = it }
}

/** The hand-built twin of the production [SnapshotSync] singleton: tests sharing one
 * cold start per session pass the SAME instance to the store and the ViewModels. */
fun testSnapshotSync(
    chatRepository: ChatRepository,
    settingsRepository: SettingsRepository,
    storeProvider: () -> SubagentStreamStore,
): SnapshotSync = SnapshotSync(chatRepository, settingsRepository, Provider { storeProvider() })
