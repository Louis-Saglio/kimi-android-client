package fr.louissaglio.kimi.android.data.chat

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull

/**
 * The two named cloud configs behind `POST /api/v1/client_configs`
 * (docs/server-api.md, Client configs — tickets 167/170): `model_switch_confirm`
 * asks the client to confirm a model switch, `effort_switch_confirm` supplies
 * thinking-effort descriptions and optional confirmation rules. Both are optional
 * cloud policy: a null/disabled config leaves today's immediate selection intact.
 *
 * They share one rule shape: `from`/`to` are a string or a non-empty string array,
 * `*` matches anything, `session_type` is `old`/`new`/`all`, and the localized
 * `hint`/`warning` carry the copy to show.
 */
enum class SessionAge(val wire: String) {
    New("new"),
    Old("old"),
}

/** One confirmation rule; a rule missing a valid `session_type` is dropped while
 * mapping (the server requires the field, the app refuses to guess). */
data class SwitchRule(
    val from: List<String>,
    val to: List<String>,
    val sessionType: String,
    val hint: String?,
    val warning: String?,
) {
    fun matches(fromId: String?, toId: String?, age: SessionAge): Boolean =
        fromId != null && toId != null &&
            (sessionType == "all" || sessionType == age.wire) &&
            matchesSide(from, fromId) && matchesSide(to, toId)

    private fun matchesSide(patterns: List<String>, id: String): Boolean =
        patterns.any { it == "*" || it == id }
}

/** One config's confirmation section: [enabled] plus the ordered rules. */
data class SwitchConfirm(
    val enabled: Boolean,
    val rules: List<SwitchRule>,
) {
    /** The first enabled rule matching the switch, or null — enabled configs with no
     * matching rule keep selection immediate. */
    fun firstMatch(fromId: String?, toId: String?, age: SessionAge): SwitchRule? =
        if (!enabled) null else rules.firstOrNull { it.matches(fromId, toId, age) }
}

/** `effort_switch_confirm` (ticket 170): the per-effort English descriptions and the
 * optional change-confirmation section. */
data class EffortSwitchConfig(
    val descriptions: Map<String, String>,
    val confirm: SwitchConfirm,
)

// --- Wire DTOs ---------------------------------------------------------------

/** `data` of a `POST /api/v1/client_configs` answer. [config] is null for an
 * unregistered name or an absent/malformed cloud value — both mean "no policy". */
@Serializable
data class ClientConfigData(
    val name: String = "",
    val config: ClientConfigDto? = null,
)

/** One generic decode covering both registered configs: the model config uses
 * [enable]/[rules], the effort one [descriptions]/[confirm]. */
@Serializable
data class ClientConfigDto(
    val enable: Boolean = false,
    val rules: List<SwitchRuleDto>? = null,
    val descriptions: EffortDescriptionsDto? = null,
    val confirm: SwitchConfirmDto? = null,
)

@Serializable
data class SwitchConfirmDto(
    val enable: Boolean = false,
    val rules: List<SwitchRuleDto>? = null,
)

@Serializable
data class SwitchRuleDto(
    val from: JsonElement? = null,
    val to: JsonElement? = null,
    @SerialName("session_type") val sessionType: String? = null,
    val hint: LocalizedTextDto? = null,
    val warning: LocalizedTextDto? = null,
)

@Serializable
data class LocalizedTextDto(
    val en: String? = null,
)

@Serializable
data class EffortDescriptionsDto(
    val enable: Boolean = false,
    val items: Map<String, LocalizedTextDto> = emptyMap(),
)

/** The model config's confirmation section. */
internal fun ClientConfigDto.toSwitchConfirm(): SwitchConfirm = SwitchConfirm(
    enabled = enable,
    rules = rules.orEmpty().mapNotNull { it.toDomain() },
)

/** The effort config; a null [confirm] disables change confirmation but keeps any
 * descriptions. */
internal fun ClientConfigDto.toEffortSwitchConfig(): EffortSwitchConfig = EffortSwitchConfig(
    descriptions = descriptions?.takeIf { it.enable }?.items.orEmpty()
        .mapNotNull { (effort, text) -> text.en?.takeIf { it.isNotBlank() }?.let { effort to it } }
        .toMap(),
    confirm = confirm?.let { SwitchConfirm(it.enable, it.rules.orEmpty().mapNotNull { rule -> rule.toDomain() }) }
        ?: SwitchConfirm(enabled = false, rules = emptyList()),
)

internal fun SwitchRuleDto.toDomain(): SwitchRule? {
    val type = sessionType?.lowercase()?.takeIf { it == "old" || it == "new" || it == "all" } ?: return null
    val from = from.toPatternList() ?: return null
    val to = to.toPatternList() ?: return null
    return SwitchRule(
        from = from,
        to = to,
        sessionType = type,
        hint = hint?.en?.takeIf { it.isNotBlank() },
        warning = warning?.en?.takeIf { it.isNotBlank() },
    )
}

/** A rule side: one string or a non-empty array of non-blank strings; null when the
 * field is absent, another JSON type, or effectively empty. */
private fun JsonElement?.toPatternList(): List<String>? = when (this) {
    is JsonPrimitive -> contentOrNull?.takeIf { it.isNotBlank() }?.let(::listOf)
    is JsonArray -> mapNotNull { (it as? JsonPrimitive)?.contentOrNull?.takeIf { value -> value.isNotBlank() } }
        .takeIf { it.isNotEmpty() }
    else -> null
}
