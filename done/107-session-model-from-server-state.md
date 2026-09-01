# Session model/effort read from server state

- **ID:** 107
- **Created:** 2026-09-01
- **Depends on:** none

## Context

Louis reported (2026-09-01): opening a session shows the server default in the
composer model chip instead of the model the session actually runs on.

Root cause: the app treats its local DataStore (`SessionPrefsStore`) as the
only source of truth for a session's model/effort. That store was the right
design when ticket 031 was written (server 0.29.0) — the server then ignored
model at session creation, so cross-client sync was explicitly declared
"impossible, not fixable app-side" (031, Out of scope). This is no longer
true:

- `GET /api/v1/sessions/{id}` → `agent_config.model` is the real bound model
  since 0.39.1 (verified live; `""` on cold sessions).
- `GET /api/v1/sessions/{id}/status` → `model` (omitted when unbound) and
  `thinking_level` (`""` on fresh sessions since 0.34.0; `"on"`/`"off"` on
  effort-less models; the effort string on K3). The app already polls this
  endpoint on cold start for the context ring and discards both fields.
- WS `agent.status.updated` (model/usage variant) carries `model` and
  `thinkingEffort` live on every turn; the app decodes only the token counts.

So any session whose model was bound outside this app's DataStore — web
client, TUI, or this app before a reinstall — displays the default. The fix
makes the server the source of truth: server-reported values are written
through into the per-session store, which stays the single read path for both
display and prompt sending. Alternatives considered and rejected: keeping the
server value in ViewModel state with a separate precedence chain (two sources
of truth; worse, the repository-level send path can't see ViewModel state, so
a stale local choice would rebind the session back on the next prompt,
actively undoing a web-client switch).

## Description

1. **Spike first** (pattern of tickets 013/017/032; keep throwaway sessions
   minimal, there is no session-delete endpoint): run a session on a
   non-default model, restart nothing but let it go cold (or pick an existing
   cold session that ran turns), then read `GET /api/v1/sessions/{id}/status`
   and `GET /api/v1/sessions/{id}` — does a cold, previously-bound session
   report its persisted `model`/`thinking_level`, or only warm ones? Also
   check what opening the chat screen (snapshot + WS subscribe) changes.
   Record findings in the ticket and `docs/server-api.md`.
2. **Decode the fields.** `SessionStatusData` gains `model: String?` and
   `thinking_level: String?`; `SessionStatus` exposes them.
   `AgentStatusUpdatedPayload` gains `model: String?` and
   `thinkingEffort: String?`; when either is non-null the socket emits a new
   `SessionEvent` (e.g. `SessionModelUpdated(model, thinkingEffort)`). The
   phase variant of `agent.status.updated` carries them null — same
   don't-clobber rule as the token counts.
3. **Write-through in ChatViewModel.** When `/status` seeding
   (`seedSessionStatus`) or the new event reports a **non-blank** model, call
   `chatRepository.setSessionModel(sessionId, model)` and update
   `sessionModel` in the UI state; same for effort via `setSessionEffort`.
   Blank/absent values never clobber the stored choice (fresh sessions report
   `""`/omitted until their first prompt binds them). Effort values outside
   the current model's `support_efforts` (`"on"`/`"off"`) are stored raw —
   the existing send-path validation (`promptThinking`) and the picker's
   `selectedEffort` filter already ignore them.
4. **No change to the picker or the send path.** The user's pick still writes
   the store directly and applies to the next prompt; the following
   status/WS frame confirms the rebind with the same value. Last-write-wins
   between a user pick and a late frame is accepted (the frame echoes the
   session's real state).

## Acceptance criteria

- [x] Spike findings recorded: what `/status` and session-get report for a
      cold, previously-bound session; `docs/server-api.md` updated.
- [x] Unit test: a `/status` response with `model`/`thinking_level` updates
      the ViewModel's `sessionModel`/`sessionEffort` and persists them.
- [x] Unit test: an `agent.status.updated` frame with `model`/`thinkingEffort`
      does the same; a phase-variant frame (nulls) changes nothing.
- [x] Unit test: blank/omitted server values leave the stored choice and the
      UI state untouched.
- [x] Unit test: after a write-through, `POST /prompts` carries the
      server-reported model (send path reads the store, unchanged).
- [x] `./gradlew testDebugUnitTest` passes.
- [ ] Live check: bind a session to a non-default model from the web client,
      open it in the app — the chip shows that model without any local pick.
      (Server half verified in the spike; data-layer half verified live
      2026-09-01 by `LiveModelSyncTest` — env-gated, drives the production
      HTTP/parse path against the real server, including pre-restart
      bindings. Device half: Louis's first try hit the placeholder-gate bug
      (see Follow-up fix); awaiting his retest on the APK of 2026-09-01
      04:53 UTC.)
- [x] APK built and copied to the file server per AGENTS.md.

## Follow-up fix (2026-09-01, same day)

Louis's on-device test showed the chip still falling back to the server default
on web-bound sessions. Root cause: the `/status` seed that carries the
write-through was gated on the snapshot's usage being the all-zero placeholder
(`applySnapshot`), a condition written in the 0.32.0 era — since 0.38.0 the
bundle carries the REAL fill, so the gate skipped the seed on every session
that has run turns. App-created sessions looked fine only because their local
pick was already in the store. Fix: the cold start now always re-reads
/status; the ring's seed guard is unchanged. Regression test: a
non-placeholder snapshot still seeds the status for the model sync (replaces
the old "applies without a status re-read" pin).

## Out of scope

- Picker UI changes (provider grouping, dismiss behavior, effort on the
  chip) — ticket 108.
- Editing the server-wide default model/effort — ticket 109.
- Reacting to `event.config.changed` / `event.model_catalog.changed` —
  ticket 110.
- Reading the snapshot's `session.agent_config.model` — only if the spike
  shows `/status` comes back empty for sessions the chat screen has just
  warmed; note the outcome in the spike findings.

## Notes

- `docs/server-api.md`: session get (`agent_config.model`, 0.39.1), `/status`
  (`model`/`thinking_level`), `agent.status.updated` (0.29.0 model/usage
  variant with `thinkingEffort`).
- Relevant code: `ChatViewModel.loadModels`/`seedSessionStatus`,
  `data/chat/SessionPrefsStore.kt`, `ChatRepository.getSessionModel` /
  `setSessionModel` / `promptModel` / `promptThinking`, `SessionSocket`'s
  `agent.status.updated` branch.

## Spike findings (2026-09-01, verified live on 0.39.1)

- A fresh, never-prompted session: `/status` **omits `model` entirely** and
  reports `thinking_level: ""` — blank/absent values must never clobber the
  stored choice.
- A prompt carrying `"model"`/`"thinking"` binds the session: afterwards
  `/status` reports both (`kimi-code/k3-256k`/`"low"` observed) and
  list/get's `agent_config.model` is set.
- **Cold sessions are covered by `/status` alone.** A session untouched since
  server start (list/get report `agent_config.model: ""`) answers its first
  `/status` call with the real persisted `model`/`thinking_level` — the call
  warms the session, and a subsequent get then also reports the model. The
  app's existing cold-start `/status` poll is therefore a reliable seed
  source; the snapshot's `session.agent_config.model` is not needed (it too
  reports the model once warm, but `/status` suffices and is already wired).
- Restart could not be tested mid-session (the agent session itself rides on
  this server), but the persisted binding was proven across the cold/warm
  boundary as above: the server had been up since 2026-08-28 and pre-restart
  sessions served their binding on first `/status`.
