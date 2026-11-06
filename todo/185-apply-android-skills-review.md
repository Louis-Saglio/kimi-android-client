# Apply the Android skills review to the server 2.5 migration

- **ID:** 185
- **Created:** 2026-11-06
- **Depends on:** 171–184

## Context

Tickets 171–184 (commits `7c25b5b..a7bc0f9`) were implemented by an agent
that did not have the Android skills loaded (`android-skills` plugin:
`android-dev`, `android-data-layer`, `kotlin-coroutines`, `kotlin-flows`,
`compose`, `android-ux`, `coil-compose`, `android-testing`). A read-only review
of that range with those skills loaded, in four parts (data layer, state
holders, Compose UI, tests), found the deviations below. This ticket fixes the
ones worth fixing; the rest are listed under Out of scope with the reason.

## Description

Data layer
- **A2** — replace `runCatching` (catches `Throwable`) in the new decode paths
  (`SessionSocket.decodeFlat`, `TranscriptFold`, `ChatMessageMapper`, `Tasks`)
  with a helper that catches only the parser exceptions
  (`IllegalArgumentException`, which covers `SerializationException`, and
  `DateTimeParseException`).
- **A3** — share the tool-frame rendering between `TranscriptFold.render` and
  the `"tool"` branch of `ChatMessageMapper.toDomain(TranscriptFrameDto)`.

State holders
- **B1** [bug] — `TranscriptColdStart.forget()` cancels the in-flight fetch,
  the apply step checks `ensureActive()`, other awaiters see a failure (not
  their own cancellation); the main dispatcher is injected.
- **B2** [bug] — a `task.watch` that times out while the connection stays open
  is retried.
- **B3** — the `ChatViewModel` socket gate no longer depends on being the last
  initializer (`CoroutineStart.LAZY` + `start()` at the end of `init`).
- **B4** — `DataError.SearchDisabled` mapped in the data layer instead of the
  ViewModel decoding the raw `50001` envelope.
- **B5** — `resolveImageBytes`: no `runCatching` in a suspend function; Base64
  decoding off the main thread.
- **B6** — de-duplicate load-failure reporting and the `PendingApproval`
  mapping in `ChatViewModel`.
- **B7** [bug] — a failed cold-start read cancels its sibling reads.

Compose UI
- **C1** [bug] — the swarm chip must not starve the model chip or fall below a
  48dp touch target: icon-only like `PermissionModeChip`, label in semantics,
  `modifier` parameter (also covers the modifier part of C7).
- **C2** — tool-result images are cached (bounded in-memory LRU keyed by
  session and file id) instead of re-downloaded on every recomposition entry.
- **C3** [bug] — the plan approval card's scroll state is keyed by approval id.
- **C4** — the plan body's height cap follows the window height.
- **C5** — `SearchDisabledState` replaced by `EmptyState` with an optional
  supporting text.
- **C6** — `JobOutputBlock` applies the caller's modifier to its root and
  announces the watch error politely.
- **C8** — tool-result thumbnails get a description of their own.

Tests
- **D1–D8** — fix the vacuous "no poll" check and the leaking teardown in
  `JobDetailViewModelTest`, the timing-margin subscriptions and sleeps
  (`SessionSocketTest`, `ChatViewModelTest`, `TranscriptColdStartTest`,
  `ChatJobsTest`, `ChatTodosTest`, `SearchViewModelTest`,
  `OkHttpSessionListSocketTest`), semantics-first selectors for the swarm chip
  and media rows, and the swarm send test that only exercised its own fake.

## Acceptance criteria

- Every item above is fixed or explicitly documented here as not fixed, with
  the reason.
- New unit tests cover B1, B2, B7 and C3 behaviour where testable on the JVM.
- `./gradlew :app:testDebugUnitTest :app:compileDebugAndroidTestKotlin` is
  green, and the instrumented suite passes on the emulator runner.

## Out of scope

- **A1** (evicting per-session socket state): needs a product decision on
  what the job screen shows for an ended task after the session was left.
- **A4, C9** (ticket-tagged KDoc that restates names): codebase-wide style;
  change it everywhere or nowhere.
- **C7** (move the hand-rolled chips to M3 `FilterChip`): the sibling chips are
  hand-rolled too; restyle them together if wanted.
- Coil adoption, `stringResource`, `runTest` instead of the real-I/O harness:
  documented project choices (see the review's "Conflicts" sections).

## Implementation notes — 1-data

*2026-11-06.* Skills loaded: `android-dev`, `android-data-layer`,
`kotlin-coroutines`, `kotlin-flows`. No skill rule conflicted with a documented
project convention for these two findings.

- **A2 — done.** New `parseOrNull(onFailure = {}) { … }` (`data/ParseOrNull.kt`,
  `internal inline`) catches only `IllegalArgumentException` (which covers
  kotlinx.serialization's `SerializationException` and the
  `jsonObject`/`jsonPrimitive` casts) and `DateTimeParseException`
  (`Instant.parse`), so an `Error`, a mapper bug or a `CancellationException`
  propagates. The review's suggestion had two helpers (a plain one and a `Log.w`
  variant). One helper with an `onFailure` lambda does both, and it builds the
  log message only on failure. Every `runCatching` in the four named files is
  gone: `SessionSocket.decodeFlat` and the older `decodePayload`,
  `TranscriptFold` (op decode, step retry, undo/compaction markers),
  `ChatMessageMapper` (approval/question frames and entities, the
  `createdAt` timestamp, and the tool frame's `inputText`/`display`, which now
  live in `toolRendering`), and `Tasks.kt` (`toInstantOrNull`, `bashArgsOf`).
  Valid input behaves the same, and malformed input is still dropped with the
  same `Log.w`. Other `runCatching` sites outside this range (`ResolvedQuestion`,
  `ModelsRepository`, `OkHttpSessionListSocket.decodePayload`, `data/sessions/*`,
  `AttachmentStore`, `ui/*`) are left alone. docs/conventions.md (Errors) says to
  convert them when they are next touched. B5 handles the `ui/chat` one.
  Test: `ParseOrNullTest` checks that a valid parse passes through, that
  malformed JSON, a missing field, a non-object `jsonObject` and a bad instant
  are all dropped and reported, and that an `IllegalStateException` and a
  `StackOverflowError` propagate.
- **A3 — done as suggested.** `ChatMessageMapper.toolRendering(TranscriptFrameDto)`
  returns `ToolRendering(name, preview, input, args, agentCall)`. That data
  class moved out of `TranscriptFold`'s privates and is now `internal`
  top-level in `ChatMessageMapper.kt`. `TranscriptFold.onToolFrame` calls it in
  place of its own `render`, which is deleted. The mapper's `"tool"` branch of
  `toDomain(TranscriptFrameDto)` builds `TranscriptFrame.Tool` from it.
  `toolRendering`'s KDoc now holds the partial-`inputText` rule that both copies
  used to state. This is a pure refactor, and the existing fold and mapper tests
  pass unchanged. New test:
  `TranscriptFoldTest."a live tool frame renders exactly like the same frame on a REST transcript page"`
  feeds the same frame body (partial `inputText`, complete `inputText`, Bash
  with description plus display, `agent_call`, an undecodable `display`) through
  the fold and through the REST page mapping, and asserts that all five fields
  match.
- Docs: docs/conventions.md has the `toolRendering` note next to the
  `toolOutput` "one parse" paragraph, and the `parseOrNull` rule under Errors.
