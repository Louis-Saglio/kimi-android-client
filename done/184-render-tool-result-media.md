# Render `session_media` images from tool results

- **ID:** 184
- **Created:** 2026-11-05
- **Depends on:** 175

## Context

Found by the nightly server drift run from 2.5.0 to 2.5.1 on 2026-11-05
(server 2.5.1, upstream commit `6dad2c8d`, "preserve session media in tool
results"). A `ReadMediaFile` call now returns an **array** in the tool result
instead of a plain string:

```json
"output":[
  {"type":"text","text":"<image path=\"/home/ubuntu/spike/one.png\">"},
  {"type":"image","source":{"kind":"session_media","file_id":"f_13df9359…"}},
  {"type":"text","text":"</image>"}]
```

The same array rides `GET /api/v1/sessions/{id}/messages`
(`tool_result.output`), the live `transcript.ops` tool frame, and the warm
REST transcript. `GET /api/v1/sessions/{id}/media/{file_id}` serves the bytes
(verified live, 200). Non-daemon media (base64/data URLs, `https`) and audio
stay on the plain-string path.

The app already knows how to render `session_media` images and how to fetch
them: `ChatMessage.Part.Image(isSessionMedia=true)` and
`ChatApi.downloadSessionMedia` came with ticket 104. But tool results are
mapped to a plain string on both paths — `ChatMessageMapper.toDomain` builds
`ChatMessage.Part.ToolResult(..., formatDetail(dto.output))` and the
transcript mapper builds `TranscriptFrame.Tool(output = formatDetail(dto.output))`
— so a media result renders as pretty-printed JSON (including the
`<image path=…>` text) instead of the image. On a phone, `ReadMediaFile` is
how the agent shows screenshots and photos; losing the image is a real UX
loss.

This ticket is the tool-result consumer of the new shape. It is not a
breakage: `formatDetail(JsonElement)` already handles arrays without
crashing.

## Description

1. Add a small domain type for a tool-result media reference
   (`fileId`, `name?`, `kind:"image"|"video"`) and carry it alongside the
   output text:
   - `ChatMessage.Part.ToolResult` gains `media: List<ToolMedia> = emptyList()`;
   - `TranscriptFrame.Tool` gains the same.
2. In `ChatMessageMapper`, parse `tool_result.output` when it is a JSON
   array: concatenate the `text` parts into the existing output string (drop
   the `<image …>` wrapper if it is a pure wrapper — it duplicates the
   image), and map every `{type:"image"|"video",
   source:{kind:"session_media",file_id}}` part to the media list. A
   string output keeps today's behaviour exactly.
3. Do the same in the transcript frame mapping (shared by the sub-agent page
   and the live ops fold): an `output` array yields text + media; an unknown
   part type is ignored.
4. Render images in the expanded tool row with the existing
   `ChatAttachments` / image-loader path and `downloadSessionMedia`
   (ticket 104's route), labelled with `name` when present. A video part
   renders as a filename chip (playback is out of scope). Empty text with
   only media must still render the media, not an empty row.
5. Keep the collapsed row unchanged except for a small media indicator when
   the result has one.

## Acceptance criteria

- [ ] A history `tool_result` whose `output` is the captured array renders
      the image (fetched from `GET /api/v1/sessions/{id}/media/{file_id}`)
      and does not show the raw JSON blob; a unit test covers the mapper.
- [ ] The same array on a transcript tool frame (warm REST and a live
      `frame.upsert`) renders identically; the shared mapper test covers both
      entry points.
- [ ] Text parts in the array become the row's output text; an unknown part
      type is ignored; the string-output path is unchanged (regression test).
- [ ] A video part renders as a chip with its `name` (or `video` placeholder)
      and never attempts image decoding.
- [ ] `:app:testDebugUnitTest` passes.

## Out of scope

- Video playback, audio parts, base64/data-URL and `https` media — they stay
  on the text path.
- User-prompt images (ticket 104) and the attachment picker.
- New server routes or a new media store; reuse `downloadSessionMedia`.
- Tool-result parsing heuristics beyond the documented part union.
- Sub-agent page layout changes (it consumes the same frame mapper; only the
  media field is added).

## Notes

Relevant code: `data/chat/ChatMessage.kt` (`Part.ToolResult`),
`data/chat/Transcript.kt` (`TranscriptFrame.Tool`),
`data/chat/ChatMessageMapper.kt` (`toDomain(MessageDto)` and
`toDomain(TranscriptFrameDto)`), `data/chat/ChatApi.kt`
(`downloadSessionMedia`), `ui/chat/ChatAttachments.kt` (the existing image
fetch/render path). Wire reference: `docs/server-api.md` → Messages → History
and Transcript → Frames; the upstream projection is
`packages/kap-server/src/services/messages/messageProjection.ts` at tag
2.5.1.

## Implementation notes

**2026-11-06 —** New domain type `ToolMedia(fileId, kind Image|Video, name?)` plus
`ToolOutput(text, media)` in `data/chat/ToolMedia.kt`. `ChatMessageMapper.toolOutput`
is the one parse of a tool result's `output`, shared by the history `tool_result`
part (`ChatMessage.Part.ToolResult.media`), the REST transcript tool frame
(`TranscriptFrame.Tool.media`; a media-only result no longer lets the progress tail
stand in) and the live fold (`TranscriptFold` → `SessionEvent.ToolCallResult.media`).
A content-part array (every element a typed object, at least one
`text`/`image`/`video`) yields its text parts joined by "\n", minus bare
`<image …>`/`</image>` (or video) wrapper tags when the array carried media, and one
`ToolMedia` per `session_media` image/video part; unknown part types and other
sources are ignored; every other value (strings, objects, other arrays) keeps
`formatDetail`. `ChatTimeline` carries the media to `ChatItem.ToolActivity.media`
on every row path (history + orphan results, transcript flatten, running-turn seed,
live result) under the output's merge rules. `ToolRows`: a row with media always
expands to `ToolMediaBlock` — images via `HistoryImageThumbnail` + the full-screen
viewer (named when `name` is present), videos as a "▶ name"/"▶ video" chip that never
reaches the image loader — and the collapsed row gains only `ToolMediaIndicator`.
The bytes come from the new shared `resolveImageBytes` (`ui/chat/ChatAttachments.kt`,
ticket 104's `downloadSessionMedia` route), used by `ChatAttachments.imageBytes` and
the new `SubagentViewModel.imageBytes`; `MessageList` threads `onImageClick`/
`loadImageBytes` into the tool rows, and `SubagentScreen` mounts the viewer.
Tests: `ToolResultMediaTest` (the verbatim capture through all three entry points,
text/unknown-part/video/wrapper rules, string and structured-output regressions),
`ChatTimelineToolMediaTest` (every row path), and the instrumented
`ToolRowTest` media cases (indicator, media-only expansion, video chip never loaded).
