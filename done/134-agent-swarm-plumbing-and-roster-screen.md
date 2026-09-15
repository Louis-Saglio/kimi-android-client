# Agent swarm: plumbing and roster screen

- **ID:** 134
- **Created:** 2026-09-15
- **Depends on:** none

## Context

A swarm on the wire is one `AgentSwarm` tool call (`prompt_template` with a
`{{item}}` placeholder plus `items[]`, max 128 members) spawning N subagents.
Every member then rides the ordinary subagent protocol: `subagent.spawned`
carries the shared `parentToolCallId`, a `swarmIndex`, a `description`, and a
`taskId` (docs/server-api.md:3257), and member frames stream on the session
socket tagged `agentId:"agent-N"`.

The app's per-member machinery is complete (tickets 044-046, 091, 098-101):
`SubagentStreamStore` keeps one record per member and `SubagentScreen` streams
it live with transcript history. Swarm members already get records and pages
today. What is missing is reachability and grouping: the link registry is
one-to-one with first-writer-wins (`SubagentStreamStore.kt:319`), so only the
first spawned member of a swarm is reachable from the `AgentSwarm` row; every
mining path checks `name == "Agent"` only; `swarmIndex` is parsed nowhere.
done/125 recorded the swarm UI as deliberately deferred.

Decisions (discussion with Louis, 2026-09-15):

- Navigation over inline expansion, confirmed by Louis. The web expands the
  swarm card into an in-timeline accordion; the app instead keeps a compact
  linked card that opens a full roster screen, consistent with how linked
  sub-agent rows already navigate to `SubagentScreen` instead of expanding.
  A 128-row accordion nested inside the timeline `LazyColumn` is both a
  performance problem and a cramped phone UX.
- Group by `parentToolCallId`, never by heuristics. done/091's out-of-scope
  note rejected heuristic linking precisely because it mis-links swarms.
- `swarmIndex` is schema-only upstream and was never captured live
  (docs/server-api.md:3311 note). Parse it, use it for ordering when present,
  but never depend on it: grouping comes from `parentToolCallId`, ordering
  falls back to spawn arrival order.

Alternatives rejected:

- Web-faithful inline accordion in the timeline (see above).
- A per-session "all subagents" screen instead of a per-swarm roster: loses
  the call-to-members grouping, which is the feature.

This ticket is the reachability slice. Aggregate visuals (135), result
parsing (136), stop (137), detach (138), approval badge (139), and the
composer toggle (140) are separate tickets.

## Description

**1. Constant and event** (`data/chat/ChatEvents.kt`). Add
`AGENT_SWARM_TOOL_NAME = "AgentSwarm"` next to `AGENT_TOOL_NAME` (:12).
`SubagentSpawned` (:106) gains `swarmIndex: Int?` and `taskId: String?` (both
on the wire since 0.38.0, docs/server-api.md:682).

**2. DTOs** (`data/chat/ChatDtos.kt`, `data/chat/Snapshot.kt`).
`SubagentSpawnedPayload` (:400) adds `val swarmIndex: Int? = null` and
`val taskId: String? = null`. `SubagentRosterEntryDto` (:457) adds
`@SerialName("swarm_index") val swarmIndex: Int? = null`; roster entries
extend the REST task object, so the task id should already be present, verify
and surface it if not. `SubagentRosterEntry` (Snapshot.kt:48) carries both new
fields. Update the "not consumed" comment at ChatDtos.kt:399.

**3. Socket mapping** (`data/chat/SessionSocket.kt:337-344`). Pass the two new
fields through; mapping shape is otherwise unchanged.

**4. Store** (`ui/chat/SubagentStreamStore.kt`).

- `links` (:319) becomes an ordered one-to-many registry
  `toolCallId -> List<subagentId>`; `registerLink` (:739) appends distinct
  ids preserving arrival order; `linksFor` (:343) returns the full list.
  Update the KDoc documenting the one-to-one assumption.
- `SubagentRecord` (:140-170) gains `swarmIndex: Int?` and `taskId: String?`,
  set from the spawned event and from `applyRoster`.
- New accessor `swarmMembers(sessionId, toolCallId): List<SubagentRecord>`
  sorted by `swarmIndex`, nulls keeping arrival order at the end.
- `applyAgentCall` (:511-564) gains an `AgentSwarm` branch: the args carry no
  `subagent_type`/`prompt`, so take the row label from the `description` arg
  and leave member records untouched.

**5. Name-check widening.** `mineAgentRowsIntoStore` (ChatViewModel.kt:902)
accepts `AgentSwarm` rows. `agentTaskIdOf` (data/chat/Tasks.kt:100) includes
`AgentSwarm` so background member tasks keep their page links in the jobs
seed. `agentResultSubagentId` (ChatTimeline.kt:796 and
SubagentStreamStore.kt:763) stays `Agent`-only: a swarm result names many
ids, and 136 handles it properly.

**6. Link driving** (ChatViewModel.kt:519-524 `applySubagentLinks`). Drive the
one-to-many registry so every member of a swarm gets linked. `ToolActivity`
(ChatItem.kt:63-76) keeps its shape: the swarm card navigates by the tool
call id it already holds; `subagentId` stays the single-agent link. Update
the KDoc.

**7. Swarm card v1** (`ui/chat/components/ToolRows.kt`). When the tool name is
`AgentSwarm` and at least one member link exists, render an `OutlinedCard` on
`surfaceContainerLow` (the tool-group idiom, ToolRows.kt:100-147): sparkles
icon (add one file under `ui/common/icons/` if absent), title "Swarm", the
`description` arg on one line (ToolPreview.kt:72 already extracts it),
trailing "{N} members" count from `linksFor`, chevron-right. The whole card
is one tap target calling `onOpenSwarm(toolCallId)`. With zero links (cold
history before mining), fall back to today's generic row. Thread the callback
through `MessageList.kt:62-63,182-188` and `ChatScreen` to the NavHost.

**8. Route and screen** (`navigation/KimiNavHost.kt`,
`ui/chat/SwarmScreen.kt`).

- `@Serializable data class Swarm(val sessionId: String, val toolCallId: String)`;
  ids only on the route, `launchSingleTop = true`, per convention.
- `SwarmScreen` mirrors `SubagentScreen`'s store access pattern (read
  `SubagentStreamStore` state, no content store needed since ids suffice).
  Top bar: back arrow, title "Swarm", supporting line with the description.
- Body: `LazyColumn` of member rows from `swarmMembers`, each row a
  `SubagentStatusChip` (idiom at SubagentScreen.kt:177-193), the member label
  (record description, else subagent name), a trailing chevron, tapping
  navigates to the existing `Subagent(sessionId, subagentId)` route.
- Empty state with the `StateViews.Empty` idiom when no members are known
  (deep cold start before the roster replays).

**9. Tests.**

- Unit: `SubagentStreamStoreTest` additions: multi-member registration
  preserves order and stays distinct, `swarmMembers` sorts by index with
  nulls last, the `AgentSwarm` branch of `applyAgentCall` takes the label
  from `description` and does not clobber member records.
- Unit: socket parse test for the new spawned fields, built from the verbatim
  docs capture with `swarmIndex`/`taskId` added.
- Unit: a 3-member swarm history mined through `ChatViewModelTestBase` links
  all three members to the one tool call.
- androidTest: the swarm card renders with the member count and fires
  `onOpenSwarm`; `SwarmScreen` renders member rows and navigates on tap;
  `RoutesTest` +1.

## Acceptance criteria

- [ ] New unit + androidTests above pass; existing suite green.
- [ ] Observable on device: prompt the agent to run an `AgentSwarm` (e.g.
      "use AgentSwarm with items a, b, c"); the timeline row becomes the
      swarm card with the member count; tapping it opens the roster; each
      member row shows its live status chip and opens its streaming
      `SubagentScreen` page; statuses settle as members finish.
- [ ] Cold start: reopen the chat after process death mid-swarm; the roster
      rebuilds from the snapshot roster replay with grouping intact.
- [ ] Single-`Agent` rows and their navigation are unchanged; unknown frames
      are still ignored.
- [ ] `flock /tmp/kimi-android-gradle.lock ./gradlew :app:testDebugUnitTest
      :app:assembleDebug --max-workers=1 --no-daemon` passes; release APK
      published to the file server per AGENTS.md.

## Out of scope

- Aggregate count, status chip, and progress bar on the card (135).
- `<agent_swarm_result>` parsing, not-started member backfill, and member
  activity lines (136).
- Stop buttons (137), detach to background (138), approval badge (139),
  composer swarm-mode toggle (140).
- Any guarantee beyond arrival order when `swarmIndex` is absent.

## Notes

- Verbatim `subagent.spawned` frame: docs/server-api.md:3257. Roster schema:
  docs/server-api.md:3590-3633. `subagent.suspended`/`subagent.failed` are
  schema-only and never captured live (docs/server-api.md:3311-3315); keep
  the existing `Unknown` roster phase fallback (Snapshot.kt:61) untouched.
- The `<agent_swarm_result>` tool result is intentionally not parsed here;
  136 owns it. Until then a settled swarm's members keep whatever state the
  roster replay produced.
