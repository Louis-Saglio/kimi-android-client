package fr.louissaglio.kimi.android.data.chat

/**
 * The continuation blocks of an `Agent` call's result text (ticket 158 —
 * docs/server-api.md, Sub-agents): the trailing `resume_hint:` block (0.43.0
 * foreground results, 0.31.0 background stubs and killed-task notifications)
 * and the `next_step:` block (background stubs only — what happens next
 * automatically). Both are single `key: value` lines at column 0, parsed
 * verbatim like the result's other head fields. Pre-0.43.0 foreground results
 * carry neither — every helper then yields null and the surfaces render
 * exactly as before.
 */
fun agentResumeHintOf(toolName: String, output: String): String? =
    agentResultField(toolName, output, "resume_hint")

/** The `next_step:` block of a background spawn's result stub (ticket 158);
 * null for foreground results and non-`Agent` calls. */
fun agentNextStepOf(toolName: String, output: String): String? =
    agentResultField(toolName, output, "next_step")

/**
 * The suggested `Agent(resume="agent-N", prompt="…")` call text inside a
 * `resume_hint:` block (ticket 158) — the composer prefill payload behind the
 * "Next step" section's Continue action. The user reviews and sends it; the
 * server executes it as the next prompt's tool call (no dedicated resume
 * endpoint exists). Verbatim, ids untranslated; null when the hint carries no
 * recognizable call — the Continue action then never shows.
 */
fun agentResumeCallOf(hint: String): String? = RESUME_CALL.find(hint)?.value

/** `key: value` line of an `Agent` result text (the [agentTaskIdOf] gate and
 *  the store's own head-field parse in one); null for other tools. */
private fun agentResultField(toolName: String, output: String, key: String): String? {
    if (toolName != AGENT_TOOL_NAME) return null
    return output.lineSequence()
        .firstOrNull { it.startsWith("$key: ") }
        ?.substringAfter(": ")
        ?.trim()
        ?.takeIf { it.isNotBlank() }
}

// The pinned hint shape (docs/server-api.md, Sub-agents): Agent(resume="agent-N",
// prompt="…"). The prompt parameter is the hint's own suggestion — optional in
// the parse so a resume-only call still pre-fills.
private val RESUME_CALL = Regex("""Agent\(resume="[^"]*"(,\s*prompt="[^"]*")?\)""")
