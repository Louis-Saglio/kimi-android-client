package fr.louissaglio.kimi.android.ui.chat

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material.icons.filled.Build
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedCard
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import fr.louissaglio.kimi.android.data.chat.AGENT_SWARM_TOOL_NAME
import fr.louissaglio.kimi.android.data.chat.BashOutputs
import fr.louissaglio.kimi.android.data.chat.EditDiffs
import fr.louissaglio.kimi.android.data.chat.FetchUrlOutputs
import fr.louissaglio.kimi.android.data.chat.GlobOutputs
import fr.louissaglio.kimi.android.data.chat.GrepResultParser
import fr.louissaglio.kimi.android.data.chat.ToolDetailFields
import fr.louissaglio.kimi.android.data.chat.ToolPayload
import fr.louissaglio.kimi.android.data.chat.ToolPreview
import fr.louissaglio.kimi.android.ui.common.formatFileSize
import fr.louissaglio.kimi.android.ui.common.icons.ClockIcon
import fr.louissaglio.kimi.android.ui.common.icons.DocumentIcon
import fr.louissaglio.kimi.android.ui.common.icons.EditIcon
import fr.louissaglio.kimi.android.ui.common.icons.GlobeIcon
import fr.louissaglio.kimi.android.ui.common.icons.ImageIcon
import fr.louissaglio.kimi.android.ui.common.icons.LinkIcon
import fr.louissaglio.kimi.android.ui.common.icons.SearchIcon
import fr.louissaglio.kimi.android.ui.common.icons.SparklesIcon
import fr.louissaglio.kimi.android.ui.common.icons.WorkspaceIcon
import fr.louissaglio.kimi.android.ui.theme.diffColors
import fr.louissaglio.kimi.android.ui.theme.successColor
import fr.louissaglio.kimi.android.ui.theme.warningColor

/** Tool-status glyph for the group header and the rows (tickets 022/037): spinner
 * while running, green check on success, red cross on error. [runningDescription]
 * feeds the spinner's semantics ("Tool running" for a row, "Tools running" for a
 * group header). */
@Composable
private fun ToolStatusIcon(status: ToolStatus, runningDescription: String, modifier: Modifier = Modifier) {
    when (status) {
        ToolStatus.Running -> CircularProgressIndicator(
            modifier = modifier.semantics { contentDescription = runningDescription },
            strokeWidth = 1.5.dp,
        )
        // No success slot in the M3 scheme: a literal green, readable on both themes.
        ToolStatus.Done -> Text(
            text = "✓",
            style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.successColor,
        )
        ToolStatus.Error -> Text(
            text = "✗",
            style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.error,
        )
    }
}

/** A run of consecutive tool calls as one card (ticket 037), mirroring the web
 * client's collapsed group: a header with the aggregate status — spinner while any
 * call runs, error mark if any failed, success check when all are done — then the
 * rows themselves, each keeping its own expand/collapse.
 *
 * M3 outlined card, matching the web's "lighter fill + thin grey border": the fill is
 * `surfaceContainerLow` (a step above the chat background in both themes), the border
 * the default 1dp `outlineVariant` hairline — no elevation, the chat surface is flat. */
@Composable
internal fun ToolGroupCard(
    group: ChatItem.ToolGroup,
    onOpenSubagent: (String) -> Unit = {},
    onOpenJob: (String) -> Unit = {},
    onQuestionRowTapped: (toolCallId: String, input: String, output: String) -> Unit = { _, _, _ -> },
    onOpenEditDiff: (toolCallId: String, payload: ToolPayload.Edit) -> Unit = { _, _ -> },
    onOpenFileContent: (toolCallId: String, payload: ToolPayload, output: String) -> Unit = { _, _, _ -> },
    swarmMembers: Map<String, List<SubagentStreamStore.SubagentState>> = emptyMap(),
    onOpenSwarm: (String) -> Unit = {},
) {
    val status = when {
        group.tools.any { it.status == ToolStatus.Running } -> ToolStatus.Running
        group.tools.any { it.status == ToolStatus.Error } -> ToolStatus.Error
        else -> ToolStatus.Done
    }
    OutlinedCard(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.outlinedCardColors(containerColor = MaterialTheme.colorScheme.surfaceContainerLow),
    ) {
        Column(Modifier.padding(horizontal = 10.dp, vertical = 4.dp)) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(6.dp),
            ) {
                ToolStatusIcon(status, runningDescription = "Tools running", modifier = Modifier.size(12.dp))
                Text(
                    text = buildString {
                        append(group.tools.size)
                        append(if (group.tools.size == 1) " tool call · " else " tool calls · ")
                        append(
                            when (status) {
                                ToolStatus.Running -> "running"
                                ToolStatus.Done -> "done"
                                ToolStatus.Error -> "error"
                            },
                        )
                    },
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            group.tools.forEachIndexed { index, tool ->
                // 1dp outlineVariant hairline between rows, like the web's row separators.
                if (index > 0) HorizontalDivider()
                ToolRow(tool, onOpenSubagent, onOpenJob, onQuestionRowTapped, onOpenEditDiff, onOpenFileContent, swarmMembers, onOpenSwarm)
            }
        }
    }
}

/** The swarm card (ticket 134, aggregate visuals 135): a linked `AgentSwarm` call's
 * compact affordance, replacing its generic row while at least one member link
 * exists. The group-card idiom (outlined, `surfaceContainerLow`). Below the 134
 * header row (sparkles glyph, "Swarm", the description arg, the member count,
 * chevron): the aggregate status chip and "{done}/{total}" count, the segmented
 * phase bar and the subtitle ("{N} in progress" while running, the settled counts
 * afterwards), plus the shared model line when every member runs the same
 * model/effort. A failed member — or the call's own error — tints the border
 * danger, mirroring the web. The whole card is one tap target opening the roster
 * screen. */
@Composable
private fun SwarmCard(
    activity: ChatItem.ToolActivity,
    members: List<SubagentStreamStore.SubagentState>,
    onOpenSwarm: (String) -> Unit,
) {
    val detailColor = MaterialTheme.colorScheme.onSurfaceVariant
    val payload = activity.payload as? ToolPayload.AgentSwarm
    val progress = SwarmProgress.of(
        members,
        expectedTotal = payload?.expectedTotal,
        toolRunning = activity.status == ToolStatus.Running,
    )
    val (chipStatus, chipLabel) = when (progress.status) {
        SwarmAggregateStatus.Running -> SubagentStatus.Working to "Running"
        SwarmAggregateStatus.Failed -> SubagentStatus.Failed to "Failed"
        SwarmAggregateStatus.Cancelled -> SubagentStatus.Cancelled to "Cancelled"
        SwarmAggregateStatus.Completed -> SubagentStatus.Completed to "Completed"
    }
    // The web tints danger on a tool error or any member failure.
    val danger = progress.status == SwarmAggregateStatus.Failed || activity.status == ToolStatus.Error
    OutlinedCard(
        modifier = Modifier
            .fillMaxWidth()
            .clickable { onOpenSwarm(activity.id) },
        colors = CardDefaults.outlinedCardColors(containerColor = MaterialTheme.colorScheme.surfaceContainerLow),
        border = if (danger) {
            BorderStroke(1.dp, MaterialTheme.colorScheme.error)
        } else {
            CardDefaults.outlinedCardBorder()
        },
    ) {
        Column(Modifier.padding(vertical = 4.dp)) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    // M3: the whole card is the tap target, at least 48dp tall.
                    .heightIn(min = 48.dp)
                    .padding(horizontal = 10.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(6.dp),
            ) {
                Icon(
                    imageVector = SparklesIcon,
                    contentDescription = null,
                    modifier = Modifier
                        .size(14.dp)
                        .testTag("toolIconSwarm"),
                    tint = detailColor,
                )
                Text(
                    text = "Swarm",
                    style = MaterialTheme.typography.labelMedium,
                )
                Text(
                    text = activity.preview,
                    style = MaterialTheme.typography.labelMedium,
                    color = detailColor,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f),
                )
                Text(
                    text = if (members.size == 1) "1 member" else "${members.size} members",
                    style = MaterialTheme.typography.labelMedium,
                    color = detailColor,
                )
                Icon(
                    imageVector = Icons.AutoMirrored.Filled.KeyboardArrowRight,
                    contentDescription = "Open swarm",
                    modifier = Modifier.size(16.dp),
                    tint = detailColor,
                )
            }
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 10.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                SubagentStatusChip(chipStatus, label = chipLabel, modifier = Modifier.testTag("swarmAggregateChip"))
                Text(
                    text = "${progress.done}/${progress.total}",
                    style = MaterialTheme.typography.labelMedium,
                    color = detailColor,
                    modifier = Modifier.testTag("swarmDoneTotal"),
                )
            }
            SwarmPhaseBar(progress)
            Text(
                text = progress.subtitle,
                style = MaterialTheme.typography.bodySmall,
                color = detailColor,
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 10.dp)
                    .testTag("swarmSubtitle"),
            )
            progress.modelLine?.let { modelLine ->
                Text(
                    text = modelLine,
                    style = MaterialTheme.typography.bodySmall,
                    color = detailColor,
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 10.dp)
                        .testTag("swarmModelLine"),
                )
            }
        }
    }
}

/** The card's segmented phase bar (ticket 135): one weighted segment per present
 * phase in phase order (queued grey, working primary, suspended warning amber,
 * completed success green, failed error, cancelled grey — the web's colors), the
 * not-yet-spawned remainder as the track so the bar fills as members spawn. One
 * contentDescription summarizes the counts; each segment carries its testTag. */
@Composable
private fun SwarmPhaseBar(progress: SwarmProgress) {
    val segments = buildList {
        if (progress.queued > 0) {
            add(SwarmBarSegment("swarmBarSegmentQueued", MaterialTheme.colorScheme.outlineVariant, progress.queued))
        }
        if (progress.working > 0) {
            add(SwarmBarSegment("swarmBarSegmentWorking", MaterialTheme.colorScheme.primary, progress.working))
        }
        if (progress.suspended > 0) {
            add(SwarmBarSegment("swarmBarSegmentSuspended", MaterialTheme.warningColor, progress.suspended))
        }
        if (progress.completed > 0) {
            add(SwarmBarSegment("swarmBarSegmentCompleted", MaterialTheme.successColor, progress.completed))
        }
        if (progress.failed > 0) {
            add(SwarmBarSegment("swarmBarSegmentFailed", MaterialTheme.colorScheme.error, progress.failed))
        }
        if (progress.cancelled > 0) {
            add(SwarmBarSegment("swarmBarSegmentCancelled", MaterialTheme.colorScheme.outlineVariant, progress.cancelled))
        }
    }
    val remainder = progress.total - progress.members
    if (segments.isEmpty() && remainder <= 0) return
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 10.dp, vertical = 4.dp)
            .height(4.dp)
            .clip(MaterialTheme.shapes.small)
            .semantics { contentDescription = progress.countsLine }
            .testTag("swarmProgressBar"),
    ) {
        segments.forEach { segment ->
            Box(
                Modifier
                    .weight(segment.count.toFloat())
                    .fillMaxHeight()
                    .background(segment.color)
                    .testTag(segment.tag),
            )
        }
        if (remainder > 0) {
            Box(
                Modifier
                    .weight(remainder.toFloat())
                    .fillMaxHeight()
                    .background(MaterialTheme.colorScheme.surfaceContainerHighest)
                    .testTag("swarmBarSegmentPending"),
            )
        }
    }
}

private data class SwarmBarSegment(val tag: String, val color: Color, val count: Int)

/** Web-style compact tool row (ticket 022): leading tool icon, name, one-line
 * monospace preview of the input with end-ellipsis, and the status as a glyph —
 * spinner while running, green check on success, red cross on error.
 *
 * Tapping a row that has details expands it (ticket 023, chevron rotates per web):
 * the call's arguments as a key/value table (ticket 118 — ToolDetailFields; the
 * pretty-printed JSON input string only when the call carried no object args), then
 * the result output — monospace, preformatted, in a capped-height region that scrolls
 * internally so a huge test log does not tunnel the timeline. Error output sits on an
 * error-tinted surface. The top-anchored chat list keeps the tapped header in place,
 * so the details unfold downward.
 *
 * An `Agent` row linked to its sub-agent (ticket 045) never expands: the sub-agent
 * page already shows everything the details would, so tapping the row navigates to
 * that page, as does the trailing open button (chevron-right, own tap target).
 *
 * An `AgentSwarm` row with at least one linked member (ticket 134) is replaced
 * wholesale by the swarm card — the aggregate visuals (135: status chip,
 * "{done}/{total}", the segmented phase bar, subtitle) over one tap target opening
 * the roster screen; with zero links (cold history before the mining lands) the
 * generic row below renders.
 *
 * A background `Bash` row linked to its job (ticket 096, [ChatItem.ToolActivity.bashTaskId])
 * behaves the same way: its result is only the `task_id:` stub — the command's real
 * output lives on the job screen, so tapping the row or its trailing "Open job"
 * chevron navigates there instead of expanding.
 *
 * `AskUserQuestion` rows (tickets 088/089) never expand either and show no chevron:
 * the raw JSON is superseded by the card UI. The tap is forwarded as
 * [onQuestionRowTapped] with the row's id (= tool call id = question id), input and
 * output — the collaborator decides: the pending row toggles the live card, a
 * resolved row opens/closes its read-only view.
 *
 * An `Edit` row with a parsed payload (ticket 119) never expands either: the pencil
 * icon, the trailing `+a −b` stat chip (the diff's line counts by delta type) and an
 * "All" badge on a replace_all call sketch the edit's shape, and the tap — row or
 * trailing open button — navigates to the diff screen. An Edit call whose args did
 * not parse keeps the generic expansion.
 *
 * `Read`/`Write` rows with a parsed payload (ticket 120) behave the same way: the
 * document icon, a Read's `lines a–b` chip (only when line_offset/n_lines restrict
 * the read) or a Write's `N lines · size` chip (and an "append" badge for
 * `mode: append`), and the tap — row or trailing open button — navigates to the
 * file content screen with the call's output as the snapshot. An unparseable
 * Read/Write call keeps the generic expansion.
 *
 * A `Bash` row with a parsed payload (ticket 121) KEEPS its inline expansion — the
 * first payload-typed expanded row — but the generic key/value table is replaced by
 * a terminal-styled layout: the call's `description` as a caption, the command in a
 * `$ `-prompted block, and a chips row (cwd, timeout) that only shows non-default
 * knobs. An output ending with the harness's `Command failed with exit code: N`
 * trailer renders stripped, the code lifted into an `exit N` error chip.
 *
 * The read-only lookup rows (ticket 122) keep the inline expansion too:
 * `Glob` (magnifier glyph, monospace `pattern` preview) expands to its scope chips
 * (path, include_ignored — non-default only) and a "N matches" header over the
 * path list, the server's truncation marker lifted into a chip ([GlobOutputs]);
 * `WebSearch` (globe glyph, prose `query` preview) expands to the result text
 * through the chat markdown renderer; `FetchURL` (link glyph, host-first shortened
 * URL preview — [hostFirstUrl]) expands to the full selectable URL, the fetch note
 * as a caption when it parses ([FetchUrlOutputs]) and the content verbatim. An
 * unparseable call of any of the three keeps the generic expansion.
 *
 * A `Grep` row with a parsed payload (ticket 123) keeps the inline expansion too:
 * the magnifier glyph (the Glob row's), a slash-delimited monospace `/pattern/`
 * preview and a trailing scope chip (the `path`, or the `glob` when no path is
 * set). The expansion shows the non-default filter knobs as chips (ignore case,
 * ±N/+N/-N context, multiline, include hidden, type — never head_limit/offset/-n)
 * and the result parsed per `output_mode` ([GrepResultParser]): per-file match
 * groups, the plain path list, or the count-badge rows. Any line failing the
 * mode's grammar renders the WHOLE output verbatim.
 *
 * The cron rows (ticket 124) carry the clock glyph: a parsed `CronCreate` keeps
 * the inline expansion — the scheduled prompt in full replaces the generic args
 * table and the result renders verbatim (its wire shape is `key: value` prose,
 * not JSON — docs/server-api.md) — with the cron expression as the monospace
 * preview and a "one-shot" badge on `recurring: false`. A parsed `CronDelete`
 * stays a lean row (id preview, generic expansion); `CronList` (no payload — a
 * call carries no args, so the row is name-keyed) previews "list schedules" and
 * renders its multi-record output verbatim.
 *
 * A task-control row whose parsed payload carries a `task_id` (`TaskOutput`,
 * `TaskStop`, `WaitFor` when set — ticket 124) behaves like a background-linked
 * Bash row: the tap or the trailing "Open job" chevron navigates to the job
 * screen, and the row never expands inline. A wait-any `WaitFor` (no task_id)
 * and `TaskList` keep the generic expansion.
 *
 * The meta tools (ticket 125) are lean tuned rows — nothing navigates away and
 * the generic key/value table stays (only `ExitPlanMode` adds its option labels
 * as a chips row above it). `Skill` previews the invocation (name + args —
 * ToolPreview), `ReadMediaFile` carries the image glyph ([ImageIcon]) and a
 * `crop W×H` badge when the region parsed, `ExitPlanMode` previews the fixed
 * "Plan ready for review" label (prose, the CronList label's recipe), `CreateGoal`
 * previews the objective, `UpdateGoal` shows no preview — the parsed status is a
 * small badge instead (success/error/primary per the payload's badgeColor, the
 * AA-checked container/content pairs), and `SetGoalBudget` previews "30 minutes"
 * (ToolPreview). `GetGoal`/`EnterPlanMode` carry no payload at all — default row.
 */
@Composable
internal fun ToolRow(
    activity: ChatItem.ToolActivity,
    onOpenSubagent: (String) -> Unit = {},
    onOpenJob: (String) -> Unit = {},
    onQuestionRowTapped: (toolCallId: String, input: String, output: String) -> Unit = { _, _, _ -> },
    onOpenEditDiff: (toolCallId: String, payload: ToolPayload.Edit) -> Unit = { _, _ -> },
    onOpenFileContent: (toolCallId: String, payload: ToolPayload, output: String) -> Unit = { _, _, _ -> },
    swarmMembers: Map<String, List<SubagentStreamStore.SubagentState>> = emptyMap(),
    onOpenSwarm: (String) -> Unit = {},
) {
    val swarmMemberList = swarmMembers[activity.id].orEmpty()
    if (activity.name == AGENT_SWARM_TOOL_NAME && swarmMemberList.isNotEmpty()) {
        SwarmCard(activity, swarmMemberList, onOpenSwarm)
        return
    }
    val detailColor = MaterialTheme.colorScheme.onSurfaceVariant
    val linkedSubagentId = activity.subagentId
    val linkedBashTaskId = activity.bashTaskId
    val isQuestionRow = activity.name == "AskUserQuestion"
    val editPayload = activity.payload as? ToolPayload.Edit
    val readPayload = activity.payload as? ToolPayload.Read
    val writePayload = activity.payload as? ToolPayload.Write
    val filePayload: ToolPayload? = readPayload ?: writePayload
    val bashPayload = activity.payload as? ToolPayload.Bash
    val globPayload = activity.payload as? ToolPayload.Glob
    val webSearchPayload = activity.payload as? ToolPayload.WebSearch
    val fetchUrlPayload = activity.payload as? ToolPayload.FetchUrl
    val grepPayload = activity.payload as? ToolPayload.Grep
    val lookupPayload = globPayload ?: webSearchPayload ?: fetchUrlPayload ?: grepPayload
    val cronCreatePayload = activity.payload as? ToolPayload.CronCreate
    val cronDeletePayload = activity.payload as? ToolPayload.CronDelete
    // CronList carries no args, so no payload exists — the row is name-keyed.
    val isCronListRow = activity.name == "CronList"
    // Ticket 124: a task-control row links to the job screen by its payload's
    // task_id — the exact background-Bash treatment (a wait-any WaitFor has none).
    val linkedTaskControlId = when (val payload = activity.payload) {
        is ToolPayload.TaskOutput -> payload.taskId
        is ToolPayload.TaskStop -> payload.taskId
        is ToolPayload.WaitFor -> payload.taskId
        else -> null
    }
    val linkedJobTaskId = linkedBashTaskId ?: linkedTaskControlId
    // Ticket 125: the meta rows' structured bits (icon, badges, option chips) read
    // the parsed payload; Skill/CreateGoal/SetGoalBudget are ToolPreview-only and
    // need no row local.
    val readMediaPayload = activity.payload as? ToolPayload.ReadMedia
    val exitPlanPayload = activity.payload as? ToolPayload.ExitPlanMode
    val updateGoalPayload = activity.payload as? ToolPayload.UpdateGoal
    // The chip counts come from the diff itself (delta types), not a line split.
    val editDiff = remember(editPayload) { editPayload?.let { EditDiffs.of(it.oldString, it.newString) } }
    val detailFields = remember(activity.args) { ToolDetailFields.of(activity.args) }
    // Ticket 121: a parsed Bash row's output with the harness's exit-code trailer
    // lifted out (null for every other row — their output renders verbatim).
    val bashOutput = remember(bashPayload, activity.output) {
        if (bashPayload != null) BashOutputs.of(activity.output) else null
    }
    // Ticket 122: the lookup rows' parsed outputs. Glob's rich list is success-only
    // (an error output is a status sentence — it renders verbatim on the error
    // surface); null parses mean "render verbatim".
    val globOutput = remember(globPayload, activity.output, activity.status) {
        if (globPayload != null && activity.status != ToolStatus.Error) GlobOutputs.of(activity.output) else null
    }
    val fetchUrlOutput = remember(fetchUrlPayload, activity.output) {
        if (fetchUrlPayload != null) FetchUrlOutputs.of(activity.output) else null
    }
    // Ticket 123: the Grep row's output parsed per its output_mode. Success-only
    // like Glob (an error output is tool prose — it renders verbatim on the error
    // surface); a null parse means "render the whole output verbatim".
    val grepResult = remember(grepPayload, activity.output, activity.status) {
        if (grepPayload != null && activity.status != ToolStatus.Error) {
            GrepResultParser.of(activity.output, grepPayload.outputMode)
        } else {
            null
        }
    }
    // A parsed lookup row's details no longer include the generic args table, so the
    // row is expandable only when its rich expansion has something to show.
    val lookupExpandable = when {
        fetchUrlPayload != null -> true // the full URL always renders
        globPayload != null -> activity.output.isNotBlank() || globPayload.path != null || globPayload.includeIgnored
        webSearchPayload != null -> activity.output.isNotBlank()
        grepPayload != null -> activity.output.isNotBlank() || grepPayload.hasNonDefaultKnobs
        else -> false
    }
    val expandable = !isQuestionRow && editPayload == null && filePayload == null && linkedSubagentId == null &&
        linkedJobTaskId == null &&
        (lookupExpandable || cronCreatePayload != null || (lookupPayload == null &&
            (detailFields.isNotEmpty() || activity.input.isNotBlank() || activity.output.isNotBlank())))
    var expanded by rememberSaveable { mutableStateOf(false) }
    Column(Modifier.fillMaxWidth()) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                // M3: the whole row is the tap target, at least 48dp tall.
                .heightIn(min = 48.dp)
                .clickable(
                    enabled = expandable || linkedSubagentId != null || linkedJobTaskId != null ||
                        isQuestionRow || editPayload != null || filePayload != null,
                ) {
                    if (isQuestionRow) onQuestionRowTapped(activity.id, activity.input, activity.output)
                    else if (editPayload != null) onOpenEditDiff(activity.id, editPayload)
                    else if (filePayload != null) onOpenFileContent(activity.id, filePayload, activity.output)
                    else if (expandable) expanded = !expanded
                    else if (linkedSubagentId != null) onOpenSubagent(linkedSubagentId)
                    else linkedJobTaskId?.let(onOpenJob)
                },
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            // Terminal glyph for shell calls (material-icons-core has no terminal icon),
            // a pencil for parsed Edit calls (ticket 119), a document for parsed
            // Read/Write calls (ticket 120), the lookup glyphs for parsed
            // Glob/WebSearch/FetchURL calls (ticket 122) and Grep calls (ticket 123 —
            // the Glob row's magnifier), the clock for cron calls (ticket 124 —
            // CronCreate/CronDelete by payload, CronList by name: it carries no
            // args), the image glyph for parsed ReadMediaFile calls (ticket 125 —
            // the one meta row with a bespoke glyph), a generic tool icon otherwise.
            // The tags mark the lookup/cron/media glyphs for the row tests.
            if (activity.name == "Bash") {
                Text(
                    text = ">_",
                    style = MaterialTheme.typography.labelMedium,
                    fontFamily = FontFamily.Monospace,
                    color = detailColor,
                )
            } else {
                Icon(
                    imageVector = when {
                        editPayload != null -> EditIcon
                        filePayload != null -> DocumentIcon
                        globPayload != null -> SearchIcon
                        webSearchPayload != null -> GlobeIcon
                        fetchUrlPayload != null -> LinkIcon
                        grepPayload != null -> SearchIcon
                        cronCreatePayload != null || cronDeletePayload != null || isCronListRow -> ClockIcon
                        readMediaPayload != null -> ImageIcon
                        else -> Icons.Filled.Build
                    },
                    contentDescription = null,
                    modifier = Modifier
                        .size(14.dp)
                        .then(
                            when {
                                globPayload != null -> Modifier.testTag("toolIconGlob")
                                webSearchPayload != null -> Modifier.testTag("toolIconWebSearch")
                                fetchUrlPayload != null -> Modifier.testTag("toolIconFetchUrl")
                                grepPayload != null -> Modifier.testTag("toolIconGrep")
                                cronCreatePayload != null || cronDeletePayload != null || isCronListRow ->
                                    Modifier.testTag("toolIconCron")
                                readMediaPayload != null -> Modifier.testTag("toolIconReadMedia")
                                else -> Modifier
                            },
                        ),
                    tint = detailColor,
                )
            }
            // A parsed FetchURL row leads with the host (ticket 122): the URL's
            // identifying part on a narrow row; the full URL is the expansion's.
            // A parsed Grep row carries its pattern slash-delimited (ticket 123).
            // CronList has no identifying argument (no args at all) — its preview
            // is a fixed label (ticket 124). Ticket 125: a parsed ExitPlanMode row
            // previews the fixed "Plan ready for review" label (the CronList
            // recipe); an UpdateGoal row shows NO preview — the status badge
            // replaces it.
            val rowPreview = when {
                fetchUrlPayload != null -> hostFirstUrl(fetchUrlPayload.url)
                grepPayload != null -> "/${ToolPreview.oneLine(grepPayload.pattern)}/"
                isCronListRow -> "list schedules"
                exitPlanPayload != null -> "Plan ready for review"
                updateGoalPayload != null -> ""
                else -> activity.preview
            }
            Text(
                text = activity.name.ifBlank { "tool" },
                style = MaterialTheme.typography.labelMedium,
                color = detailColor,
            )
            if (rowPreview.isNotBlank()) {
                Text(
                    text = rowPreview,
                    modifier = Modifier.weight(1f),
                    style = MaterialTheme.typography.labelMedium,
                    // The WebSearch query is prose (ticket 122), and CronList's
                    // preview is a fixed label (ticket 124) — as is ExitPlanMode's
                    // (ticket 125). Every other preview is a machine string and
                    // stays monospace.
                    fontFamily = if (webSearchPayload != null || isCronListRow || exitPlanPayload != null) {
                        null
                    } else {
                        FontFamily.Monospace
                    },
                    color = detailColor,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            } else {
                Spacer(Modifier.weight(1f))
            }
            if (editPayload != null && editPayload.replaceAll) {
                // The replace_all flag (ticket 119): the one badge case, matching the
                // sub-agent status chip's shape.
                Surface(color = MaterialTheme.colorScheme.secondaryContainer, shape = MaterialTheme.shapes.small) {
                    Text(
                        text = "All",
                        modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp),
                        style = MaterialTheme.typography.labelMedium,
                    )
                }
            }
            if (writePayload != null && writePayload.append) {
                // The `mode: append` flag (ticket 120), the "All" badge's shape.
                Surface(color = MaterialTheme.colorScheme.secondaryContainer, shape = MaterialTheme.shapes.small) {
                    Text(
                        text = "append",
                        modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp),
                        style = MaterialTheme.typography.labelMedium,
                    )
                }
            }
            if (cronCreatePayload != null && !cronCreatePayload.recurring) {
                // The `recurring: false` flag (ticket 124), the "All" badge's shape.
                Surface(color = MaterialTheme.colorScheme.secondaryContainer, shape = MaterialTheme.shapes.small) {
                    Text(
                        text = "one-shot",
                        modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp),
                        style = MaterialTheme.typography.labelMedium,
                    )
                }
            }
            readMediaPayload?.cropLabel?.let { label ->
                // The region crop (ticket 125), the "All" badge's shape — absent
                // without a full region (the parser is all-or-nothing).
                Surface(color = MaterialTheme.colorScheme.secondaryContainer, shape = MaterialTheme.shapes.small) {
                    Text(
                        text = label,
                        modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp),
                        style = MaterialTheme.typography.labelMedium,
                    )
                }
            }
            updateGoalPayload?.let { payload ->
                // The goal status badge (ticket 125) replaces the row's preview. The
                // ticket's named colors ride as the badge's CONTENT on their
                // AA-checked container pairs: the diff palettes (whose content
                // colors ARE successColor / the scheme's error) and the primary
                // pair; a drifted status gets the neutral badge every flag uses.
                val (container, content) = when (payload.badgeColor) {
                    ToolPayload.UpdateGoal.BadgeColor.SUCCESS ->
                        MaterialTheme.diffColors.addedContainer to MaterialTheme.diffColors.addedContent
                    ToolPayload.UpdateGoal.BadgeColor.ERROR ->
                        MaterialTheme.diffColors.removedContainer to MaterialTheme.diffColors.removedContent
                    ToolPayload.UpdateGoal.BadgeColor.PRIMARY ->
                        MaterialTheme.colorScheme.primaryContainer to MaterialTheme.colorScheme.onPrimaryContainer
                    ToolPayload.UpdateGoal.BadgeColor.NEUTRAL ->
                        MaterialTheme.colorScheme.secondaryContainer to MaterialTheme.colorScheme.onSecondaryContainer
                }
                Surface(color = container, shape = MaterialTheme.shapes.small) {
                    Text(
                        text = payload.status,
                        modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp),
                        style = MaterialTheme.typography.labelMedium,
                        color = content,
                    )
                }
            }
            if (editDiff != null) {
                Text(
                    text = "+${editDiff.addedCount}",
                    style = MaterialTheme.typography.labelMedium,
                    fontFamily = FontFamily.Monospace,
                    color = MaterialTheme.successColor,
                )
                Text(
                    text = "−${editDiff.removedCount}",
                    style = MaterialTheme.typography.labelMedium,
                    fontFamily = FontFamily.Monospace,
                    color = MaterialTheme.colorScheme.error,
                )
            }
            readPayload?.rangeLabel?.let { label ->
                Text(
                    text = label,
                    style = MaterialTheme.typography.labelMedium,
                    fontFamily = FontFamily.Monospace,
                    color = detailColor,
                )
            }
            if (writePayload != null) {
                Text(
                    text = "${writePayload.lineCount} lines · ${formatFileSize(writePayload.byteSize)}",
                    style = MaterialTheme.typography.labelMedium,
                    fontFamily = FontFamily.Monospace,
                    color = detailColor,
                )
            }
            // The Grep row's scope chip (ticket 123): the `path`, or the `glob` when
            // no path is set — the Read range chip's recipe (plain text, no pill),
            // weight-bounded so a long path ellipsizes instead of crowding the row.
            (grepPayload?.path ?: grepPayload?.glob)?.let { scope ->
                Text(
                    text = scope,
                    modifier = Modifier.weight(1f, fill = false),
                    style = MaterialTheme.typography.labelMedium,
                    fontFamily = FontFamily.Monospace,
                    color = detailColor,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
            ToolStatusIcon(activity.status, runningDescription = "Tool running", modifier = Modifier.size(12.dp))
            if (expandable) {
                val chevronRotation by animateFloatAsState(if (expanded) 180f else 0f, label = "chevron")
                Icon(
                    imageVector = Icons.Filled.KeyboardArrowDown,
                    contentDescription = if (expanded) "Collapse tool details" else "Expand tool details",
                    modifier = Modifier
                        .size(16.dp)
                        .rotate(chevronRotation),
                    tint = detailColor,
                )
            }
            if (linkedSubagentId != null) {
                // Bare clickable Icon, not an IconButton (same for the three open
                // chevrons below): the button's 40dp touch-target padding would
                // inset the glyph from the row edge, misaligning it with the
                // expand chevron. The whole row navigates on tap anyway.
                Icon(
                    imageVector = Icons.AutoMirrored.Filled.KeyboardArrowRight,
                    contentDescription = "Open sub-agent",
                    modifier = Modifier
                        .size(16.dp)
                        .clickable { onOpenSubagent(linkedSubagentId) },
                    tint = detailColor,
                )
            }
            if (editPayload != null) {
                // Same open affordance as the linked rows above: the row tap already
                // navigates, this chevron is the explicit target (ticket 119).
                Icon(
                    imageVector = Icons.AutoMirrored.Filled.KeyboardArrowRight,
                    contentDescription = "Open diff",
                    modifier = Modifier
                        .size(16.dp)
                        .clickable { onOpenEditDiff(activity.id, editPayload) },
                    tint = detailColor,
                )
            }
            if (filePayload != null) {
                // Same open affordance as the Edit row above (ticket 120).
                Icon(
                    imageVector = Icons.AutoMirrored.Filled.KeyboardArrowRight,
                    contentDescription = "Open file",
                    modifier = Modifier
                        .size(16.dp)
                        .clickable { onOpenFileContent(activity.id, filePayload, activity.output) },
                    tint = detailColor,
                )
            }
            if (linkedJobTaskId != null) {
                // Background-Bash rows (ticket 096) and task-control rows (ticket
                // 124) share this one "Open job" affordance.
                Icon(
                    imageVector = Icons.AutoMirrored.Filled.KeyboardArrowRight,
                    contentDescription = "Open job",
                    modifier = Modifier
                        .size(16.dp)
                        .clickable { onOpenJob(linkedJobTaskId) },
                    tint = detailColor,
                )
            }
        }
        if (expanded && expandable) {
            Column(
                modifier = Modifier.padding(bottom = 4.dp),
                verticalArrangement = Arrangement.spacedBy(4.dp),
            ) {
                when {
                    bashPayload != null -> {
                        // Ticket 121's terminal layout replaces the generic args table.
                        bashPayload.description?.let { description ->
                            Text(
                                text = description,
                                style = MaterialTheme.typography.bodySmall,
                                color = detailColor,
                            )
                        }
                        BashCommandBlock(bashPayload.command)
                        BashChipsRow(bashPayload, activity)
                    }
                    globPayload != null -> GlobChipsRow(globPayload)
                    grepPayload != null -> GrepChipsRow(grepPayload)
                    cronCreatePayload != null -> {
                        // Ticket 124: the scheduled prompt in full replaces the
                        // generic args table (the table one-lines its values); the
                        // cron expression is the row's preview, the recurring flag
                        // the row's badge — nothing else to show.
                        ToolDetailBlock(text = cronCreatePayload.prompt, color = MaterialTheme.colorScheme.surfaceVariant)
                    }
                    fetchUrlPayload != null -> {
                        // The full URL (the row shows it host-first, ticket 122).
                        SelectionContainer {
                            Text(
                                text = fetchUrlPayload.url,
                                style = MaterialTheme.typography.bodySmall,
                                fontFamily = FontFamily.Monospace,
                            )
                        }
                    }
                    // WebSearch: nothing to add — the query already leads the row.
                    webSearchPayload != null -> Unit
                    exitPlanPayload != null -> {
                        // Ticket 125: the option labels as a chips row ABOVE the
                        // generic args table (which stays — the wire's options
                        // objects render as one compact-JSON value row).
                        if (exitPlanPayload.optionLabels.isNotEmpty()) {
                            ExitPlanOptionsRow(exitPlanPayload.optionLabels)
                        }
                        if (detailFields.isNotEmpty()) {
                            ToolArgsTable(detailFields)
                        } else if (activity.input.isNotBlank()) {
                            ToolDetailBlock(text = activity.input, color = MaterialTheme.colorScheme.surfaceVariant)
                        }
                    }
                    detailFields.isNotEmpty() -> {
                        // The args table replaces the pretty-printed input JSON (ticket
                        // 118); the string only remains for calls that carried no object
                        // args.
                        ToolArgsTable(detailFields)
                    }
                    activity.input.isNotBlank() ->
                        ToolDetailBlock(text = activity.input, color = MaterialTheme.colorScheme.surfaceVariant)
                }
                if (activity.output.isNotBlank()) {
                    val outputColor = if (activity.status == ToolStatus.Error) {
                        MaterialTheme.colorScheme.errorContainer
                    } else {
                        MaterialTheme.colorScheme.secondaryContainer
                    }
                    when {
                        globOutput != null -> {
                            GlobResultHeader(globOutput)
                            ToolDetailBlock(text = globOutput.lines.joinToString("\n"), color = outputColor)
                        }
                        webSearchPayload != null -> WebSearchResultBlock(activity.output, color = outputColor)
                        grepPayload != null -> when (val result = grepResult) {
                            // Any line failing the mode's grammar degrades the WHOLE
                            // output to verbatim (ticket 123) — never a half-parsed mix.
                            null -> ToolDetailBlock(text = activity.output, color = outputColor)
                            is GrepResultParser.Result.Content -> {
                                GrepContentResults(result, outputColor)
                                GrepNotices(result.notices)
                            }
                            is GrepResultParser.Result.FileList -> {
                                GrepFilesHeader(result.paths.size)
                                ToolDetailBlock(text = result.paths.joinToString("\n"), color = outputColor)
                                GrepNotices(result.notices)
                            }
                            is GrepResultParser.Result.Counts -> {
                                GrepCountResults(result, outputColor)
                                GrepNotices(result.notices)
                            }
                        }
                        fetchUrlOutput != null -> {
                            fetchUrlOutput.note?.let { note ->
                                Text(
                                    text = note,
                                    style = MaterialTheme.typography.bodySmall,
                                    color = detailColor,
                                )
                            }
                            ToolDetailBlock(text = fetchUrlOutput.content, color = outputColor)
                        }
                        else -> {
                            ToolDetailBlock(
                                text = bashOutput?.output ?: activity.output,
                                color = outputColor,
                                // Live tool.progress chunks (ticket 025): keep the tail in view.
                                pinnedToBottom = activity.status == ToolStatus.Running,
                            )
                            bashOutput?.exitCode?.let { code ->
                                // The lifted trailer (ticket 121): the status glyph already
                                // turned red via is_error, the chip adds the code.
                                Surface(color = MaterialTheme.colorScheme.error, shape = MaterialTheme.shapes.small) {
                                    Text(
                                        text = "exit $code",
                                        modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp),
                                        style = MaterialTheme.typography.labelMedium,
                                        fontFamily = FontFamily.Monospace,
                                        color = MaterialTheme.colorScheme.onError,
                                    )
                                }
                            }
                        }
                    }
                }
            }
        }
    }
}

/** One preformatted monospace block of the expanded tool row. The height is capped
 * and scrolls internally, so a very long output never stretches the timeline.
 * [pinnedToBottom] (a running tool's live output, ticket 025) re-scrolls to the tail
 * every time the text grows. */
@Composable
private fun ToolDetailBlock(text: String, color: Color, pinnedToBottom: Boolean = false) {
    val scrollState = rememberScrollState()
    if (pinnedToBottom) {
        LaunchedEffect(text) { scrollState.scrollTo(scrollState.maxValue) }
    }
    Surface(color = color, shape = MaterialTheme.shapes.small) {
        Box(
            Modifier
                .fillMaxWidth()
                .heightIn(max = 240.dp)
                .verticalScroll(scrollState)
                .padding(horizontal = 10.dp, vertical = 8.dp),
        ) {
            Text(
                text = text,
                style = MaterialTheme.typography.bodySmall,
                fontFamily = FontFamily.Monospace,
            )
        }
    }
}

/** The Bash command's terminal block (ticket 121): the dimmed `$ ` prompt prefix and
 * the command, monospace, on a `surfaceContainerHigh` surface — the [ToolDetailBlock]
 * recipe (capped height, internal scroll). */
@Composable
private fun BashCommandBlock(command: String) {
    Surface(color = MaterialTheme.colorScheme.surfaceContainerHigh, shape = MaterialTheme.shapes.small) {
        Row(
            Modifier
                .fillMaxWidth()
                .heightIn(max = 240.dp)
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 10.dp, vertical = 8.dp),
        ) {
            Text(
                text = "\$ ",
                style = MaterialTheme.typography.bodySmall,
                fontFamily = FontFamily.Monospace,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Text(
                text = command,
                modifier = Modifier.weight(1f),
                style = MaterialTheme.typography.bodySmall,
                fontFamily = FontFamily.Monospace,
            )
        }
    }
}

/** The Bash row's non-default knobs (ticket 121): cwd (folder glyph + path,
 * monospace) and the timeout chip — `⏱ Ns` for an explicit timeout, "no timeout" for
 * `disable_timeout`, the row's own budget fields (ticket 097) so the chips always
 * agree with the job screen's budget display. Nothing renders when everything is
 * default. */
@Composable
private fun BashChipsRow(payload: ToolPayload.Bash, activity: ChatItem.ToolActivity) {
    val timeoutSeconds = activity.timeoutSeconds
    val timeoutDisabled = activity.timeoutDisabled
    if (payload.cwd == null && timeoutSeconds == null && !timeoutDisabled) return
    Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
        payload.cwd?.let { cwd ->
            // Bounded (weight, no fill): a long path ellipsizes instead of pushing
            // the timeout chip out of the row.
            Surface(
                color = MaterialTheme.colorScheme.secondaryContainer,
                shape = MaterialTheme.shapes.small,
                modifier = Modifier.weight(1f, fill = false),
            ) {
                Row(
                    modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(4.dp),
                ) {
                    Icon(
                        imageVector = WorkspaceIcon,
                        contentDescription = null,
                        modifier = Modifier.size(12.dp),
                    )
                    Text(
                        text = cwd,
                        style = MaterialTheme.typography.labelMedium,
                        fontFamily = FontFamily.Monospace,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
            }
        }
        if (timeoutSeconds != null || timeoutDisabled) {
            Surface(color = MaterialTheme.colorScheme.secondaryContainer, shape = MaterialTheme.shapes.small) {
                Text(
                    // An explicit timeout wins over disable_timeout — the
                    // BashTimeout.effectiveSeconds ordering.
                    text = if (timeoutSeconds != null) "⏱ ${timeoutSeconds}s" else "no timeout",
                    modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp),
                    style = MaterialTheme.typography.labelMedium,
                )
            }
        }
    }
}

/** The ExitPlanMode expansion's option labels (ticket 125): one pill per label —
 * the "All" badge's shape. Sits above the generic args table; nothing renders
 * for a plain plan approval (no options on the wire). */
@Composable
private fun ExitPlanOptionsRow(labels: List<String>) {
    Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
        labels.forEach { label ->
            Surface(color = MaterialTheme.colorScheme.secondaryContainer, shape = MaterialTheme.shapes.small) {
                Text(
                    text = label,
                    modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp),
                    style = MaterialTheme.typography.labelMedium,
                )
            }
        }
    }
}

/** The generic key/value table of a call's arguments (ticket 118): one row per
 * top-level arg — label column in `labelMedium` `onSurfaceVariant`, value column
 * monospace `bodySmall`. No height cap: unlike a raw output dump, the per-arg rows
 * stay short (values are one-lined by ToolDetailFields). */
@Composable
private fun ToolArgsTable(fields: List<ToolDetailFields.Field>) {
    Surface(color = MaterialTheme.colorScheme.surfaceVariant, shape = MaterialTheme.shapes.small) {
        Column(
            Modifier
                .fillMaxWidth()
                .padding(horizontal = 10.dp, vertical = 8.dp),
            verticalArrangement = Arrangement.spacedBy(4.dp),
        ) {
            fields.forEach { field ->
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text(
                        text = field.label,
                        modifier = Modifier.weight(1f),
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    Text(
                        text = field.value,
                        modifier = Modifier.weight(2f),
                        style = MaterialTheme.typography.bodySmall,
                        fontFamily = FontFamily.Monospace,
                    )
                }
            }
        }
    }
}

/** The Glob row's non-default scope knobs (ticket 122): the `path` override (folder
 * glyph + path, monospace, the Bash cwd chip's recipe) and an "including ignored"
 * chip for `include_ignored: true`. Nothing renders when everything is default. */
@Composable
private fun GlobChipsRow(payload: ToolPayload.Glob) {
    if (payload.path == null && !payload.includeIgnored) return
    Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
        payload.path?.let { path ->
            // Bounded (weight, no fill): a long path ellipsizes instead of pushing
            // the ignored chip out of the row.
            Surface(
                color = MaterialTheme.colorScheme.secondaryContainer,
                shape = MaterialTheme.shapes.small,
                modifier = Modifier.weight(1f, fill = false),
            ) {
                Row(
                    modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(4.dp),
                ) {
                    Icon(
                        imageVector = WorkspaceIcon,
                        contentDescription = null,
                        modifier = Modifier.size(12.dp),
                    )
                    Text(
                        text = path,
                        style = MaterialTheme.typography.labelMedium,
                        fontFamily = FontFamily.Monospace,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
            }
        }
        if (payload.includeIgnored) {
            Surface(color = MaterialTheme.colorScheme.secondaryContainer, shape = MaterialTheme.shapes.small) {
                Text(
                    text = "including ignored",
                    modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp),
                    style = MaterialTheme.typography.labelMedium,
                )
            }
        }
    }
}

/** The Glob expansion's result header (ticket 122): the match count (the listed
 * lines, blanks dropped) plus the "truncated" chip when the server capped the
 * results ([GlobOutputs] stripped the marker). */
@Composable
private fun GlobResultHeader(output: GlobOutputs.Lifted) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        Text(
            text = if (output.lines.size == 1) "1 match" else "${output.lines.size} matches",
            style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        if (output.truncatedCount != null) {
            Surface(color = MaterialTheme.colorScheme.secondaryContainer, shape = MaterialTheme.shapes.small) {
                Text(
                    text = "truncated",
                    modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp),
                    style = MaterialTheme.typography.labelMedium,
                )
            }
        }
    }
}

/** The Grep row's non-default filter knobs (ticket 123): `ignore case` for `-i`,
 * the context chips (`±N` symmetric, `+N`/`-N` per side when asymmetric —
 * [ToolPayload.Grep.contextChips]), `multiline`, `include hidden` for
 * `include_ignored`, and the `type` filter. Nothing renders when everything is
 * default; head_limit/offset/`-n` never earn UI space (not even carried by the
 * payload). */
@Composable
private fun GrepChipsRow(payload: ToolPayload.Grep) {
    if (!payload.hasNonDefaultKnobs) return
    Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
        if (payload.caseInsensitive) GrepChip("ignore case")
        payload.contextChips.forEach { GrepChip(it) }
        if (payload.multiline) GrepChip("multiline")
        if (payload.includeIgnored) GrepChip("include hidden")
        payload.type?.let { GrepChip("type: $it") }
    }
}

@Composable
private fun GrepChip(label: String) {
    Surface(color = MaterialTheme.colorScheme.secondaryContainer, shape = MaterialTheme.shapes.small) {
        Text(
            text = label,
            modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp),
            style = MaterialTheme.typography.labelMedium,
        )
    }
}

/** The Grep files-mode result header (ticket 123): the listed-path count — the
 * Glob expansion's "N matches" recipe ([GlobResultHeader], minus the truncation
 * chip: Grep's own notices carry that information, as captions). */
@Composable
private fun GrepFilesHeader(pathCount: Int) {
    Text(
        text = if (pathCount == 1) "1 match" else "$pathCount matches",
        style = MaterialTheme.typography.labelMedium,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
    )
}

/** The Grep content-mode results (ticket 123): per-file groups — a `labelMedium`
 * `onSurfaceVariant` file header above monospace lines, match lines carrying their
 * line number dimmed at the left, context lines dimmed without a number. The `--`
 * separators collapse to plain 4dp spacing. Capped height with internal scroll —
 * the [ToolDetailBlock] recipe. */
@Composable
private fun GrepContentResults(result: GrepResultParser.Result.Content, color: Color) {
    Surface(color = color, shape = MaterialTheme.shapes.small) {
        Column(
            Modifier
                .fillMaxWidth()
                .heightIn(max = 240.dp)
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 10.dp, vertical = 8.dp),
        ) {
            result.items.forEachIndexed { index, item ->
                when (item) {
                    is GrepResultParser.ContentItem.Separator -> Spacer(Modifier.height(4.dp))
                    is GrepResultParser.ContentItem.Group -> {
                        // A separator already supplied the gap above this group.
                        if (index > 0 && result.items[index - 1] !is GrepResultParser.ContentItem.Separator) {
                            Spacer(Modifier.height(4.dp))
                        }
                        Text(
                            text = item.path,
                            style = MaterialTheme.typography.labelMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                        item.lines.forEach { line -> GrepContentLine(line) }
                    }
                }
            }
        }
    }
}

@Composable
private fun GrepContentLine(line: GrepResultParser.ContentLine) {
    if (line.context) {
        Text(
            text = line.text,
            style = MaterialTheme.typography.bodySmall,
            fontFamily = FontFamily.Monospace,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    } else {
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(
                text = "${line.number}",
                style = MaterialTheme.typography.bodySmall,
                fontFamily = FontFamily.Monospace,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Text(
                text = line.text,
                style = MaterialTheme.typography.bodySmall,
                fontFamily = FontFamily.Monospace,
            )
        }
    }
}

/** The Grep count-mode results (ticket 123): the server's aggregate summary line
 * when it parsed, then one row per file — the path (monospace, ellipsized) and its
 * match count as a `surfaceVariant` pill. Capped height with internal scroll — the
 * [ToolDetailBlock] recipe. */
@Composable
private fun GrepCountResults(result: GrepResultParser.Result.Counts, color: Color) {
    result.summary?.let { summary ->
        Text(
            text = summary,
            style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
    Surface(color = color, shape = MaterialTheme.shapes.small) {
        Column(
            Modifier
                .fillMaxWidth()
                .heightIn(max = 240.dp)
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 10.dp, vertical = 8.dp),
            verticalArrangement = Arrangement.spacedBy(4.dp),
        ) {
            result.entries.forEach { entry ->
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    Text(
                        text = entry.path,
                        modifier = Modifier.weight(1f),
                        style = MaterialTheme.typography.bodySmall,
                        fontFamily = FontFamily.Monospace,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                    Surface(color = MaterialTheme.colorScheme.surfaceVariant, shape = MaterialTheme.shapes.small) {
                        Text(
                            text = "${entry.count}",
                            modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp),
                            style = MaterialTheme.typography.labelMedium,
                            fontFamily = FontFamily.Monospace,
                        )
                    }
                }
            }
        }
    }
}

/** The tool notices [GrepResultParser] stripped from the output's head/tail
 * (ticket 123) — the pagination/truncation/timeout/sensitive-filter reports —
 * kept as dimmed captions below the result block so nothing the server said is
 * lost. Nothing renders when the output carried none. */
@Composable
private fun GrepNotices(notices: List<String>) {
    notices.forEach { notice ->
        Text(
            text = notice,
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

/** The WebSearch expansion (ticket 122): the result text through the chat markdown
 * renderer (read-only — the assistant bubble's component), in the [ToolDetailBlock]
 * recipe (capped height, internal scroll) so a long result never tunnels the
 * timeline. */
@Composable
private fun WebSearchResultBlock(output: String, color: Color) {
    Surface(color = color, shape = MaterialTheme.shapes.small) {
        Box(
            Modifier
                .fillMaxWidth()
                .heightIn(max = 240.dp)
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 10.dp, vertical = 8.dp),
        ) {
            MarkdownBody(output)
        }
    }
}

/** The FetchURL row's host-first preview (ticket 122): `host[:port]` plus the
 * path's first segment, an ellipsis marking the rest — `example.com/docs/…`. The
 * identifying part of a URL on a narrow row is the host; the full URL lives in the
 * expansion. A URL without a parseable host renders one-lined as-is. */
internal fun hostFirstUrl(url: String): String {
    val trimmed = url.trim()
    val uri = runCatching { java.net.URI(trimmed) }.getOrNull() ?: return ToolPreview.oneLine(trimmed)
    val host = uri.host ?: return ToolPreview.oneLine(trimmed)
    val authority = if (uri.port >= 0) "$host:${uri.port}" else host
    val path = uri.rawPath?.trim('/')?.takeIf { it.isNotEmpty() } ?: return authority
    val first = path.substringBefore('/')
    return if (first.length == path.length) "$authority/$first" else "$authority/$first/…"
}
