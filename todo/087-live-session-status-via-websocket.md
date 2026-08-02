# Live session status in the list via WebSocket

- **ID:** 087
- **Created:** 2026-08-02
- **Depends on:** 086

## Context

Product discussion with Louis (2026-08-02), follow-up to 086. Even with 086's
silent refresh on return, the list is blind while Louis is *looking* at it: a
session that starts waiting on an approval/question, finishes a turn, or gets
auto-titled only shows it after a manual pull-to-refresh. Louis runs several
agent sessions concurrently; the list should be a live "which session needs
me" dashboard (the follow-up flagged in ticket 016's notes).

The wire supports it natively (`docs/server-api.md`, WebSocket):

- `event.session.work_changed` carries `busy`, `pending_interaction`
  (`"none"` / `"approval"` / `"question"`), `last_turn_reason` — exactly the
  busy dot (011) and badge (016) state.
- `session.meta.updated` carries title patches (e.g. server auto-titling from
  the first prompt).
- The subscribe frame takes a **list** of `session_ids` — one connection
  follows every loaded session.

Decisions (Louis, 2026-08-02): drive busy + needs-me badge + title; the badge
fires on `pending_interaction != "none"` (approvals AND questions both read
as "needs me" — no new badge design); **no live reorder** — rows light up in
place, ordering stays owned by the silent refresh.

Alternatives considered and rejected: (a) polling REST on a timer while the
list is visible — dumber than one WS, the infrastructure already exists;
(b) relying on the server's unsolicited broadcast of unsubscribed sessions
(0.31.0 caveat) — undocumented, must be filtered, not built upon; (c) a
process-wide shared events connection — overkill, the screen-scoped gate
already bounds the lifetime correctly; (d) patching `SessionSocket` for
multi-session use — its frame handling is chat-specific (foreign-session
filtering opposite of what the list needs) and carries the 048/085 machinery;
a separate socket is the cheaper risk.

## Description

**`SessionListSocket`** (`data/sessions`, new `@Singleton`): connection
management (reconnect loop with the same 1 s → 30 s exponential backoff shape
as `SessionSocket`), frame parsing for exactly two event types plus the ack.
Modest duplication of the reconnect machinery is accepted (see rejected
alternative d).

- **Lifecycle**: collected only while the sessions screen is visible —
  `ON_START` starts, `ON_STOP` cancels (the screen's existing
  `LifecycleEventEffect` gate pattern). Chat open or app backgrounded → no
  connection.
- **Subscribe**: on connect, one subscribe frame with all currently loaded
  session ids, **no cursors** (the REST load is the baseline; events only
  patch). `onEndReached` pages loaded while connected send a follow-up
  subscribe for the new ids.
- **Foreign frames**: the 0.31.0 unsolicited-broadcast caveat applies — drop
  any event whose envelope `session_id` is not in the subscribed set.
- **Reconnect healing**: after a drop + successful reconnect, resubscribe all
  ids and trigger 086's silent REST refresh once, to heal the missed window.
- Parse failures drop the frame with a `Log.w` (house rule) — never kill the
  connection.

**Live row patches** (`SessionListViewModel`): consume the socket flow while
the screen is started; patch the affected row in place (unknown session id →
ignore — the next refresh reconciles):

- `event.session.work_changed` → `isRunning = busy`,
  `isAwaitingInteraction = pending_interaction != "none"`.
- `session.meta.updated` → title patch.
- Never reorder: the list's ordering changes only via the silent/initial
  refresh.

**Badge-rule consistency rename**: the REST mapping and the WS patch must use
one rule, so `pending_interaction != "none"` replaces `== "approval"`
everywhere — rename `Session.isAwaitingApproval` to `isAwaitingInteraction`
across `SessionsRepository.toDomain()`, the domain model, `SessionRow`
(content description "Approval needed" → "Needs attention"), and their tests
(mechanical sweep; the badge visuals stay as-is).

## Acceptance criteria

- [ ] While the list is visible, a `work_changed` (`busy=true`) for a listed
  session lights its running dot without any refresh; `busy=false` clears it
  (ViewModel test with a fake socket flow).
- [ ] A `work_changed` with `pending_interaction: "question"` lights the
  badge, and `"approval"` still does; `"none"` clears it (ViewModel test).
- [ ] REST-loaded rows follow the same rule: an envelope with
  `"pending_interaction": "question"` maps to `isAwaitingInteraction = true`
  (`SessionsRepositoryTest`); `SessionRowTest` semantics updated for the
  renamed flag/content description.
- [ ] A `session.meta.updated` patch updates the row's title live (ViewModel
  test).
- [ ] Events for sessions not in the list are ignored, and a patched row never
  changes position in the list (ViewModel test).
- [ ] The socket subscribes with exactly the loaded ids in one frame (asserted
  on the client frames of the existing socket test harness), sends a follow-up
  subscribe when a new page loads, and after a reconnect triggers the silent
  refresh path once.
- [ ] The connection closes on ON_STOP and never runs while the chat screen is
  open (lifecycle test or harness assertion).
- [ ] `./gradlew :app:assembleDebug` succeeds with all unit tests green (VPS
  rules: `flock /tmp/kimi-android-gradle.lock`, `--max-workers=1`,
  `--no-daemon` when parallel).

## Out of scope

- Live removal of server-archived sessions (the subscribe ack's `not_found`
  could drop them at connect time — noted, deferred).
- Live reordering of any kind (rejected by Louis).
- A distinct question-pending badge design (questions reuse the "needs me"
  badge).
- Any background lifetime: foreground service, push, updates while the app is
  backgrounded.
- Chat-path changes: `SessionSocket`, the 048 resume path, and the 085
  snapshot machinery stay untouched.

## Notes

- Key files: `data/sessions/SessionListSocket.kt` (new),
  `ui/sessions/SessionListViewModel.kt` (socket collection + row patching),
  `ui/sessions/SessionsScreen.kt` (gate wiring — the existing
  `LifecycleEventEffect(ON_START)` pattern extends with ON_STOP),
  `data/sessions/SessionsRepository.kt` + `Session.kt` (rename),
  `ui/sessions/SessionsScreen.kt` `SessionRow` (content description).
- Protocol references: subscribe frame + ack (`docs/server-api.md` ~line
  1579), unsolicited-frames caveat (~1616), `work_changed` payload (~1752),
  `session.meta.updated` (~1892). The nightly drift check keeps these current.
- `SessionSocket` is the reference implementation for the reconnect loop and
  the OkHttp WS listener → channel pattern; copy the shape, not the chat
  frame parsing.
- Test harnesses: `SessionListViewModelTest` (MockWebServer) for the ViewModel
  cases — the socket should be an injected interface so the fake-flow tests
  stay trivial; the socket's own frame tests can reuse the websocket test
  approach from `SessionSocket`'s tests.
