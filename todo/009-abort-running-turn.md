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

- [ ] A long-running turn started from either client stops from the phone within
  ~1–2 s (the web client shows the turn aborted).
- [ ] Stop is hidden/disabled when no turn is running.
- [ ] `./gradlew :app:assembleDebug` succeeds.

## Out of scope

Undo/compact/btw session actions (deferred), killing background tasks, queued
prompt management.

## Notes

