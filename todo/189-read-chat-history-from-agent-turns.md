# Read chat history from the per-agent turns route

- **ID:** 189
- **Created:** 2026-11-12
- **Depends on:** 188

## Context

Server 2.6.0 (nightly drift run, 2026-11-12; commit `1dd55786`) removed
`GET /api/v1/sessions/{id}/transcript` and `.../transcript/ops` (both are plain
HTTP 404s). The app's cold start calls both: `ChatApi.transcript` for the main
chat and the sub-agent page, and `TranscriptSyncApi.catchUp` for the seq
watermark. The chat therefore cannot rebuild after a cold open or a
reconnect.

The replacement is `GET /api/v1/sessions/{id}/agents/{agent_id}/turns`:
`{turns, has_more}`, newest page by default, `before_ordinal` and `page_size`
(1–200, default 50) for older pages. A page is at most 1 MiB; the latest page
includes the running turn. There is no `seq` and no catch-up route.

## Description

- Replace `ChatApi.transcript` with `ChatApi.turns(settings, sessionId,
  agentId, beforeOrdinal, pageSize)` and map the response to the conversation
  domain model introduced by ticket 188.
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
- Keep `GET .../messages` untouched: it is still the flattened main-agent
  history used where the app needs message rows.

## Acceptance criteria

- [ ] A cold open issues exactly one `GET .../agents/main/turns?page_size=…`
      and builds the visible rows from it.
- [ ] Loading older history sends `before_ordinal=<first ordinal>` and
      prepends the returned turns without duplicates.
- [ ] No request goes to `/transcript` or `/transcript/ops` (MockWebServer
      test asserts the recorded paths).
- [ ] A reconnect re-subscribes and replaces the local turns with the fresh
      `turns` frame; no `seq`/watermark code remains.
- [ ] An unknown agent (`40401`) shows the existing load-failure state.

## Out of scope

- Live frame application (ticket 188).
- The sub-agent page's per-agent history (ticket 192).
- Compaction UI (ticket 191).

## Notes

- `docs/server-api.md` → Conversation → Read turns.
