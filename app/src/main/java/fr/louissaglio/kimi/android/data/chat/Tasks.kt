package fr.louissaglio.kimi.android.data.chat

import java.time.Instant
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

// Domain models of the background-task store (docs/server-api.md, Background lifecycle
// and REST /tasks — ticket 092): background sub-agents and background bash. Question
// flows (kind "tool") are excluded — they already surface as question cards in chat.

/**
 * One background task as REST `/tasks` serves it: [kind] and [status] use the REST
 * wire vocabulary (the core one is already mapped server-side). [runInBackground]
 * (0.39.0) is the wire flag verbatim — `false` on a listed foreground sub-agent,
 * which the jobs seed filters out (ticket 162). [agentId] links a sub-agent task
 * to the WS sub-agent id for navigation (subagent-kind tasks carry it since
 * 0.36.1); absent on bash tasks — the link then arrives via `task.started`.
 * [command] rides on bash-kind tasks; [outputPreview]/[outputBytes] arrive only on a
 * get-with-output read (ticket 096's job screen) — the stored stdout/stderr tail,
 * live while a bash task runs. [model]/[thinkingEffort] ride on subagent-kind tasks
 * (0.34.0) — the sub-agent store heals a member's pair from the list on a cold
 * start (ticket 135).
 */
data class BackgroundTask(
    val id: String,
    val kind: Kind,
    val description: String,
    val status: Status,
    val runInBackground: Boolean,
    val createdAt: Instant,
    val startedAt: Instant,
    val completedAt: Instant? = null,
    val agentId: String? = null,
    val command: String? = null,
    val outputPreview: String? = null,
    val outputBytes: Int? = null,
    val model: String? = null,
    val thinkingEffort: String? = null,
) {
    /** REST wire vocabulary (docs/server-api.md): core `agent`→Subagent,
     * `process`→Bash. Question flows (`tool`) never reach the domain — filtered at
     * the mapping boundary. */
    enum class Kind { Subagent, Bash }

    /** REST wire vocabulary: core `killed`→Cancelled, `timed_out`/`lost`→Failed. */
    enum class Status { Running, Completed, Failed, Cancelled }
}

/**
 * The `info` object of `task.started`/`task.terminated` (docs/server-api.md —
 * [startedAt]/[endedAt] are epoch MILLIS on the wire, unlike REST's ISO strings).
 * [status] keeps the CORE vocabulary (killed/timed_out/lost included) — the collector
 * maps it to the REST wire one. [agentId] is null on bash tasks.
 */
data class TaskInfo(
    val taskId: String,
    val description: String,
    val status: Status,
    val startedAt: Instant,
    val endedAt: Instant? = null,
    val stopReason: String? = null,
    val kind: Kind,
    val agentId: String? = null,
    val subagentType: String? = null,
) {
    /** Core vocabulary (docs/server-api.md): `agent` = background sub-agent,
     * `process` = background bash. */
    enum class Kind { Agent, Process }

    /** Core vocabulary — a superset of the REST wire one ([TimedOut] and [Lost]
     * surface as Failed there, [Killed] as Cancelled). */
    enum class Status { Running, Completed, Failed, TimedOut, Killed, Lost }
}

private val TASK_ID = Regex("""task_id:\s*(\S+)""")

/**
 * The row→job link of a background Bash call (ticket 096): the call's tool result is
 * an immediate plain-text stub whose FIRST line is `task_id: bash-…`
 * (docs/server-api.md, REST /tasks) — the real output lives in the task store, so the
 * row navigates to the job screen instead of expanding. History carries the same
 * text, so live and re-entered sessions link alike. The `bash-` prefix requirement is
 * the guard that keeps background **Agent** results (`task_id: agent-…`) untouched —
 * those rows already navigate via their subagentId.
 */
fun bashTaskIdOf(toolName: String, output: String): String? {
    if (toolName != "Bash") return null
    val id = TASK_ID.find(output.lineSequence().firstOrNull().orEmpty())
        ?.groupValues?.get(1)
    return id?.takeIf { it.startsWith("bash-") }
}

/** The harness's kill budget for a background Bash call whose input sets no explicit
 * `timeout` (tool-schema contract — NOT observable on the wire, docs/server-api.md). */
const val DEFAULT_BACKGROUND_BASH_TIMEOUT_SECONDS = 600

/**
 * The timeout knobs of a Bash tool call's input (ticket 097): [seconds] is the
 * explicit `timeout` (null when unset), [disabled] mirrors `disable_timeout: true`.
 * REST never exposes either — the call input (live args / history `tool_use` alike)
 * is the only wire location.
 */
data class BashTimeout(
    val seconds: Int?,
    val disabled: Boolean,
) {
    /** The effective kill budget: the explicit timeout, else the 600 s background
     * default, else null when the kill is disabled — null means "no budget UI". */
    val effectiveSeconds: Int?
        get() = seconds ?: if (disabled) null else DEFAULT_BACKGROUND_BASH_TIMEOUT_SECONDS

    /** True when [effectiveSeconds] comes from the harness default rather than the
     * call — the screen labels a default-sourced budget. */
    val isDefault: Boolean get() = seconds == null && !disabled
}

private val bashArgsJson = Json { ignoreUnknownKeys = true }

/** The input object of a Bash tool call; null for non-Bash calls and
 * blank/unparseable input (the row then carries no input-derived information). */
private fun bashArgsOf(toolName: String, argsJson: String): JsonObject? {
    if (toolName != "Bash" || argsJson.isBlank()) return null
    return runCatching { bashArgsJson.parseToJsonElement(argsJson).jsonObject }.getOrNull()
}

/** The timeout knobs of an already-parsed Bash input object — the ONE derivation
 * behind both the string-input [bashTimeoutOf] (the row's budget fields, ticket 097)
 * and the typed payload (`ToolPayload.Bash`, ticket 121). Non-primitive knobs parse
 * as absent rather than throwing. */
fun bashTimeoutOf(args: JsonObject): BashTimeout = BashTimeout(
    seconds = (args["timeout"] as? JsonPrimitive)?.intOrNull,
    disabled = (args["disable_timeout"] as? JsonPrimitive)?.booleanOrNull == true,
)

/** Parses [argsJson] (a Bash call's input) into its [BashTimeout]; null for non-Bash
 * calls and blank/unparseable input (the row then carries no budget information). */
fun bashTimeoutOf(toolName: String, argsJson: String): BashTimeout? =
    bashArgsOf(toolName, argsJson)?.let(::bashTimeoutOf)

/** The agent-set `description` of a Bash call's input; null when absent or blank —
 * the job screen's title then falls back to the task-store description. */
fun bashDescriptionOf(toolName: String, argsJson: String): String? =
    bashArgsOf(toolName, argsJson)?.get("description")?.jsonPrimitive?.contentOrNull?.takeIf { it.isNotBlank() }

/** The `command` of a Bash call's input; null when absent — the job screen's Command
 * section then waits for the entry fetch (REST carries it). */
fun bashCommandOf(toolName: String, argsJson: String): String? =
    bashArgsOf(toolName, argsJson)?.get("command")?.jsonPrimitive?.contentOrNull
