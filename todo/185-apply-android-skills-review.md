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
