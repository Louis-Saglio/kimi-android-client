# Data-layer consistency batch

- **ID:** 068
- **Created:** 2026-07-31
- **Depends on:** none

## Context

Small findings from the 2026-07-31 tech-debt audit, batched to avoid ticket
spam. Each item is a few lines; together they remove a pile of paper cuts
and two error-handling holes. Batching alternatives considered: filing
eight micro-tickets (rejected — process overhead dwarfs the diffs) or
folding them into 067 (rejected — DI rewiring and behavior fixes shouldn't
share a diff).

## Description

1. **Envelope unwrap idioms.** Three coexist: `Envelope.toResult()`,
   `toAckResult()`, and two hand-rolled sites that need nullable-`data`
   semantics — `ChatApi.kt:291-300` (`sessionStatus`; a null `data` on
   `code == 0` silently reports `busy = false`) and `ModelsRepository.kt:
   70-74`. Add `Envelope.toNullableResult()` and migrate both sites.
2. **`SessionStatusData` required fields.** `ChatDtos.kt:81-82`
   (`contextTokens`, `maxContextTokens`) have no defaults — every other DTO
   defaults everything; a server omission degrades the status poll to
   `MalformedResponse` instead of a partial read. Give them defaults.
3. **Transcript path bypasses InternalEnvelopes stripping.**
   `ChatMessageMapper.kt:129-138` passes transcript text straight through
   while the message path strips harness envelopes (`:63`, `:74-80`). A
   sub-agent's user-role transcript frames (its internal prompts/nudges)
   can carry `<system-reminder>`/`<notification>` traffic and render as raw
   XML on the sub-agent page. Apply the same stripping to user-role
   transcript frames.
4. **Dead DTO fields.** Delete `TranscriptData`'s eight unconsumed fields
   (`ChatDtos.kt:331-338`) and `TranscriptAgentDto` (`:343-348`);
   `ignoreUnknownKeys` already covers the wire shape.
5. **Stale comments.** `ChatDtos.kt:21-23` (`id: null` → the key is absent
   since 0.31.0), `ChatEvents.kt:117` (`SubagentSuspended` "not captured
   live" — it is, `SessionSocket.kt:309-310`), `ChatTimeline.kt:277-279`
   ("No refetch ever comes" — predates ticket 046), `ChatScreen.kt:114`
   (socket "held only while the screen is visible" — predates the 045
   gate).
6. **Silent frame drops.** `SessionSocket.kt:318` ignores frame types
   without a log; add a `Log.d` with the type so a server rename shows up
   during development.
7. **`AttachmentNotice.kt:31`** — `groupValues[3].toLong()` can throw
   `NumberFormatException` past the `DataError` boundary
   (`ApiClient.kt:90-99` only maps serialization/IO). Use `toLongOrNull`
   and treat failure per the mapper's no-notice contract.
8. **"missing data" leaks to the UI.** `Envelope.kt:25` produces
   `DataError.Api(0, "missing data")` which `toUserMessage()` shows
   verbatim; replace with a user-facing string.
9. **Repo hygiene.** Delete the untracked `pids` scratch file at the repo
   root and add it to `.gitignore`.

## Acceptance criteria

- [ ] Items 1-9 done; transcript stripping covered by a test next to
  `InternalEnvelopesTest`.
- [ ] `./gradlew :app:testDebugUnitTest` green.

## Out of scope

- Switching the approval event family (ticket 069).
- Pagination/query-construction normalization (raw interpolation is safe
  for today's id shapes; not worth a diff until a third paginated endpoint
  appears).

## Notes

- Keep each item a separate commit if the batch grows past comfort; the
  diff budget is ~500 lines total and this should land well under.
