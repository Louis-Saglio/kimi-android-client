# Send prompts from chat

- **ID:** 006
- **Created:** 2026-07-17
- **Depends on:** 005

## Context

Second half of the daily-driver loop: talking to the agent from the phone. The
WebSocket stream from 005 already renders responses; this ticket adds the input
side via the prompts endpoint documented in 001.

## Description

- Input row docked above the keyboard on the chat screen:
  - multiline `TextField`, growing to ~5 lines then scrolling,
  - IME action = send,
  - send button disabled when the field is blank or while a turn is running
    (driven by the `turn.*` events already handled in 005).
- POST the prompt per `docs/server-api.md`.
- Rendering of the exchange comes from the existing WS stream; if 001 found the
  protocol expects local echo, add an optimistic user bubble marked pending
  until the stream confirms — follow the doc, don't guess.
- On POST failure: snackbar with Retry; the typed text is never lost.

## Acceptance criteria

- [ ] A prompt sent from the phone appears in the same session in the web
  client, and the agent's streamed reply renders on the phone.
- [ ] Send is disabled during an active turn; re-enables on `turn.ended`.
- [ ] With networking off, sending shows the error snackbar and preserves the
  typed text.
- [ ] `./gradlew :app:assembleDebug` succeeds.

## Out of scope

Slash-command/skill autocomplete (`/...`), attachments and images, voice input
(deferred), mid-turn steering (`prompts:steer` — deferred), message editing.

## Notes

- Load `android-skills:compose` (IME/keyboard handling) when implementing.
