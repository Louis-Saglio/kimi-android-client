# Render attachments in chat history

- **ID:** 034
- **Created:** 2026-07-24
- **Depends on:** 033

## Context

After ticket 033 the app can send attachments, but the chat timeline still
shows them raw: the server rewrites `file` parts into a text notice
(`Attached file "<name>" (<mime>, <size> bytes): <abs path> — open it with the
Read tool`) and converts images to base64 `image` items, so a user message with
attachments currently renders as an odd sentence and/or an ignored content
item. The web client parses the notice back into an attachment chip — the
notice wording is an explicit client contract (see `docs/server-api.md`
§ Prompts). Parity requires the app to do the same.

## Description

In the chat message pipeline (`data/chat/` parsing + `ui/chat/` rendering):

- Parse the `Attached file "<name>" (<mime>, <size> bytes): <path> — …` text
  notice (exact regex per `docs/server-api.md` / web client's
  `ATTACHED_FILE_NOTICE_RE`) into an attachment chip model (name, mime, size);
  render it as a compact chip row inside the user bubble instead of the raw
  sentence. Extract the `fileId` from the materialized filename prefix
  (`<fileId>-<name>`) when present so the chip can fetch the file.
- Render `image` content items in user messages as thumbnails (bounded decode;
  base64 source in history, `file_id` source if it appears). Tapping a
  thumbnail opens a full-screen viewer; for `file_id` sources fetch via
  `GET /api/v1/files/{id}` with the bearer token.
- Attachment chips for non-image files show name + size + a generic icon;
  tapping opens the file via `GET /api/v1/files/{id}` (share sheet / open
  intent) when a `fileId` was recovered, otherwise the chip is inert.
- Messages without attachments render exactly as today.

## Acceptance criteria

- [ ] Unit test: the notice text parses into name/mime/size (and fileId from
  the path basename) for the documented wording; non-matching text passes
  through untouched.
- [ ] Unit test: a user message mixing an attachment notice and real text
  renders the text plus a chip, not the raw notice.
- [ ] Manual: send a photo + a `.txt` (ticket 033 flow), reload the session —
  the echo and the reloaded history show a thumbnail and a file chip, not the
  raw notice text.
- [ ] `./gradlew :app:assembleRelease` passes.

## Out of scope

- Video rendering/playback.
- Saving attachments to phone storage, thumbnail caching across app restarts
  beyond what the image loader gives for free.
- Rendering attachments inside internal envelopes or assistant messages.

## Notes

- The notice wording is a server/client contract — if the live wording differs
  from the doc, update `docs/server-api.md` in the same diff.
- If image loading justifies it, adding Coil is acceptable (check the pinned
  Compose BOM compatibility note in `AGENTS.md` first).
