package fr.louissaglio.kimi.android.fakes

import fr.louissaglio.kimi.android.data.sessions.SessionListEvent
import fr.louissaglio.kimi.android.data.sessions.SessionListSocket
import fr.louissaglio.kimi.android.data.settings.ServerSettings
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.onCompletion
import kotlinx.coroutines.flow.onStart

/**
 * Fake [SessionListSocket] for ViewModel tests (ticket 087): the test pushes events
 * via [events]; [collections]/[completions] expose the screen gate (collection starts
 * on ON_START, is cancelled on ON_STOP). The session-ids flow is ignored — subscribe
 * frames are asserted on the real socket in OkHttpSessionListSocketTest.
 */
class FakeSessionListSocket : SessionListSocket {
    val events = MutableSharedFlow<SessionListEvent>(extraBufferCapacity = 16)
    var collections = 0
        private set
    var completions = 0
        private set

    override fun observe(settings: ServerSettings, sessionIds: Flow<Set<String>>): Flow<SessionListEvent> =
        events
            .onStart { collections++ }
            .onCompletion { completions++ }
}
