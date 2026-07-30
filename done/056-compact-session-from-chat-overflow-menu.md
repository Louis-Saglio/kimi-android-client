# Compact session from a chat overflow menu

- **ID:** 056
- **Created:** 2026-07-30
- **Depends on:** none

## Context

Louis wants the app's equivalent of the TUI's `/compact`: the server
summarises a session's history to free up context window. The wire contract
is already pinned down — ticket 054 verified `POST /api/v1/sessions/{id}:compact`
live on 0.30.0 (`docs/server-api.md`, § Compact). The web client triggers it
from a `/compact` chip that appears in the composer at ≥80% context usage; the
app has nothing (041 and 053 both deferred it deliberately).

UX decision (2026-07-30, with Louis): add a ⋮ **overflow menu in the chat top
app bar**, with "Compact session" as its first item, designed to host future
session-level actions (rename, delete, export…). Rationale:

- The top bar has been empty since 053 (back arrow + title/cwd only), and
  053's own reasoning supports this: compact is *session management*, not a
  turn control, so it does not belong in the composer next to Send/Stop/Steer.
- A top-right overflow menu is the canonical Material 3 placement for
  infrequent actions; compact is per-context-window, not per-turn.
- Alternatives rejected: the web's composer chip as the primary home
  (ephemeral, conditional chrome — Louis wants a permanent, extensible menu);
  menu action without a timeline trace (hollow — the divider row is where the
  feedback lives); rendering the persisted summary as a user bubble (the web
  hides it — it is internal content, see below).
- Coherence: 047's planned tasks icon + running-count badge stays a direct
  top-bar icon (glanceable live status); it coexists with the menu. Do not
  move the tasks entry into the menu later.

Server facts that shape the UX (all verified live, § Compact):

- Fire-and-forget: instant `{"code":0,"data":{}}` ACK, then 8–46 s of
  background summarization. WS sequence: `compaction.started` →
  `work_changed busy=true` (**no turn events** — compaction is not a turn) →
  `context.spliced` → `compaction.completed` (`result.summary`,
  `compactedCount`, `tokensBefore`, `tokensAfter`, `keptUserMessageCount`) →
  `work_changed busy=false`.
- A second compact while one runs is a **silent no-op** — the client must
  prevent it; the server will not error.
- 40910 on: empty session ("No messages to compact in current history."),
  turn active, approval pending. 40401 unknown session.
- The `context.spliced` frame injects the summary as an **id-less user
  message whose text carries a model-facing prefix** ("The conversation so far
  has been compacted… treat it as notes, not proof"). Kept user messages are
  re-spliced with their original ids (our existing id-dedup covers them).
- History is never deleted: compaction appends one `role=user` message with
  `metadata.origin.kind: "compaction_summary"` and the bare summary (no
  prefix). Pagination and the role filter are unchanged.

Web parity (embedded-bundle recon, 2026-07-30): the web client **hides**
persisted `compaction_summary` messages via its origin-kind visibility filter
and renders compaction as a divider row — "Compacting context…" while running,
then "Context compacted ({before} → {after} tokens)" with a "View summary"
affordance opening a "Compaction summary" dialog. We match that.

Menu-item states (confirmed with Louis): "Compact session" is **disabled while
a turn runs (`isWorking`) and while a compaction is in progress**; otherwise
enabled — the empty-session 40910 surfaces as a snackbar (it has a good server
message).

Single-ticket delivery (option A of the discussion) with the size budget
explicitly relaxed by Louis (041/053 precedent): the divider row is part of
the feature's feedback, splitting it off leaves the action hollow. The ≥80%
discovery affordance is ticket 057.

## Description

1. **Data layer** (`app/.../data/chat/`):
   - `ChatApi.compact(sessionId)`: `POST /api/v1/sessions/{id}:compact` with a
     `{}` body through `ApiClient` (never a hand-rolled request). Make sure
     the 40910/40401 envelope `msg` survives into the mapped `DataError` so
     the snackbar can show the server's own sentence (reuse the existing
     server-message path if there is one; extend it if not).
   - `ChatRepository.compact(sessionId)`: thin facade, `Result`/`DataError`.
   - `SessionSocket`: parse `compaction.started` (`trigger`, optional
     `instruction` — consumed only for completeness) and `compaction.completed`
     (`result.summary`, `compactedCount`, `tokensBefore`, `tokensAfter`,
     `keptUserMessageCount`) into new `SessionEvent`s. Also decode
     `compaction.failed` / `compaction.cancelled` / `compaction.blocked`
     defensively (schemas exist in the protocol bundle; shapes unverified on
     this server — a permissive DTO with all-optional fields): any of them
     ends the compacting state with an error snackbar, so a mid-run server
     failure can never leave the UI stuck in "Compacting…".
   - `MessageDto`: add the optional `metadata.origin.kind` (snake_case DTO;
     the DTO is shared by the history and `context.spliced` decode paths).
     `ChatMessageMapper` carries `originKind` onto `ChatMessage`; messages
     with kind `compaction_summary` never flow into the user-bubble path.
2. **Timeline / ViewModel** (`ui/chat/`):
   - New `ChatItem.CompactionDivider`: pending | done with `tokensBefore`/
     `tokensAfter` | done without token numbers (history-sourced); carries the
     summary text when available (live: from `compaction.completed`; history:
     from the persisted message).
   - Live flow: `onCompact()` is a no-op guard while `isWorking` or
     `isCompacting`; on POST success set `isCompacting = true` optimistically.
     On `compaction.started` insert the pending divider at the tail. On
     `compaction.completed` flip the divider to done with the token delta,
     stash the summary, clear `isCompacting`, and emit a snackbar effect —
     "Context compacted: 26.5k → 386 tokens" (reuse
     `ui/common/Formatters.formatContextSize`). On failed/cancelled/blocked:
     remove the pending divider, clear `isCompacting`, error snackbar.
   - `context.spliced` during compaction: drop the `compaction_summary`
     message outright (origin-kind check in the splice path — its text is the
     model-facing prefix, not user content). Kept user messages dedup by id
     via the existing logic; verify the id-less steer-echo dedup path cannot
     misfire on the summary.
   - History: a `compaction_summary` user message maps to a done divider
     (token numbers are not persisted — label without them) holding the bare
     summary; it never renders as a user bubble. This must hold on cold open,
     on the 048 resync refetch, and on 043 pagination prepend.
   - `UiState` gains `isCompacting: Boolean`.
3. **UI** (`ui/chat/ChatScreen.kt`, `ui/chat/components/`):
   - `TopAppBar` actions: `Icons.Filled.MoreVert` `IconButton` (48dp,
     `contentDescription = "Session options"`) opening a `DropdownMenu` whose
     first item is "Compact session", `enabled = !isWorking && !isCompacting`.
     Structure the menu so adding items later is trivial.
   - Divider row in `MessageList`: slim centered row — pending: small spinner
     + "Compacting context…"; done: "Context compacted" plus, when known,
     " (26.5k → 386 tokens)"; tapping a divider that has a summary opens a
     dialog titled "Compaction summary" with the summary text (web-parity
     strings).
   - Update `ChatContentPreview` with pending/done divider states.
4. **Tests**: socket parsing of the compaction frames (including the
   permissive failed/cancelled decode); mapper origin-kind handling (summary
   never becomes a user bubble, on history AND splice paths); timeline divider
   insert/update/dedup and history+live interplay; ViewModel optimistic
   state, no-op guards, snackbar effects; `ChatApi.compact` request shape.

## Acceptance criteria

- [x] The chat top bar shows a ⋮ button opening a menu with "Compact session";
      the item is enabled only when no turn is running and no compaction is in
      progress (state matrix verified in unit tests and/or preview).
      *(`ChatViewModelTest.onCompact is a no-op while a turn runs or a
      compaction is in progress`; menu in `ChatScreen.kt`, preview updated.)*
- [x] Tapping it issues `POST /api/v1/sessions/{id}:compact` with body `{}`
      through `ApiClient` (MockWebServer test).
      *(`ChatRepositoryTest.compact posts an empty body to the compact
      endpoint`.)*
- [x] On-device against the VPS server: compact a real session — a
      "Compacting context…" divider appears, the composer does NOT switch to
      Stop/Steer (compaction emits no turn events), and on completion the
      divider shows the token delta, a snackbar confirms, and the context ring
      drops (existing `agent.status.updated` path — verify live).
      *(`ChatViewModelTest.compaction frames drive the divider, the state and
      the snackbars` runs the verbatim 0.30.0 started → splice → completed
      sequence end-to-end; the ring drop rides the untouched 041 path.
      On-device confirmation left to Louis via the published APK, as in 053.)*
- [x] The spliced summary never appears as a user bubble; after killing and
      reopening the app (and after paging up through 043 pagination), the
      persisted `compaction_summary` message renders only as a divider, whose
      tap opens the "Compaction summary" dialog.
      *(`ChatTimelineTest` splice-drop / history-divider / prepend-dedup tests,
      `ChatViewModelTest.a compaction_summary history message renders as a
      divider on cold open`; dialog in `CompactionDividerRow.kt`.)*
- [x] Error paths: compacting an empty session and compacting during a
      web-started turn each surface the server's 40910 message as a snackbar;
      no second compact can be triggered while one runs (item disabled).
      *(`ChatViewModelTest.a compact POST failure surfaces the server's 40910
      message and leaves the state`; both 40910 variants share the one
      `toAckResult` → `toUserMessage` path; the `isCompacting` guard and the
      disabled item block a second compact.)*
- [x] `./gradlew :app:testDebugUnitTest` passes, including the new socket /
      mapper / timeline / ViewModel tests. *(GREEN, 280+ tests.)*
- [x] Release APK built and copied to the file server per AGENTS.md.
      *(Published 2026-07-30.)*

## Out of scope

- The `instruction` field UI (the endpoint accepts `{"instruction": "..."}`;
  no UI for it in v1 — send `{}`).
- The ≥80% discovery affordance ("Compact session" in the context-ring
  dialog) — ticket 057.
- Auto-compaction (`auto_compact`) surfacing beyond the generic
  started/completed handling.
- Token numbers on history-sourced dividers (not persisted server-side).
- Additional menu items (rename/delete/export) — the menu is built to grow,
  but this ticket ships compact only.
- Cold-open-during-compaction seeding: `GET .../status` reports `busy: true`
  mid-compaction with no turn events, which the 005-era seeding
  (`ChatViewModel.kt:998`) may read as `isWorking = true` with no
  `turn.ended` to clear it. Pre-existing edge, not made worse by this ticket;
  file a follow-up if it proves visible in practice.
- Any change to 047's planned tasks icon placement.

## Notes

- Wire reference: `docs/server-api.md` § Compact (verbatim captures, error
  envelopes, splice shape). Keep it accurate if parsing reveals drift.
- Web bundle recon (2026-07-30, embedded in the `kimi` binary): visibility
  filter shows only `origin.kind` undefined/`user` (+ user-slash skill
  activations); i18n strings "Compacting context…", "Context compacted",
  " ({before} → {after} tokens)", "View summary", "Compaction summary".
- `MessageDto` currently has no `metadata` field (`ChatDtos.kt:20`) and the
  splice DTO explicitly ignores origin (`ChatDtos.kt:189`) — both paths gain
  the kind decode together.
- One-shot snackbars go through the effect channel (AGENTS.md); dropped
  frames get a `Log.w`, never silent.
- M3: use `DropdownMenu`/`DropdownMenuItem` with the `ExposedDropdownMenuBox`
  anchor overload or `MenuAnchor` — match whatever the codebase already uses
  for menus, if any.
