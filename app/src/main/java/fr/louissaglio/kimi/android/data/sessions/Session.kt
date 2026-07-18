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
)
