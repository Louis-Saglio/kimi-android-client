package fr.louissaglio.kimi.android.data.chat

import fr.louissaglio.kimi.android.data.parseOrNull
import java.time.Instant
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.longOrNull

// The task domain (docs/server-api.md, Sub-agents → Task frames and controls —
// server 2.5.0, ticket 179): the `task.list`/`task` frames' own vocabulary. There is
// no REST task API any more; every task change arrives as a full-state `task` frame.

/**
 * One task's full state, as a `task` frame (or a `task.list` entry, or a task control's
 * reply) carries it. Every frame replaces the previous state wholesale. [kind] `Other`
 * is non-shell/sub-agent work (e.g. a background question) — never a job row.
 * [detached] is the background flag: `false` while a tool call waits on the task (a
 * foreground spawn or command), `true` once it runs in the background (spawned so, or
 * moved there by `task.background`). [childAgentId] is the spawned sub-agent's agent id
 * (subagent kind only — `agent-0`, a different namespace from the `agent-…` task id).
 * [outcome] is set exactly when [status] is [Status.Ended]; the ended frame is the
 * task's last.
 */
data class TaskState(
    val taskId: String,
    val kind: Kind,
    val status: Status,
    val detached: Boolean,
    val description: String = "",
    val outcome: TaskOutcome? = null,
    val phase: String? = null,
    val childAgentId: String? = null,
    val parentToolCallId: String? = null,
    val swarmIndex: Int? = null,
    val command: String? = null,
    val startedAt: Instant? = null,
    val endedAt: Instant? = null,
    val resultSummary: String? = null,
    val error: String? = null,
    val stateReason: String? = null,
    val model: String? = null,
    val thinking: String? = null,
) {
    /** Wire `kind`: `shell`, `subagent`, anything else (`other`) → [Other]. */
    enum class Kind { Shell, Subagent, Other }

    /** Wire `status`: `running` or `ended`. */
    enum class Status { Running, Ended }

    val isRunning: Boolean get() = status == Status.Running
}

/** An ended task's `outcome` (docs/server-api.md): `killed` is a deliberate stop
 * (`task.stop`), `timed_out` the kill budget, `lost` a task the server lost track of. */
enum class TaskOutcome(val wire: String) {
    Completed("completed"),
    Failed("failed"),
    TimedOut("timed_out"),
    Killed("killed"),
    Lost("lost"),
    ;

    companion object {
        fun fromWire(value: String?): TaskOutcome? = entries.firstOrNull { it.wire == value }
    }
}

/** The two task controls the app sends (docs/server-api.md → WebSocket → Control
 * frames): flat `task.stop` (replaces REST `:cancel`) and `task.background` (replaces
 * REST `:detach`). Both reply `{type:"response",request_id,code,msg?,task?}`. The
 * output stream's `task.watch`/`task.unwatch` (ticket 180) carry their own parameters
 * — [SessionSocket.watchTask]/[SessionSocket.unwatchTask]. */
enum class TaskControl(val wire: String) {
    Stop("task.stop"),
    Background("task.background"),
}

/**
 * The wire task state → [TaskState] (ticket 179). Null — the frame is dropped — when
 * the task id is blank or the status is neither `running` nor `ended`; an unknown kind
 * degrades to [TaskState.Kind.Other] (never a job), an unknown outcome to null. Blank
 * optional strings read as absent.
 */
internal fun TaskStateDto.toTaskState(): TaskState? {
    if (taskId.isBlank()) return null
    val status = when (status) {
        "running" -> TaskState.Status.Running
        "ended" -> TaskState.Status.Ended
        else -> return null
    }
    return TaskState(
        taskId = taskId,
        kind = when (kind) {
            "shell" -> TaskState.Kind.Shell
            "subagent" -> TaskState.Kind.Subagent
            else -> TaskState.Kind.Other
        },
        status = status,
        detached = detached,
        description = description.orEmpty(),
        outcome = if (status == TaskState.Status.Ended) TaskOutcome.fromWire(outcome) else null,
        phase = phase.blankToNull(),
        childAgentId = childAgentId.blankToNull(),
        parentToolCallId = parentToolCallId.blankToNull(),
        swarmIndex = swarmIndex,
        command = command.blankToNull(),
        startedAt = startedAt.toInstantOrNull(),
        endedAt = endedAt.toInstantOrNull(),
        resultSummary = resultSummary.blankToNull(),
        error = error.blankToNull(),
        stateReason = stateReason.blankToNull(),
        model = model.blankToNull(),
        thinking = thinking.blankToNull(),
    )
}

private fun String?.blankToNull(): String? = this?.takeIf { it.isNotBlank() }

/** An ISO-8601 string, or an epoch-millis number; anything else is unknown. */
private fun JsonPrimitive?.toInstantOrNull(): Instant? {
    val primitive = this ?: return null
    if (primitive is JsonNull) return null
    if (!primitive.isString) return primitive.longOrNull?.let(Instant::ofEpochMilli)
    return parseOrNull { Instant.parse(primitive.content) }
}

private val TASK_ID = Regex("""task_id:\s*(\S+)""")

/**
 * The row→job link of a background Bash call (ticket 096): the call's tool result is
 * an immediate plain-text stub whose FIRST line is `task_id: bash-…`
 * (docs/server-api.md, Sub-agents) — the real output lives in the task store, so the
 * row navigates to the job screen instead of expanding. History carries the same
 * text, so live and re-entered sessions link alike. The `bash-` prefix requirement is
 * the guard that keeps background **Agent** results (`task_id: agent-…`) for
 * [agentTaskIdOf] (ticket 157).
 */
fun bashTaskIdOf(toolName: String, output: String): String? {
    if (toolName != "Bash") return null
    val id = TASK_ID.find(output.lineSequence().firstOrNull().orEmpty())
        ?.groupValues?.get(1)
    return id?.takeIf { it.startsWith("bash-") }
}

/**
 * The row→job link of a background `Agent` spawn (ticket 157): its result is the
 * immediate plain-text stub whose FIRST line is `task_id: agent-…` (docs/server-api.md,
 * Sub-agents — the background result contract's head; a foreground result starts at
 * `agent_id:` and never carries the line), so the spawn row's tap opens the job
 * screen for the task. History repeats the same text, so a re-entered session links
 * alike. The `agent-` prefix requirement mirrors [bashTaskIdOf]'s `bash-` guard.
 */
fun agentTaskIdOf(toolName: String, output: String): String? {
    if (toolName != AGENT_TOOL_NAME) return null
    val id = TASK_ID.find(output.lineSequence().firstOrNull().orEmpty())
        ?.groupValues?.get(1)
    return id?.takeIf { it.startsWith("agent-") }
}

/** The harness's kill budget for a background Bash call whose input sets no explicit
 * `timeout` (tool-schema contract — NOT observable on the wire, docs/server-api.md). */
const val DEFAULT_BACKGROUND_BASH_TIMEOUT_SECONDS = 600

/**
 * The timeout knobs of a Bash tool call's input (ticket 097): [seconds] is the
 * explicit `timeout` (null when unset), [disabled] mirrors `disable_timeout: true`.
 * No task frame exposes either — the call input (live args / history `tool_use`
 * alike) is the only wire location.
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
    return parseOrNull { bashArgsJson.parseToJsonElement(argsJson).jsonObject }
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
 * section then waits for the task frame (shell tasks carry it). */
fun bashCommandOf(toolName: String, argsJson: String): String? =
    bashArgsOf(toolName, argsJson)?.get("command")?.jsonPrimitive?.contentOrNull
