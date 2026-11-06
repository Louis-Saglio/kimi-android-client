package fr.louissaglio.kimi.android.testutil

/**
 * Server frames of the 2.5.0 session socket (docs/server-api.md, WebSocket → Session
 * frames / Transcript protocol — ticket 175), shaped after the verbatim captures. Op
 * builders return one op object; [opsFrame] wraps ops into a `transcript.ops` frame.
 */
object TranscriptFrames {

    fun opsFrame(vararg ops: String, agentId: String = "main", seq: Long = 1, sessionId: String = "session_1"): String =
        """{"type":"transcript.ops","session_id":"$sessionId","timestamp":"2026-11-04T19:25:51.000Z",""" +
            """"payload":{"type":"transcript.ops","agent_id":"$agentId","ops":[${ops.joinToString(",")}],"seq":$seq}}"""

    /** [interactions] is the snapshot's raw entity array (see [approvalEntity]). */
    fun resetFrame(
        agentId: String = "main",
        seq: Long = 1,
        sessionId: String = "session_1",
        interactions: String = "[]",
    ): String =
        """{"type":"transcript.reset","session_id":"$sessionId","timestamp":"2026-11-04T19:25:50.000Z",""" +
            """"payload":{"type":"transcript.reset","agent_id":"$agentId","snapshot":{"items":[],"interactions":$interactions,""" +
            """"attachments":[],"prompts":[],"hasMoreOlder":true},"has_more_older":true,"seq":$seq}}"""

    fun ackFrame(id: String = "c_1", accepted: List<String> = listOf("session_1"), notFound: List<String> = emptyList()): String =
        """{"type":"ack","id":"$id","code":0,"msg":"success","payload":{"accepted":${jsonList(accepted)},""" +
            """"not_found":${jsonList(notFound)}}}"""

    /** The flat `agent` state frame (ticket 177), shaped after the verbatim capture;
     * a null [model]/[thinking]/[permissionMode] omits the key (a model-less session
     * omits both binding fields). */
    fun agentFrame(
        status: String = "idle",
        model: String? = null,
        thinking: String? = null,
        permissionMode: String? = "auto",
        agentId: String = "main",
        sessionId: String = "session_1",
    ): String =
        """{"type":"agent","session_id":"$sessionId","agent_id":"$agentId",""" +
            """"event_created_at":"2026-11-04T19:25:52.678Z","status":"$status",""" +
            (model?.let { """"model":"$it",""" } ?: "") +
            (thinking?.let { """"thinking":"$it",""" } ?: "") +
            (permissionMode?.let { """"permission_mode":"$it",""" } ?: "") +
            """"plan_mode":false,"swarm_mode":false,"tower_mode":false}"""

    /** The flat `usage` state frame (ticket 177); a null [maxContextTokens] omits the
     * optional limit. */
    fun usageFrame(
        contextTokens: Long,
        maxContextTokens: Long? = 1_000_000,
        agentId: String = "main",
        sessionId: String = "session_1",
    ): String =
        """{"type":"usage","session_id":"$sessionId","agent_id":"$agentId",""" +
            """"event_created_at":"2026-11-04T19:25:52.679Z","context_tokens":$contextTokens""" +
            (maxContextTokens?.let { ""","max_context_tokens":$it""" } ?: "") + "}"

    /** The flat `todo` state frame (ticket 178), shaped after the verbatim capture
     * (docs/server-api.md, Todos): [items] are `title to status` pairs, the full list. */
    fun todoFrame(
        vararg items: Pair<String, String>,
        agentId: String = "main",
        sessionId: String = "session_1",
    ): String =
        """{"type":"todo","session_id":"$sessionId","agent_id":"$agentId",""" +
            """"event_created_at":"2026-11-04T19:34:17.194Z","items":[""" +
            items.joinToString(",") { (title, status) -> """{"title":"$title","status":"$status"}""" } + "]}"

    /**
     * One task's full state (ticket 179), shaped after the verbatim 2.5.0 captures
     * (docs/server-api.md, Sub-agents → Task frames and controls) — the body shared by
     * [taskFrame], [taskListFrame] entries and [taskResponseFrame]'s `task`. [extra] is
     * raw JSON appended to the object (`,"child_agent_id":"agent-0"`, …).
     */
    fun taskState(
        taskId: String,
        kind: String = "shell",
        status: String = "running",
        detached: Boolean = true,
        outcome: String? = null,
        description: String = "Bash: sleep 30",
        startedAt: String = "2026-11-04T19:30:00.000Z",
        endedAt: String? = null,
        extra: String = "",
    ): String =
        """{"task_id":"$taskId","kind":"$kind","status":"$status","detached":$detached,""" +
            """"description":"$description","started_at":"$startedAt"""" +
            (outcome?.let { ""","outcome":"$it"""" } ?: "") +
            (endedAt?.let { ""","ended_at":"$it"""" } ?: "") + extra + "}"

    /** The flat `task` frame: [state] (a [taskState] object) with the frame's own
     * `type`/`session_id`/`agent_id`/`event_created_at` beside its fields. */
    fun taskFrame(state: String, agentId: String = "main", sessionId: String = "session_1"): String =
        """{"type":"task","session_id":"$sessionId","agent_id":"$agentId",""" +
            """"event_created_at":"2026-11-04T19:30:00.000Z",""" + state.removePrefix("{")

    /** The flat `task.list` frame: the agent's running tasks ([taskState] objects). */
    fun taskListFrame(vararg states: String, agentId: String = "main", sessionId: String = "session_1"): String =
        """{"type":"task.list","session_id":"$sessionId","agent_id":"$agentId","tasks":[""" +
            states.joinToString(",") + "]}"

    /** A task control's reply: `{type:"response",request_id,code,msg?,task?}`. */
    fun taskResponseFrame(requestId: String, code: Int = 0, msg: String? = null, task: String? = null): String =
        """{"type":"response","request_id":"$requestId","code":$code""" +
            (msg?.let { ""","msg":"$it"""" } ?: "") +
            (task?.let { ""","task":$it""" } ?: "") + "}"

    /**
     * The flat approval `interaction` frame (ticket 171), shaped after the verbatim
     * 2.4.0/2.5.0 captures (docs/server-api.md, Interactions): `pending` carries the
     * snake_case request; `ended` adds [outcome], `ended_at` and the response. A null
     * [command] omits `tool_input_display` (the card falls back to [action]); a raw
     * [displayJson] (e.g. [PLAN_REVIEW_DISPLAY]) replaces the command display.
     */
    fun approvalFrame(
        interactionId: String,
        status: String = "pending",
        outcome: String? = null,
        toolName: String = "Bash",
        action: String = "Running: echo hi",
        command: String? = "echo hi",
        agentId: String = "main",
        sessionId: String = "session_1",
        displayJson: String? = command?.let {
            """{"kind":"command","command":"$it","cwd":"/path/to/project","language":"bash"}"""
        },
    ): String {
        val display = displayJson?.let { ""","tool_input_display":$it""" } ?: ""
        val ended = if (status == "ended") {
            """"outcome":"$outcome","ended_at":"2026-11-04T19:27:27.526Z","""
        } else {
            ""
        }
        val response = if (status == "ended") ""","response":{"decision":"$outcome"}""" else ""
        return """{"type":"interaction","session_id":"$sessionId","agent_id":"$agentId",""" +
            """"event_created_at":"2026-11-04T19:27:26.728Z","interaction_id":"$interactionId",""" +
            """"status":"$status",$ended"tool_call_id":"call_00_$interactionId","kind":"approval",""" +
            """"request":{"tool_name":"$toolName","action":"$action"$display,""" +
            """"expires_at":"2026-11-05T19:27:26.727Z"}$response}"""
    }

    /** One approval entity of `transcript.reset.snapshot.interactions` / the REST
     * transcript (camelCase request — docs/server-api.md, Pending state on REST). A raw
     * [displayJson] replaces the command display ([toolName]/[action] to match). */
    fun approvalEntity(
        interactionId: String,
        state: String = "pending",
        command: String = "echo hi",
        agentId: String = "main",
        toolName: String = "Bash",
        action: String = "Running: $command",
        displayJson: String = """{"kind":"command","command":"$command"}""",
    ): String =
        """{"interactionId":"$interactionId","interactionKind":"approval","toolCallId":"call_$interactionId",""" +
            """"state":"$state","request":{"id":"$interactionId","sessionId":"session_1","agentId":"$agentId",""" +
            """"turnId":"t1","toolCallId":"call_$interactionId","toolName":"$toolName","action":"$action",""" +
            """"display":$displayJson}}"""

    /** The plan of [PLAN_REVIEW_DISPLAY], decoded (JSON escapes resolved). */
    const val PLAN_REVIEW_PLAN: String =
        "# Add README.md with \"hello\"\n\n## Context\n\nThe repository has no README."

    /** An `ExitPlanMode` approval's `tool_input_display`, shaped after the verbatim
     * 2.4.0 capture (docs/server-api.md, Plan review approvals); pair it with
     * `toolName = "ExitPlanMode"` and [PLAN_REVIEW_ACTION]. */
    const val PLAN_REVIEW_DISPLAY: String =
        """{"kind":"plan_review",""" +
            """"plan":"# Add README.md with \"hello\"\n\n## Context\n\nThe repository has no README.",""" +
            """"path":"/path/to/session/agents/main/plans/plan.md"}"""

    /** The `action` of a plan review approval (docs/server-api.md, Plan review approvals). */
    const val PLAN_REVIEW_ACTION: String = "Presenting plan and exiting plan mode"

    /** The verbatim pending question's single-select item (docs/server-api.md,
     * Interactions → The `interaction` frame). */
    const val CAKE_OR_PIE: String =
        """[{"id":"q_0","question":"Cake or pie?","options":[{"id":"opt_0_0","label":"Cake","description":""},""" +
            """{"id":"opt_0_1","label":"Pie","description":""}],"header":"Dessert","allow_other":true}]"""

    /**
     * The flat question `interaction` frame (ticket 172), shaped after the verbatim
     * 2.4.0/2.5.0 captures: `pending` carries `request:{questions[]}` ([questions] is
     * the raw item array); `ended` adds [outcome], `ended_at` and — when answered — the
     * `response.answers` keyed by question text.
     */
    fun questionFrame(
        interactionId: String,
        status: String = "pending",
        outcome: String? = null,
        questions: String = CAKE_OR_PIE,
        toolCallId: String = "call_00_$interactionId",
        agentId: String = "main",
        sessionId: String = "session_1",
    ): String {
        val ended = if (status == "ended") {
            """"outcome":"$outcome","ended_at":"2026-10-28T19:28:18.858Z","""
        } else {
            ""
        }
        val response = if (outcome == "answered") ""","response":{"answers":{"Cake or pie?":"Cake"}}""" else ""
        return """{"type":"interaction","session_id":"$sessionId","agent_id":"$agentId",""" +
            """"event_created_at":"2026-10-28T19:28:18.527Z","interaction_id":"$interactionId",""" +
            """"status":"$status",$ended"tool_call_id":"$toolCallId","kind":"question",""" +
            """"request":{"questions":$questions}$response}"""
    }

    /** One question entity of `transcript.reset.snapshot.interactions` / the REST
     * transcript (the snake_case request with `question_id`, `tool_call_id`, `turn_id`,
     * `created_at` — docs/server-api.md, Pending state on REST). */
    fun questionEntity(
        interactionId: String,
        state: String = "pending",
        questions: String = CAKE_OR_PIE,
        toolCallId: String = "call_00_$interactionId",
    ): String =
        """{"interactionId":"$interactionId","interactionKind":"question","toolCallId":"$toolCallId",""" +
            """"state":"$state","request":{"question_id":"$interactionId","questions":$questions,""" +
            """"created_at":"2026-11-05T10:00:00.000Z","turn_id":"t1","tool_call_id":"$toolCallId"}}"""

    fun promptOp(promptId: String, status: String, extra: String = ""): String =
        """{"op":"prompt.upsert","prompt":{"promptId":"$promptId","status":"$status","userMessageId":"$promptId",""" +
            """"content":[{"type":"text","text":"hi"}],"createdAt":"2026-11-04T19:25:50.000Z"$extra}}"""

    fun turnOp(turnId: String, state: String, extra: String = ""): String =
        """{"op":"turn.upsert","turn":{"kind":"turn","turnId":"$turnId","ordinal":1,"state":"$state",""" +
            """"origin":{"kind":"user","payload":{"kind":"user"}}$extra}}"""

    fun stepOp(turnId: String, stepId: String, state: String, extra: String = ""): String =
        """{"op":"step.upsert","turnId":"$turnId","step":{"kind":"step","stepId":"$stepId","turnId":"$turnId",""" +
            """"ordinal":1,"state":"$state"$extra}}"""

    /** A text (assistant by default) or `thinking` frame upsert. */
    fun textOp(frameId: String, text: String, kind: String = "text", turnId: String = "t1"): String =
        """{"op":"frame.upsert","turnId":"$turnId","stepId":"$turnId.1","frame":{"kind":"$kind","frameId":"$frameId",""" +
            (if (kind == "text") """"role":"assistant",""" else "") + """"text":"$text"}}"""

    fun appendOp(frameId: String, offset: Int, text: String, turnId: String = "t1"): String =
        """{"op":"append","target":{"type":"frame","turnId":"$turnId","stepId":"$turnId.1","frameId":"$frameId"},""" +
            """"offset":$offset,"text":"$text"}"""

    /** A tool frame upsert; [extra] carries `input`/`display`/`output` fields. */
    fun toolOp(toolCallId: String, name: String, state: String, extra: String = "", turnId: String = "t1"): String =
        """{"op":"frame.upsert","turnId":"$turnId","stepId":"$turnId.1","frame":{"kind":"tool",""" +
            """"frameId":"$turnId.1.$toolCallId","toolCallId":"$toolCallId","name":"$name","state":"$state"$extra}}"""

    /** An `undo` marker splicing [messages] (JSON message objects). */
    fun undoOp(markerId: String, vararg messages: String): String =
        """{"op":"marker.upsert","item":{"kind":"marker","markerId":"$markerId","marker":"undo",""" +
            """"payload":{"agentId":"main","start":0,"deleteCount":0,"messages":[${messages.joinToString(",")}]},""" +
            """"at":"2026-11-04T19:25:51.000Z"}}"""

    fun userMessage(id: String?, text: String): String =
        """{"role":"user","content":[{"type":"text","text":"$text"}],""" +
            (if (id != null) """"id":"$id",""" else "") + """"origin":{"kind":"user"}}"""

    private fun jsonList(items: List<String>): String = items.joinToString(",", "[", "]") { "\"$it\"" }
}
