# Chat screen: history and live streaming

- **ID:** 005
- **Created:** 2026-07-17
- **Depends on:** 004

## Context

The heart of the clone. The web client streams everything over a single
WebSocket (`tool.call.delta`, `message.updated`, `turn.*` …). v1 renders plain
text only — the web client's KaTeX/Mermaid/syntax highlighting is explicitly
deferred to keep this ticket inside the size budget.

## Description

Fill the stub `chat/{sessionId}` screen:

- Load message history via REST (per `docs/server-api.md`) and render
  user/assistant messages as chat bubbles in a `LazyColumn`, keyed by message id.
- Open the session WebSocket and apply events live:
  - streaming text deltas append to the in-flight assistant message,
  - tool-call start/delta/result render as compact activity rows (tool name +
    status),
  - `turn.started`/`turn.ended` toggle an "agent is working" indicator in the
    app bar.
- Auto-scroll to bottom on new content only when already at the bottom.
- Reconnect with exponential backoff on socket drop; on reconnect, refetch
  history to avoid gaps or duplicates (dedupe by message id).
- MVVM per house pattern; socket owned by a repository-level class, not the
  composable.

## Acceptance criteria

- [ ] Opening a session shows the same history the web client shows for that
  session (manual side-by-side check).
- [ ] With a prompt sent from the web UI, the phone screen updates live:
  streaming text, tool activity rows, working indicator clearing on
  `turn.ended`.
- [ ] Toggling airplane mode mid-stream → visible reconnect behavior; after
  recovery the conversation is complete with no duplicated messages.
- [ ] Diff stays under ~500 lines; cut styling before cutting streaming.

## Out of scope

Sending prompts (006), permission UI (007), markdown/code-highlight/KaTeX/Mermaid
rendering (deferred), images or file attachments, holding sockets for more than
the open session, chat pagination/infinite scroll.

## Notes

- WS wire format comes from `docs/server-api.md` (ticket 001) — do not
  re-reverse-engineer the bundle.
- Load `android-skills:kotlin-flows` and `android-skills:compose` when
  implementing.
