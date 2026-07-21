# Assistant message layout parity: full width, no bubble, copy action

- **ID:** 024
- **Created:** 2026-07-21
- **Depends on:** 021

## Context

Product discussion 2026-07-21 (rendering-parity batch). The web client renders
assistant messages as full-width plain text with a small copy button
underneath; only the user's own messages get a bubble. The app instead wraps
assistant replies in a gray `Surface` capped at 320dp
(`ChatScreen.kt` `AssistantBubble`), which wastes a third of an already narrow
screen and will cramp the code blocks from ticket 021. Ranked last of the
original six: the bubbles are ugly-but-functional, and the ticket is most
valuable once markdown styling exists (021) since full width is what code
blocks and lists need.

## Description

- Assistant messages render full-width, without the bubble `Surface`
  background — visually matching the web client (text directly on the chat
  background). Thinking stays above the reply, dimmed and italic as today.
- Add a small copy action beneath each assistant message (icon button,
  per web): copies the message's raw markdown source to the clipboard, with a
  brief visual confirmation.
- User bubbles keep their bubble and right alignment; no change beyond what
  is needed to keep the two message types visually coherent.
- Live streaming bubbles follow the same full-width layout.

## Acceptance criteria

- [ ] Assistant text spans the full content width; no bubble background is
  drawn (on-device visual check against the web client for the same session).
- [ ] The copy action places the exact raw markdown text on the clipboard
  (instrumented test via `ClipboardManager`, or manual check pasting into a
  notes app).
- [ ] Thinking, when present, still renders dimmed above the reply.
- [ ] Streaming bubbles render full-width while deltas append.
- [ ] `./gradlew :app:assembleDebug` succeeds, unit tests green.

## Out of scope

Message timestamps, resend/regenerate actions (the web client has them — raise
a follow-up if wanted), per-code-block copy buttons, long-press context menus,
user-bubble restyling beyond coherence.

## Notes

- Keep the copy icon subtle (small, `onSurfaceVariant`) so it does not add
  visual noise to every message — the web client is a good reference.
- Reusing the same copy affordance for user bubbles is a one-line follow-up;
  leave it out unless it falls out naturally.
