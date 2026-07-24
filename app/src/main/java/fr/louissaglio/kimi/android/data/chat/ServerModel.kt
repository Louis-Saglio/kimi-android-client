package fr.louissaglio.kimi.android.data.chat

/**
 * One available model, as exposed by [ChatRepository.getModels] to ViewModels
 * (`GET /api/v1/models`, docs/server-api.md). [model] is the id sent with prompts;
 * [displayName] is the human label. [supportEfforts]/[defaultEffort] are null on
 * models without thinking-effort levels (only K3 has them on 0.29.0).
 */
data class ServerModel(
    val provider: String,
    val model: String,
    val displayName: String,
    val maxContextSize: Long,
    val supportEfforts: List<String>? = null,
    val defaultEffort: String? = null,
)
