package fr.louissaglio.kimi.android.data.chat

import java.time.Instant

/**
 * One available model, as exposed by [ChatRepository.getModels] to ViewModels
 * (`GET /api/v1/models`, docs/server-api.md). [model] is the id sent with prompts;
 * [displayName] is the human label. [supportEfforts]/[defaultEffort] are null on
 * models without thinking-effort levels (only K3 has them on 0.29.0). [upgrade] is
 * the catalog item's optional retirement guidance (2.3.0, ticket 166) — the picker
 * shows it read-only and never acts on it.
 */
data class ServerModel(
    val provider: String,
    val model: String,
    val displayName: String,
    val maxContextSize: Long,
    val supportEfforts: List<String>? = null,
    val defaultEffort: String? = null,
    val upgrade: ModelUpgrade? = null,
)

/** A catalog item's `upgrade` object (ticket 166): the replacement [model] alias, the
 * user-facing migration [migrationMarkdown], and the optional [retirementAt] date. */
data class ModelUpgrade(
    val model: String,
    val migrationMarkdown: String,
    val retirementAt: Instant? = null,
)
