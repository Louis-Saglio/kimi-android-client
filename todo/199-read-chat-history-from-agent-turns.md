# Read chat history from the per-agent turns route

- **ID:** 199
- **Created:** 2026-11-17
- **Depends on:** 198

## Context

Server 2.6.3 (nightly drift run, 2026-11-17; commit `1dd55786`) removed
`GET /api/v1/sessions/{id}/transcript` and `.../transcript/ops` (both are plain
HTTP 404s), and server 2.7.0 (drift run 2026-11-19; commit `b9574579`) also
removed `GET /api/v1/sessions/{id}/messages` and `/messages/{id}` (plain HTTP
404s, verified live). The app's cold start calls all of them:
`ChatApi.transcript` for the main chat and the sub-agent page,
`TranscriptSyncApi.catchUp` for the seq watermark, and `ChatApi.history` for
the chat bubbles. The chat therefore cannot rebuild after a cold open or a
reconnect, and the 2.7.0 removal leaves the bubbles without a source too.

The replacement is `GET /api/v1/sessions/{id}/agents/{agent_id}/turns`:
`{turns, has_more}`, newest page by default, `before_ordinal` and `page_size`
(1–200, default 50) for older pages. A page is at most 1 MiB; the latest page
includes the running turn. There is no `seq` and no catch-up route.

## Description

- Replace `ChatApi.transcript` with `ChatApi.turns(settings, sessionId,
  agentId, beforeOrdinal, pageSize)` and map the response to the conversation
  domain model introduced by ticket 198.
- Replace `TranscriptSyncApi.catchUp` and the `transcriptWatermark` bookkeeping:
  a reconnect drops local turns and takes the fresh `turns` frame, then pages
  older with `before_ordinal`. Delete `TranscriptCatchUp`, `TranscriptData`,
  `TranscriptItemDto`, `TranscriptStepDto`, `TranscriptFrameDto` and the
  `/transcript/ops` call.
- `ChatViewModel`'s cold start reads the newest turns page, the prompt queue
  (`GET .../prompts`) and the session object as today; seed the fold from the
  page.
- `HistoryPager` pages turns with `before_ordinal` (the page's first turn
  ordinal) instead of `before_turn`.
- Map the turns' items to the existing chat rows: `user_message` → the user
  bubble (text + `attachments[]` + `skills[]`), `assistant_message` → reply,
  `reasoning` → thinking row, `tool_call` → tool row (input/output/media),
  `system` → compaction/fork divider, `notification` → the notification row
  ticket 198 defines. Delete `ChatApi.history` and its `MessageDto` mapping —
  there is no `/messages` route to keep.

## Acceptance criteria

- [ ] A cold open issues exactly one `GET .../agents/main/turns?page_size=…`
      and builds the visible rows from it.
- [ ] Loading older history sends `before_ordinal=<first ordinal>` and
      prepends the returned turns without duplicates.
- [ ] No request goes to `/transcript`, `/transcript/ops`, `/messages` or
      `/messages/{id}` (MockWebServer test asserts the recorded paths).
- [ ] A reconnect re-subscribes and replaces the local turns with the fresh
      `turns` frame; no `seq`/watermark code remains.
- [ ] An unknown agent (`40401`) shows the existing load-failure state.

## Out of scope

- Live frame application (ticket 198).
- The sub-agent page's per-agent history (ticket 202).
- Compaction UI (ticket 201).

## Notes

- `docs/server-api.md` → Conversation → Read turns.
