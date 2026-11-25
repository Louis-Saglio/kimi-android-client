# Show a context breakdown from the context summary route

- **ID:** 216
- **Created:** 2026-11-25
- **Depends on:** none

## Context

Server 2.8.0 (nightly drift run, 2026-11-25; kap-server commits `638fd94b`
and `ca71dc29`) added the context-inspection routes, verified live:
`GET /api/v1/sessions/{id}/context/summary` answers
`{session_id, agent_id, captured_at, model?, tokens:{used,max?},
groups:[{role,count,tokens}]}`; `GET .../context/items` and `GET
.../context/items/{item_id}` add the per-item outline and full content. The
groups are `system`, `definition` (one per tool), `user`, `reminder`,
`assistant`, `tool` and `compaction`; a live probe read 7 451 system tokens,
14 419 in 32 tool definitions, and 120/221/514/326 across the message groups.

The app already has the right home for this: the composer's context ring
(`ui/chat/ContextUsageRing.kt`) opens a details dialog (`InfoDialog`) with
`"used / max tokens · pct%"` and, at high usage, a "Compact session" action.
Today the dialog says *how full* the window is but not *what fills it*, which
is exactly the question a user has before deciding to compact or to start a
new session. The ring is fed by the `context` frame (ticket 210), which only
carries the totals; the new REST route supplies the missing breakdown without
touching the socket.

On a phone this fits the existing dialog: one tap from the composer, no new
screen, no polling.

## Description

- Add `ContextSummaryDto` and a `ChatApi.contextSummary(settings, sessionId):
  Result<ContextSummary>` calling
  `GET /api/v1/sessions/{id}/context/summary` (optional `agent_id` omitted —
  the chat is the main agent). Map to a small domain model: `used`, `max?`,
  `model?`, and `groups: List<{role, tokens}>` sorted by tokens descending.
- In `ContextUsageRing`'s details dialog, fetch the summary when the dialog
  opens (`LaunchedEffect(showDetails)`), and render the non-zero groups as
  compact rows under the existing numbers: a humanised role label
  (`System prompt`, `Tool definitions`, `Your messages`, `Reminders`,
  `Assistant`, `Tool results`, `Compaction summary`), the token count and its
  share of `tokens.used`. Keep the existing numbers and the "Compact session"
  button unchanged.
- Render nothing extra while loading or on failure (the dialog keeps its
  current content); no snackbar and no retry button for a diagnostic fetch.
- The fetch is per-open, not polled and not cached across sessions.

## Acceptance criteria

- [ ] Opening the details dialog issues exactly one
      `GET /api/v1/sessions/<id>/context/summary` for the current session and
      renders the non-empty groups sorted by token count, with labels and
      token counts.
- [ ] The percentages/rows sum to at most `tokens.used`; an absent `max`
      leaves the existing numbers line unchanged.
- [ ] A failed request (network error, 40401, non-zero envelope) leaves the
      dialog exactly as it is today; no error surface is added.
- [ ] Closing and reopening the dialog fetches again (fresh `captured_at`),
      and switching sessions never shows the previous session's breakdown.
- [ ] Unit tests cover the DTO mapping and the sorted/labelled group list;
      `:app:testDebugUnitTest` passes.

## Out of scope

- The `/context/items` list and the item-detail drill-down (a later ticket if
  the summary proves useful).
- Live updates of the breakdown — the `context` frame stays the live signal
  for the totals (ticket 210).
- A separate screen, navigation destination or settings entry.
- Sub-agent context (`agent_id` other than `main`).
- Changing the ring's percentage logic or the high-usage threshold.

## Notes

- `docs/server-api.md` → Context inspection and the 2.7.3→2.8.0 drift block.
- Live: the summary on a session with 4 turns read
  `tokens:{used:23053,max:1000000}` and six groups; `groups[].count` is the
  number of messages/tools behind the tokens.
- The dialog lives at `ui/chat/ContextUsageRing.kt` (the `showDetails` block),
  using `ui/chat/components/InfoDialog.kt`; `ChatApi` and `ChatRepository` are
  in `data/chat/`.
