# Structured attachment chips (replace the notice regex)

- **ID:** 160
- **Created:** 2026-09-23
- **Depends on:** none

## Context

Since 0.39.1 the server records attachments structurally: history user
messages carry `metadata.origin.attachments [{name, mediaType, size,
path}]`, echoed on the `context.spliced` splice and `turn.started`
origin (docs/server-api.md:2849-2851, 842); transcript `attachments[]`
gained typed file entries; since 0.42.0 image/video media parts preserve
the attachment `name`. Ticket 034 reverse-engineers attachment chips by
regexing the agent's `Attached file "…"` notice text — fragile against
wording drift and blind to name/size/media type. The structured array
replaces the parser outright (AGENTS.md: no backward-compat shims; the
server always sends it now).

## Description

**1. DTO.** Parse `metadata.origin.attachments` on the user-message DTO
(origin parsing currently reads `kind` only). Delete 034's notice-regex
parser and its tests.

**2. Chips.** The attachment chip row renders from the structured array:
name, human size, media-type icon; image attachments keep the existing
bubble/viewer and gain the filename label (0.42.0 `name` on the media
part). Unknown/absent array → no chips (an old session re-read today
comes from a current server, so this is just defensive).

**3. Tests.** Parse test with the doc shape (name/mediaType/size/path);
chip ViewModel test; androidTest: text attachment chip shows name+size,
image bubble shows filename. The deleted regex's test fixtures go with
it.

## Acceptance criteria

- [ ] Unit + androidTests above pass; existing suite green.
- [ ] On device: sending a file shows a chip with its real name and
      size; an image attachment shows the filename in history.
- [ ] No code path matches on the `Attached file` notice text anymore.
- [ ] `flock /tmp/kimi-android-gradle.lock ./gradlew :app:testDebugUnitTest
      :app:assembleDebug --max-workers=1 --no-daemon` passes; release APK
      published to the file server per AGENTS.md.

## Out of scope

- Server-local path attachments (`source:{"kind":"path"}` / `"path"`
  part variant — a composer feature; rejected in the drift review).
- Attachment management (delete/download) — 063/104 territory.
- Transcript `attachments[]` typed entries (sub-agent page surface;
  separate ticket if wanted).

## Notes

- `path` is server-local: display the basename, never the full path, in
  chips.
