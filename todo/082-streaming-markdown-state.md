# Adopt StreamingMarkdownState in MarkdownBody (incremental streaming parse)

- **ID:** 082
- **Created:** 2026-07-31
- **Depends on:** 077

## Context

Renderer 0.42.0 (landed via 077) adds `StreamingMarkdownState`: append-only
incremental parsing — a streamed delta extends the already-parsed document
instead of re-parsing the whole snapshot. Today every new assistant/thinking
snapshot costs one full re-parse in `MarkdownBody`, which is why the 100 ms
`DeltaCoalescer` window exists (tickets 065/073). Ticket 073's evaluation
said: revisit the window on this ladder rung.

Goal: cheaper streaming in the app's core UX (markdown chat rows), and an
honest re-evaluation of whether the coalescing window can be widened or
dropped for assistant-text rows. The window is one injectable constructor
argument (`streamCoalesceWindowMillis`), so experiments are cheap.

Guardrails from 073 that must survive any refactor:

- `immediate = true` first-frame synchronous parse — `MessageList`'s initial
  `scrollToBottom` measures real row heights; breaking this reintroduces the
  073 bottom-pin regression (6 instrumented failures).
- `retainState` semantics — no blank flash while a re-parse (or incremental
  re-layout) is in flight.
- `eolAsNewLine = true` annotator config.
- Sub-agent pages reuse `MessageList`/`MarkdownBody` — one implementation,
  both screens benefit.

## Description

1. Read the 0.42.0/0.43.0 `StreamingMarkdownState` API (PR #575, #564, and
  0.43.0's synchronous `parseMarkdown` #591) and map it onto
  `MarkdownBody.kt`'s current `rememberMarkdownState(content, retainState =
  true, immediate = true)` usage.
2. Prototype the switch for streaming assistant/thinking rows. If the API
  only pays off when fed append-only deltas, evaluate per-row state (keyed by
  message id) fed from the timeline's coalesced emissions — the
  `DeltaCoalescer` already produces exactly that stream.
3. Benchmark informally (frame drops by eye + `MarkdownBody` recomposition
  counts on a long markdown-heavy session; the VPS can't measure phone
  frames — Louis's phone can).
4. Decide, with numbers in this ticket: keep the 100 ms window, widen it, or
  drop it for the rows that moved to incremental parsing. Update `AGENTS.md`
  (streaming/DeltaCoalescer paragraphs) either way.

## Acceptance criteria

- [ ] Streaming rows use incremental parsing, **or** a documented no-go with
  the blocker recorded (e.g. API doesn't fit per-row retention) and
  `AGENTS.md` updated.
- [ ] `./gradlew testDebugUnitTest` green; androidTest green on Firebase
  Test Lab (`ChatAutoScrollTest`, `ToolRowExpandScrollTest` first — they
  guard the first-frame contract); robo green.
- [ ] Coalescing-window decision recorded with measurements; code + tests +
  `AGENTS.md` match the decision.
- [ ] Release APK published to the file server per `AGENTS.md`.

## Out of scope

- Replacing the renderer library (073's standing rule: only if proven a dead
  end, as a new ticket with Louis).
- Touching `DeltaCoalescer`'s event-flush invariants — at most its window
  value and which streams route through it.
- Thinking-block/tool-row rendering changes beyond what the state switch
  requires.

## Notes

- 065 (DeltaCoalescer), 073 (renderer ladder + first-frame rationale) are
  required reading before starting.
