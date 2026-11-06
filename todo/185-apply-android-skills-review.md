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

## Implementation notes — 2-state

*2026-11-06.* Skills loaded: `android-dev`, `kotlin-coroutines`, `kotlin-flows`,
`android-data-layer`, `android-testing`. One project convention won over a skill
rule: `TranscriptColdStart` stays a session-scoped `@Singleton` that owns its scope
(docs/conventions.md, Cold start). B1 fixes only the missing cancel and the
hard-coded dispatcher, as the review scoped it.

- **B1 — done.** `forget` now runs `coldStarts.remove(id)?.cancel()`.
  `fetchAndApply` calls `ensureActive()` between the reads and the apply, so a
  forgotten fetch never seeds the socket or re-creates store records. `sync` and
  `resync` share `awaitFetch`. When `await()` throws a `CancellationException`,
  `awaitFetch` rethrows it only if the caller itself was cancelled
  (`currentCoroutineContext().ensureActive()`). Otherwise it returns
  `Result.failure(TranscriptColdStart.Interrupted)`, so a second awaiter gets the
  ordinary load error with Retry and does not end silently. The scope's dispatcher
  is now injected through a new `@MainImmediateDispatcher` qualifier
  (`di/AppModule.kt`). The test graph passes `Dispatchers.Main`, which is the
  `setMain` delegate and behaves like `Main.immediate` over a TestDispatcher.
  `awaitInFlight()` now joins every child of the scope, cancelled fetches included,
  so tests keep it as a leak guard. Cancelling the Deferred alone would not stop a
  read blocked in OkHttp's `execute()`: the Deferred only completes once its
  `withContext(IO)` block returns. The fetch is only really stopped together with
  the ApiClient change under B7. Test:
  `TranscriptColdStartSyncTest."forget cancels the in-flight fetch, which applies nothing, and its awaiter gets a failure"`
  holds the messages read open, releases the store, and asserts an `Interrupted`
  failure within 5 s, no watermark, no roster record, and that the next `sync` is
  `Fresh`.
- **B7 — done, with a wider fix than suggested.** On inspection, the suggested
  `cancelChildren()` before each early return was ineffective in two ways. The
  reads were awaited in a fixed order, so a fast prompts failure still waited for a
  slow messages read. And cancelling a read does not interrupt OkHttp's blocking
  `execute()`, so `coroutineScope` still waited for it until the response or the
  read timeout. I checked this: with only the cold-start change, the new test fails.
  The fix has two parts:
  1. Each read's `async` throws its failure (a private `ReadFailed` wrapper,
     unwrapped right outside `coroutineScope`, so the catch is narrow). The first
     failure in completion order cancels its siblings.
  2. `ApiClient.executeResponse` aborts the OkHttp call when the caller of a GET is
     cancelled. An `UNDISPATCHED` child awaits cancellation and calls `call.cancel()`
     in its `finally`. POSTs are deliberately left alone: aborting a write mid-flight
     could drop a prompt the server half-received, and that would change behaviour.

  Tests: `TranscriptColdStartSyncTest."a failed read fails the cold start without waiting for a hung sibling"`
  holds the messages read and fails the prompts read, then expects a failure within
  5 s while the held read is still unreleased. New `ApiClientCancellationTest`: a
  cancelled GET joins within 5 s and fires `EventListener.canceled`; a cancelled
  POST runs to completion with no `canceled` event. All three were confirmed red
  without the ApiClient change.
- **B2 — done.** In `JobDetailViewModel.startWatch`, a non-`Api` failure while
  `connectionId` still equals the connection the watch was sent on (the socket's
  15 s no-reply timeout) re-sends the watch immediately. The timeout itself already
  spaced the attempts, so no extra delay is added. Re-sends are capped at
  `MAX_WATCH_RETRIES` = 3 per connection; the counter resets on a reply or a new
  connection id. A watch lost with its connection still waits for the next
  connection. Test:
  `JobDetailViewModelTest."a watch left unanswered on an open connection is re-sent on it"`
  does not answer the first watch, advances the Main test scheduler past 15 s
  (`sendControl`'s `withTimeoutOrNull` runs on the caller's Main, so on the test
  clock), and checks that the second watch streams. I first tried an injectable
  timeout and dropped it, because the virtual clock makes it unnecessary. The test
  was confirmed red without the fix.
- **B3 — done, with a different start point.** The gate is now
  `private val socketGate = viewModelScope.launch(start = CoroutineStart.LAZY)`,
  and the first `onScreenStarted()` starts it with `socketGate.start()` (idempotent).
  The review's literal option, `start()` as the last line of the existing (first)
  `init`, would have run the inline cold start before `coldStartJob`,
  `coldStartDone`, `runningTools` and others were initialised. A start from a named
  trigger after construction cannot depend on source order at all. The ordering
  comments are deleted. Behaviour is the same, because `ChatScreen` always wires
  ON_START. The only shift is the restored-page hand-over: the gate now opens at
  the first ON_START instead of in the constructor, one frame later, and the
  keep-alive keeps feeding until then.
  `SubagentStreamStoreKeepAliveTest."opening the chat hands the feed over…"` now
  asserts that the keep-alive is still the feeder between construction and
  `onScreenStarted`.
- **B4 — done as suggested.** New `DataError.SearchDisabled`, plus a
  `toUserMessage` branch. `SearchApi.search` maps exactly the disabled-index 50001
  (`isGlobalSearchDisabled`, moved to `data/search` and still internal) to it.
  `SearchViewModel` now checks `error is DataError.SearchDisabled`. The existing
  `SearchViewModelTest` cases go through the real `SearchApi` on MockWebServer, so
  they pass unchanged. `SearchDisabledMappingTest` moved to `data/search`, and
  `SearchApiTest` gained a case: the disabled 50001 maps to `SearchDisabled`, and
  another 50001 stays `DataError.Api`. The 40001 page-token check in the
  ViewModel is left as it is (not part of this finding).
- **B5 — done.** `resolveImageBytes` takes a `decodeDispatcher` and decodes the
  inline base64 under `withContext(decodeDispatcher)`, catching only
  `IllegalArgumentException`. The dispatcher comes from a new `@DefaultDispatcher`
  qualifier into `ChatViewModel` (passed on to `ChatAttachments`) and
  `SubagentViewModel`. Their internal test constructors default it to
  `Dispatchers.Default`, following the `@StreamCoalesceWindowMillis` precedent. There
  is no JVM test for the decode, because `android.util.Base64` is a stub there
  (`isReturnDefaultValues`). This is not a bug fix, and the existing `imageBytes`
  tests pass.
- **B6 — done.** The `SocketEvent.Failed(SessionNotFound)` branch calls
  `reportLoadFailure(event.error)` after clearing `isReconnecting`. Two adjacent
  private mappers, `PendingApprovalRequest.toPendingApproval()` and
  `SessionEvent.ApprovalRequested.toPendingApproval()`, replace the three inline
  constructors (the cold start, `ApprovalsReset`, `ApprovalRequested`). There are
  two because the live frame and the snapshot carry different domain types. This is
  a pure refactor, and the existing tests pass unchanged.
- Docs: docs/conventions.md describes GET cancellation (data/network), the
  `SearchDisabled` mapping (data/search), the cancel-on-forget and fail-fast reads
  (Cold start), the watch re-send (job screen), the lazily started gate (sub-agent
  feeder), the decode dispatcher (media rows), and the typed-condition and
  dispatcher-qualifier rules (Errors). `SubagentStreamStore`'s own
  `Dispatchers.Main.immediate` scope is unchanged (not part of these findings).
- Gate: `./gradlew :app:testDebugUnitTest :app:compileDebugAndroidTestKotlin` is
  green (1093 tests, 0 failures, 1 skipped). The instrumented
  suite was not run in this step.
