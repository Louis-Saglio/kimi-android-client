# Swarm card: aggregate status and progress bar

- **ID:** 135
- **Created:** 2026-09-15
- **Depends on:** 134

## Context

134 ships a functional but plain swarm card: label, member count, chevron.
The web's 0.43.0 `swarm-card` shows the aggregate at a glance: a
`{done}/{total}` chip, a segmented progress bar with one color per member
phase (queued grey, working blue, suspended amber, completed green, failed
red, cancelled grey), a subtitle ("3 in progress" while running,
"8 completed · 1 failed · 1 cancelled" once settled), and a danger tint when
the tool errored or any member failed. Louis picked this as part of the
swarm core on 2026-09-15.

The phase model needs one gap closed first: `SubagentStatus`
(SubagentStreamStore.kt:44) has Queued/Working/Suspended/Completed/Failed
but no Cancelled. A stopped member (server: `task.terminated` with
`status:"killed"`, no `subagent.completed`, docs/server-api.md:3478-3495)
would otherwise count as failed, which is the wrong signal: the user
cancelled it on purpose. The web separates cancelled for the same reason.

## Description

**1. Cancelled member state** (`ui/chat/SubagentStreamStore.kt`).
`SubagentStatus` gains `Cancelled`. Map member-scope `task.terminated` with
`info.status == "killed"` (live) and REST wire status `"cancelled"` (cold
start via the tasks list) to it. `SubagentStatusChip`
(SubagentScreen.kt:177-193) gains the cancelled style: grey container, close
icon, label "Cancelled".

**2. Warning color** (`ui/theme/Theme.kt`). Add
`LocalWarningColor`/`MaterialTheme.warningColor` following the
`LocalSuccessColor` pattern (Theme.kt:29-33), AA-checked in both themes, for
the suspended segment and chip. Suspended currently has no dedicated theme
color.

**3. Typed payload** (`data/chat/ToolPayload.kt`). Add
`ToolPayload.AgentSwarm` (register in `ToolPayloads` :256): `description`,
and `expectedTotal` derived from the `items` array size, or the
`resume_agent_ids` map size when only resumes are present. This lets the card
show totals before all members have spawned.

**4. Aggregator.** New small pure collaborator
`ui/chat/SwarmProgress.kt`: input the member list plus `expectedTotal?`,
output per-phase counts, done, total (`max(members.size, expectedTotal ?: 0)`),
and the aggregate status: Running when any member is Queued/Working/Suspended
or the tool call is still running; otherwise Failed when any member failed
(web tints danger on tool error or any failure); otherwise Cancelled when at
least one member was cancelled and none completed; otherwise Completed.
Cover the matrix in unit tests, including the empty-members running case.

**5. Card UI** (`ui/chat/components/ToolRows.kt`). The 134 card gains, below
the header row: the aggregate status chip, the "{done}/{total}" count, a
segmented progress bar (segments sized proportionally to phase counts, only
present phases rendered, colors: queued `outlineVariant`, working `primary`,
suspended `warningColor`, completed `successColor`, failed `error`, cancelled
`outlineVariant` with the cancelled count folded into the subtitle), and a
subtitle line: "{N} in progress" while running, else
"{c} completed · {f} failed" plus " · {k} cancelled" when k > 0 (web-parity
strings). Accessibility: one `contentDescription` on the bar summarizing the
counts; `testTag`s per segment group. Danger tint on the card border/head
when the aggregate is Failed, mirroring the web.

**6. Screen summary strip** (`ui/chat/SwarmScreen.kt`). Under the top bar, a
text strip rendering the same counts ("3 working · 5 completed · 1 failed")
from the same `SwarmProgress` input.

**7. Shared model line** (web parity, cheap): when every member record
carries the same non-null model and thinking effort, show one muted
`bodySmall` line "model · effort" under the card subtitle and in the screen
strip. Skip entirely when mixed or unknown.

**8. Tests.** `SwarmProgressTest` covering the aggregate matrix and totals
from `expectedTotal` before spawn; store test for the killed/cancelled
mapping both live and from the REST list; androidTest asserting segments,
counts, chip, and subtitle text for a synthetic member set.

## Acceptance criteria

- [ ] New unit + androidTests above pass; existing suite green.
- [ ] Observable on device: mid-swarm the card shows the live bar, the
      done/total count advancing, and "N in progress"; once settled it reads
      "c completed · f failed · k cancelled".
- [ ] A member stopped from the web UI (in-app stop is ticket 137) renders
      as cancelled, not failed, on both the card and the roster.
- [ ] The totals are correct before all members have spawned (from the
      `items` count), and the bar fills as members spawn.
- [ ] `flock /tmp/kimi-android-gradle.lock ./gradlew :app:testDebugUnitTest
      :app:assembleDebug --max-workers=1 --no-daemon` passes; release APK
      published to the file server per AGENTS.md.

## Out of scope

- `<agent_swarm_result>` parsing and not-started member backfill (136);
  settled aggregates here come from live and roster state only.
- The stop button (137) and detach action (138).
- Per-member duration on roster rows (the web does not show it either).

## Notes

- Web string reference (0.43.0 locale, verified in the bundle):
  "{count} in progress", "{completed} completed · {failed} failed ·
  {cancelled} cancelled", phases "Queued/Working/Suspended/Completed/
  Failed/Cancelled".
- Suspended means "provider rate limit; subagent requeued for retry"
  (upstream subagent-batch), never a permission wait; the amber treatment
  should read as transient, not as an error.
