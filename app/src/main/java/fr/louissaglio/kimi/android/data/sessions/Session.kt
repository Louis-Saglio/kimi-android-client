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
    /** True while the session is blocked on a permission approval (`pending_interaction == "approval"`). */
    val isAwaitingApproval: Boolean,
)

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

/** Domain model for a workspace, as exposed by [SessionsRepository] to ViewModels. */
data class Workspace(
    val id: String,
    val root: String,
    /** May be empty server-side; the UI falls back to [root] for the label. */
    val name: String,
    val lastOpenedAt: Instant,
)
