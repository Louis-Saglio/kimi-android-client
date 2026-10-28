package fr.louissaglio.kimi.android.data.sessions

import fr.louissaglio.kimi.android.data.settings.ServerSettings
import kotlinx.coroutines.flow.Flow

/**
 * The sessions-screen WebSocket (ticket 087): one connection follows every loaded
 * session while the list is visible, so a row lights up the moment its session turns
 * busy, starts waiting on an approval/question, or gets (re-)titled — no refresh
 * needed. The screen's ON_START/ON_STOP gate bounds the connection's lifetime.
 *
 * An interface so ViewModel tests drive a fake flow; the OkHttp implementation is
 * [OkHttpSessionListSocket].
 */
interface SessionListSocket {

    /**
     * Streams [SessionListEvent]s for the sessions in [sessionIds]. One collection =
     * one socket owned here (not by the UI); cancelling the collection closes it.
     *
     * [sessionIds] is the live set of loaded session ids: on connect, one subscribe
     * frame carries the current set — NO cursors (the REST load is the baseline;
     * events only patch) — and changes while connected send follow-up subscribes for
     * the new ids only. On a drop the connection retries with exponential backoff
     * (1 s doubling to 30 s); a successful reconnect resubscribes the full set and
     * emits [SessionListEvent.Reconnected] once so the collector heals the missed
     * window via REST.
     */
    fun observe(settings: ServerSettings, sessionIds: Flow<Set<String>>): Flow<SessionListEvent>
}

/** One list-screen event: a live patch for one row, a row insertion (ticket 129) or
 * removal, a global cache-invalidation signal (ticket 110), or the reconnect-heal
 * signal. */
sealed interface SessionListEvent {

    /**
     * A dropped (or initially failed) connection was re-established and every loaded
     * id resubscribed. Events in the gap are lost (no cursors — see
     * [SessionListSocket.observe]), so the collector re-syncs the list from REST once.
     */
    data object Reconnected : SessionListEvent

    /**
     * `event.session.work_changed` (docs/server-api.md): the running spinner, the
     * needs-me badge and the unread marker (ticket 145) of one session.
     */
    data class WorkChanged(
        val sessionId: String,
        val busy: Boolean,
        /** Server runtime state: "none" | "approval" | "question". */
        val pendingInteraction: String,
        /**
         * Turn-end reason on the idle frame ("completed" | "cancelled"), null on busy
         * frames. The unread marker (ticket 145) marks a session only when a running
         * row goes idle with "completed" — an abort is the user's own doing.
         */
        val lastTurnReason: String? = null,
    ) : SessionListEvent

    /** `session.meta.updated` (docs/server-api.md): [title] is the patched title. */
    data class MetaUpdated(val sessionId: String, val title: String?) : SessionListEvent

    /**
     * `event.session.archived` (0.38.0, docs/server-api.md): the session was archived
     * — from any client — and its row must leave the list. A global frame: the
     * envelope `session_id` is `__global__`, so [sessionId] comes from the payload.
     */
    data class SessionArchived(val sessionId: String) : SessionListEvent

    /**
     * `event.session.deleted` (0.42.0, docs/server-api.md — ticket 126): the session
     * was deleted from any client and its row must leave the list, exactly like an
     * archive. Same global frame shape as [SessionArchived]: the envelope `session_id`
     * is `__global__`, the real id rides camelCase in the payload.
     */
    data class SessionDeleted(val sessionId: String) : SessionListEvent

    /**
     * `event.session.created` (verified on 0.42.0, docs/server-api.md — ticket 129):
     * a session was created from any client, and [session] is the full REST-shaped
     * object. Not a `__global__` frame: it is broadcast on the new session's own
     * stream, so the envelope `session_id` is the created session — an id this
     * connection is by definition not subscribed to. Fires on plain creates only:
     * a `:fork`'s frame is dropped server-side (the fork is never loaded), so a fork
     * made elsewhere appears on the next refresh — the app's own fork inserts the
     * row itself (ticket 128).
     */
    data class SessionCreated(val session: Session) : SessionListEvent

    /**
     * `event.config.changed` (delivered since 0.39.1, docs/server-api.md): a config
     * write from any client. A global frame (`session_id` is the `__global__`
     * watermark); the payload's full config projection is not decoded — the frame
     * type alone tells the collector to drop the models/config caches so the next
     * read refetches (ticket 110).
     */
    data object ConfigChanged : SessionListEvent

    /**
     * `event.model_catalog.changed` (delivered since 0.39.1, docs/server-api.md): a
     * provider model refresh. Same global shape and same cache-invalidation handling
     * as [ConfigChanged].
     */
    data object ModelCatalogChanged : SessionListEvent
}
