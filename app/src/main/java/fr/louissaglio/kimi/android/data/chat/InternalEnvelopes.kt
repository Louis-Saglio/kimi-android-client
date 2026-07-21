package fr.louissaglio.kimi.android.data.chat

/**
 * Strips internal protocol envelopes from the text of user-role messages
 * (docs/server-api.md, "Internal envelopes in user messages"). The server
 * injects harness traffic as user messages — background-task notifications,
 * system reminders, TUI shell passthrough, skill activations — which the web
 * client hides. Without this filter they render as raw XML in user bubbles.
 *
 * Detection mirrors the generated shapes (paired tags, attributes allowed on
 * the opening tag), not a full XML parse: these envelopes are server-generated,
 * never user-typed. A `<system-reminder>` the user pasted themselves is
 * collateral damage the web client accepts too (it hides the whole message by
 * origin metadata instead).
 */
object InternalEnvelopes {

    // Tag set from agent-core's user-role injections (ContextMemory.appendSystemReminder,
    // appendLocalCommandStdout, appendBashInput/Output, renderNotificationXml,
    // renderSkillLoadedBlock, renderCronFireXml).
    private val TAGS = listOf(
        "system-reminder",
        "notification",
        "local-command-stdout",
        "bash-input",
        "bash-stdout",
        "bash-stderr",
        "kimi-skill-loaded",
        "cron-fire",
    )

    private val ENVELOPE_RES = TAGS.map { tag -> Regex("""<$tag(?:\s[^>]*)?>[\s\S]*?</$tag>""") }

    // The one text-level strip the web client does beyond origin-based hiding:
    // the image-compression caption inlined next to uploads (agent-core
    // buildImageCompressionCaption; rerouted through <system-reminder> on newer
    // servers, but older transcripts carry it inline).
    private val IMAGE_CAPTION_RE =
        Regex("""<system>Image compressed to fit model limits:[\s\S]*?</system>""")

    // Fixed lead-in sentences agent-core puts ahead of a <kimi-skill-loaded> block
    // (renderUserSlashSkillPrompt / renderModelToolSkillPrompt). Generated prose,
    // not user text — stripped alongside the block so nothing of the injection remains.
    private val SKILL_LEAD_IN_RES = listOf(
        Regex("""User activated the skill "[^"]*"\. Follow the loaded skill instructions\."""),
        Regex("""Skill tool loaded instructions for this request\. Follow them\."""),
    )

    /** Returns [text] with every envelope block removed, trimmed of the
     * whitespace the removal leaves behind. Text without envelopes is returned
     * unchanged (fast path — no trimming of genuine user input). */
    fun strip(text: String): String {
        if (!text.contains('<')) return text
        var result = text
        for (re in ENVELOPE_RES) result = re.replace(result, "")
        result = IMAGE_CAPTION_RE.replace(result, "")
        if (result.length == text.length) return text
        if (text.contains("<kimi-skill-loaded")) {
            for (re in SKILL_LEAD_IN_RES) result = re.replace(result, "")
        }
        return result.trim()
    }
}
