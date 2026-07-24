# Attach and send files in chat

- **ID:** 033
- **Created:** 2026-07-24
- **Depends on:** none

## Context

The app cannot attach files to a user message, while the web client can. Louis
drives the agent from his phone; being unable to send a photo, PDF, or log file
into a session is a recurring gap (parity with the web client's composer).

The protocol is already documented in `docs/server-api.md` (Files section,
2026-07-24): upload bytes via `POST /api/v1/files` (multipart, one file per
request, 50 MiB limit, verified live), then reference the returned `file_id` in
the prompt's `content` items — `{"type":"image","source":{"kind":"file","file_id":…}}`
for `image/*`, `{"type":"file","file_id":…,"name":…,"media_type":…,"size":…}`
for everything else. The content-item shapes and the server-side rewriting are
source-derived; this ticket's manual verification doubles as the live check —
if behavior differs, fix `docs/server-api.md` in the same diff.

Decisions from the product discussion: support any file type (same upload code
path, images just get a thumbnail chip); composer UX is an attach button plus
removable chips above the input, mirroring the web client. Rejected
alternatives: images-only (artificial limit, same code), auto-send on pick (no
way to caption or reconsider).

## Description

Add an attach affordance to the chat composer (`ui/chat/ChatScreen.kt`):

- An attach (paperclip/plus) icon button next to the input that launches the
  Android document/photo picker (`ActivityResultContracts.GetContent` with
  `*/*`, or OpenMultipleDocuments if trivially cheap — single-file is fine).
- Picked files appear as removable chips above the text field: file name +
  size, image thumbnail (Coil is not currently a dependency — a simple
  `ImageBitmap` decode of a bounded sample is acceptable, or a generic icon if
  that gets heavy) for `image/*`.
- On send: for each attachment, read the bytes via `ContentResolver`, upload
  with `POST /api/v1/files` (OkHttp `MultipartBody`), collect the returned
  `FileMeta`, then post the prompt with the attachment content items followed
  by the text item (if any). Sending with attachments and no text is allowed;
  sending with neither is not.
- Show per-attachment upload progress/failure on the chip; a failed upload
  blocks the send with an error surfaced to the user (existing error channel).
- Data layer: add the upload call + DTOs in `data/chat/` (`ChatRepository.kt`,
  `ChatDtos.kt`); enqueue prompt with the typed content union (extend the
  existing content DTO, currently text-only).
- Text-only sending must behave exactly as today.

## Acceptance criteria

- [ ] Unit test: repository maps an uploaded `FileMeta` to the correct prompt
  content item (`image` for `image/*`, `file` otherwise) and keeps text items.
- [ ] Unit test: ViewModel blocks send while an upload is in flight and
  surfaces an upload failure without posting the prompt.
- [ ] Manual: attach a photo and a `.txt` file, add text, send — the turn runs
  and the agent can read the attached `.txt` (it lands in
  `<sessionDir>/attachments/`).
- [ ] Manual: text-only send unchanged; removing a chip before send excludes it.
- [ ] `./gradlew :app:assembleRelease` passes.

## Out of scope

- Rendering attachments of past/echoed messages in the chat timeline — ticket 034.
- Video attachments, camera capture, multiple-file picker polish, download of
  attachments to phone storage.
- Anything above a single attach action per file (retry per chip is the only
  recovery).

## Notes

- Protocol reference: `docs/server-api.md` § Files and the `content` union
  under § Prompts. Web client reference implementation:
  `apps/kimi-web/src/composables/useAttachmentUpload.ts` and
  `useWorkspaceState.ts` (MoonshotAI/kimi-code).
- Upload is one file per request; run uploads sequentially to keep it simple.
- 50 MiB server limit — surface the 413 as a normal send failure, no special UX.
