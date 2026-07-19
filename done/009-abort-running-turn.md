# Abort a running turn

- **ID:** 009
- **Created:** 2026-07-17
- **Depends on:** 006

## Context

The safety valve of the core loop: stopping a runaway agent from the phone.
Small by construction — the turn state already exists in the chat ViewModel from
005/006, this adds the `:abort` call and a button.

## Description

- While a turn is active (per the `turn.*` events handled in 005), show a Stop
  action in the chat app bar (icon button).
- Tapping it POSTs the `:abort` endpoint per `docs/server-api.md` — no
  confirmation dialog (abort is cheap and reversible by re-prompting).
- On success the working indicator clears; partial assistant text stays in the
  transcript. On failure: snackbar.

## Acceptance criteria

- [x] A long-running turn started from either client stops from the phone within
  ~1–2 s (the web client shows the turn aborted).
  *(Wire level verified live 2026-07-19 against server 0.27.0: prompt →
  `busy: true` → `POST :abort` acked `{"aborted": true}` →
  `turn.step.interrupted` → `turn.ended` (`"cancelled"`, durationMs 4033) →
  `prompt.aborted`, all inside the 4 s abort window. A turn started from another
  client is covered by `seedWorkingState` + `isSessionRunning`, which this
  ticket also fixed for the 0.27.0 `status` → `busy` rename (unit-tested). The
  ack alone does not clear the indicator — the `turn.ended` frame does —
  unit-tested in `ChatViewModelTest`. On-device tap-to-stop verified by Louis
  2026-07-19.)*
- [x] Stop is hidden/disabled when no turn is running.
  *(Hidden: the app-bar action only composes when `isWorking`; `onStop` also
  no-ops without a running turn — unit-tested: zero requests recorded.)*
- [x] `./gradlew :app:assembleDebug` succeeds. *(2026-07-19: 40 unit tests
  green — 6 new (3 in `ChatRepositoryTest`, 3 in `ChatViewModelTest`); Firebase
  Test Lab robo passed on MediumPhone.arm-34; APK published to the file
  server.)*

## Out of scope

Undo/compact/btw session actions (deferred), killing background tasks, queued
prompt management.

## Notes

- 2026-07-19: the server was upgraded 0.23.3 → 0.27.0 (`backend: v2`) after
  `docs/server-api.md` was written; verified deltas are documented in a new
  top section there. Two affect this app:
  - `GET /sessions/{id}/status` lost its `status` field in favour of
    `busy: bool` — fixed here (`SessionStatusData`), otherwise opening a
    session mid-turn would never show the Stop button for web-started turns.
  - **Not fixed (follow-up candidate):** the session-object `status` is also
    gone (`busy`/`last_turn_reason` now), so the session list's running
    indicator (`SessionsRepository.isRunning`) silently reads false on 0.27.0;
    and `prompt.submitted` was dropped in favour of `context.spliced`, so a
    phone-sent user message only appears at the `turn.ended` refetch. Both
    predate this ticket (server-side rename, not an app bug).
- Stop renders as a plain filled square in the app bar — material-icons-core
  has no stop glyph and pulling in material-icons-extended for one icon was not
  worth it.
