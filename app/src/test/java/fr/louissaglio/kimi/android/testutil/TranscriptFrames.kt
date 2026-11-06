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

    fun resetFrame(agentId: String = "main", seq: Long = 1, sessionId: String = "session_1"): String =
        """{"type":"transcript.reset","session_id":"$sessionId","timestamp":"2026-11-04T19:25:50.000Z",""" +
            """"payload":{"type":"transcript.reset","agent_id":"$agentId","snapshot":{"items":[],"interactions":[],""" +
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
