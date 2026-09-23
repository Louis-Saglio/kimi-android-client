package fr.louissaglio.kimi.android.data.sessions

import java.time.Instant

/** Domain model for a session, as exposed by [SessionsRepository] to ViewModels. */
data class Session(
    val id: String,
    /** Null when the server sent no usable title; the UI falls back to [cwd]. */
    val title: String?,
    val cwd: String?,
    val updatedAt: Instant,
    val isRunning: Boolean,
    /** True while the session is blocked on an approval or a question (see [isAwaitingInteraction]). */
    val isAwaitingInteraction: Boolean,
    /** True when the session's last turn did not complete ("cancelled" — abort, crash,
     * server restart mid-turn); the row's interrupted badge (ticket 159). Fed by the REST
     * `last_turn_reason` and the WS turn-end frame; a later completed turn clears it. */
    val isLastTurnInterrupted: Boolean = false,
)

/**
 * The one "needs me" badge rule (ticket 087): the REST mapping and the WebSocket
 * patch must agree, so both go through this — approvals AND questions read as
 * awaiting interaction.
 */
fun isAwaitingInteraction(pendingInteraction: String): Boolean = pendingInteraction != "none"

/**
 * Label for one session: title → cwd basename → id, shared by the session list row
 * and the chat top bar. A cwd of "/" (basename "") still falls back to the id.
 */
val Session.displayName: String
    get() = displayNameFor(id, title, cwd)

/** [Session.displayName] for callers holding the raw fields (e.g. navigation arguments). */
fun displayNameFor(id: String, title: String?, cwd: String?): String =
    title?.takeIf { it.isNotBlank() } ?: cwd?.trimEnd('/')?.substringAfterLast('/')?.takeIf { it.isNotBlank() } ?: id

/** One page of the session list; [hasMore] mirrors the envelope's `has_more` flag. */
data class SessionPage(
    val items: List<Session>,
    val hasMore: Boolean,
)

/**
 * One archived session row (ticket 132): unlike the live-list [Session] this keeps the
 * server's archive stamp — the archived screen's rows display it. The live state flags
 * are dropped: an archived session is never running or awaiting interaction.
 */
data class ArchivedSession(
    val id: String,
    /** Null when the server sent no usable title; the UI falls back to [cwd]. */
    val title: String?,
    val cwd: String?,
    /** Server `archived_at`; null when absent — the row then falls back to [updatedAt]. */
    val archivedAt: Instant?,
    /** `updated_at`, untouched by archiving — a stale session archived today sorts low. */
    val updatedAt: Instant,
) {
    /** The archive time the row displays. */
    val archiveTime: Instant get() = archivedAt ?: updatedAt
}

/** Label for one archived session: the same title → cwd basename → id fallback as the main list. */
val ArchivedSession.displayName: String
    get() = displayNameFor(id, title, cwd)

/** Workspace folder basename for the row's supporting line (ticket 132): the main
 * list's cwd-shortening rule, without the 114 cross-row disambiguation. */
val ArchivedSession.workspaceFolder: String?
    get() = cwd?.trimEnd('/')?.substringAfterLast('/')?.takeIf { it.isNotBlank() }

/** One page of the archived session list (ticket 132); [hasMore] mirrors `has_more`. */
data class ArchivedSessionPage(
    val items: List<ArchivedSession>,
    val hasMore: Boolean,
)

/**
 * One workspace group of the grouped session list (ticket 115). [sessions] holds only
 * the group's first page (5 per workspace); [total] is the workspace's full matching
 * count server-side, shown in the group header.
 */
data class SessionGroup(
    val workspaceId: String,
    val cwd: String,
    val sessions: List<Session>,
    val total: Int,
)

/** Domain model for a workspace, as exposed by [SessionsRepository] to ViewModels. */
data class Workspace(
    val id: String,
    val root: String,
    /** May be empty server-side; the UI falls back to [root] for the label. */
    val name: String,
    val lastOpenedAt: Instant,
)
