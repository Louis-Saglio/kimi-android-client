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
  *(Resolved 2026-07-18: no local echo. The doc shows the server commits the
  user message (`user_message_id`) and announces it with `prompt.submitted`,
  which already triggers the 005 history refetch — an optimistic bubble would
  only risk duplicates.)*
- On POST failure: snackbar with Retry; the typed text is never lost.

## Acceptance criteria

- [x] A prompt sent from the phone appears in the same session in the web
  client, and the agent's streamed reply renders on the phone.
  *(Request method/path/body unit-tested in `ChatRepositoryTest` against the
  documented wire format; manually verified by Louis on-device, 2026-07-18.)*
- [x] Send is disabled during an active turn; re-enables on `turn.ended`.
  *(Implemented: the button is gated on blank input, in-flight POST, and
  `isWorking` — set on the POST ack and `turn.started`, cleared on
  `turn.ended`. Manually verified by Louis on-device, 2026-07-18.)*
- [x] With networking off, sending shows the error snackbar and preserves the
  typed text. *(Implemented: POST failure keeps the text in the field and shows
  a snackbar whose Retry re-sends the same text. Manually verified by Louis
  on-device, 2026-07-18.)*
- [x] `./gradlew :app:assembleDebug` succeeds. *(2026-07-18: 20 unit tests
  green — 2 new; Firebase Test Lab robo passed on MediumPhone.arm-34; APK
  published to the file server.)*

## Out of scope

Slash-command/skill autocomplete (`/...`), attachments and images, voice input
(deferred), mid-turn steering (`prompts:steer` — deferred), message editing.

## Notes

- Load `android-skills:compose` (IME/keyboard handling) when implementing.
