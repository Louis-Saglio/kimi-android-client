package fr.louissaglio.kimi.android.data.chat

/**
 * The server-wide config projection the Settings "Models" section edits (ticket 109,
 * `GET`/`POST /api/v1/config`, docs/server-api.md): the default model, the default
 * thinking effort, and the providers (read-only — managing them is out of scope for
 * the phone client). Nulls mean the server has no such value.
 */
data class ServerConfig(
    val defaultModel: String?,
    val defaultEffort: String?,
    val providers: List<ServerProvider>,
)

/** One configured provider; [name] is its key in the config's `providers` map.
 * Credentials never cross the wire — [hasApiKey] is all the server exposes. */
data class ServerProvider(
    val name: String,
    val type: String,
    val baseUrl: String,
    val hasApiKey: Boolean,
)
