# Render `session_media` image sources in chat

- **ID:** 104
- **Created:** 2026-08-18
- **Depends on:** none

## Context

Server 0.37.2 (upstream `d833a1a8`, "engine-native image references via
kimi-file:// media resolver") changed how uploaded images project on the wire.
Both `GET /api/v1/sessions/{id}/messages` and the `POST .../prompts` ACK echo
now return image content as
`{"type":"image","source":{"kind":"session_media","file_id":"f_…"}}` instead of
the 0.36.1 shapes (ACK base64, history `url:data:…`). The `file` kind survives
only as the transient upload form accepted on prompt submission, so *sending*
still works — but `ChatMessageMapper.toDomain(MediaSourceDto)` only knows
`base64`/`url`/`file`, so every `session_media` image part is dropped. Result:
a sent image (and its live `context.spliced` echo) renders as nothing, and
disappears again on cold open / history reload. Live-verified 2026-08-18: a 1×1
png uploaded then prompted came back `kind:"session_media"` in both ACK and
history. The canonical bytes are served by a new endpoint
`GET /api/v1/sessions/{id}/media/{file_id}` (for uploaded files the id is the
same `f_…` upload id, so the legacy `GET /api/v1/files/{id}` still happens to
work — but only for uploads, not for session-owned media that never passed the
daemon upload store).

## Description

Render `session_media` image sources end-to-end, root-cause:

1. In `ChatMessageMapper.toDomain(MediaSourceDto)` add a `"session_media"`
   branch that produces a `ChatMessage.Part.Image` carrying the `file_id`.
   Give `Part.Image` an explicit source discriminator (e.g. `sourceKind` /
   `isSessionMedia`) instead of overloading `fileId` — a `session_media` id is
   session-scoped, not a daemon-file id.
2. Add `ChatApi.downloadSessionMedia(settings, sessionId, fileId)` hitting
   `GET /api/v1/sessions/{sessionId}/media/{fileId}` (mirrors `downloadFile`).
3. Route the image fetch in `ChatAttachments.imageBytes` (and the full-screen
   viewer path) through the session-media endpoint for `session_media` sources;
   thread the session id from the caller (it already has it).

## Acceptance criteria

- [ ] `ChatMessageMapper` maps a `{"kind":"session_media","file_id":"f_…"}`
      image source to a non-null `Part.Image`; a unit test covers it.
- [ ] The image fetch for a `session_media` source calls
      `GET /api/v1/sessions/{id}/media/{file_id}` (assert on the request URL in
      the existing MockWebServer test style), not `/api/v1/files/{id}`.
- [ ] Sending an image renders it in the timeline (live echo) and after a
      history reload / cold open.
- [ ] `:app:testDebugUnitTest` passes.

## Out of scope

- Video content parts remain unsupported (the mapper has never rendered
  `type:"video"`) — a separate ticket if video support is wanted.
- The additive 0.37.2 fields the app ignores — `turn.ended.time` /
  `turn.started.time` / `prompt.completed.time`, `turn.started.promptId`,
  `/meta` `web_title`, prompt `skills`/`prompt_id`, error `40927` — need no
  change.
- `/api/v2/sessions` page/projection/batch-archive changes (unused by the app).
- The server's default-model/thinking-effort config drift (deepseek default) is
  config, not protocol.

## Notes

- `docs/server-api.md` → Files → "Session media" documents the new endpoint.
- Sending is unaffected: `ChatApi.postPrompt` still posts
  `source.kind:"file"`, which the server accepts and promotes to
  `session_media`.
