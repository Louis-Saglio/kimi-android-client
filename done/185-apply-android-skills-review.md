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
  change it everywhere or nowhere. (The review's own sites were cleaned up
  afterwards, see Follow-up — 5-rest.)
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

## Implementation notes — 3-compose

*2026-11-06.* Skills loaded: `android-dev`, `compose`, `android-ux`, `android-testing`.
One project convention won over a skill rule: images stay on the project's
`HistoryImageThumbnail` + `loadImageBytes` byte loader (docs/conventions.md, media
rows) instead of android-dev's Coil default, so C2 is the review's minimal LRU, not
a Coil `ImageLoader`. No Robolectric in the project, so the Compose-level fixes (C1,
C3, C4, C6, C8) are covered by instrumented tests, which compile here but were not
run.

- **C1 — done, plus one extra change.** `SwarmModeChip` is icon-only, like
  `PermissionModeChip`. The sparkles glyph sits on the secondaryContainer fill when
  on and is bare when off. The label moved to the semantics (`"Swarm mode"`, still
  `toggleable(role = Role.Switch)`, so on/off is announced). The chip is
  `sizeIn(minWidth = 48.dp, minHeight = 48.dp)` and takes a `modifier` parameter
  (the modifier part of C7). Before shipping I redid the layout arithmetic for a
  360dp phone mid-turn, and icon-only alone was not enough. The middle zone is about
  145dp after attach, Stop and the default-padded Steer, and ring 48 + permission 60
  + swarm 48 = 156dp, so the last-measured swarm chip would still get about 37dp.
  The Steer `Button` now uses 16dp horizontal content padding instead of M3's 24dp.
  It stays above M3's 58dp minimum width, and the zone grows to about 161dp. With
  that, all three icon-sized controls keep 48dp. When idle at 360dp the model chip
  gets about 72dp; before, with the label, it got about 50dp. Limit not fixed: mid-turn
  below about 400dp the model chip still has almost no room. That is the row's
  documented give ("the model chip ellipsizes under pressure"), and it was nearly as
  tight before ticket 174. Fixing it means changing the layout (for example a
  second row, or an icon-only Steer), which needs a product decision. Tests:
  `ComposerLayoutTest` pins the bar to 360dp and asserts the model and swarm chips
  are ≥ 48dp wide when idle, and the swarm, permission and ring controls are ≥ 48dp
  wide mid-turn. `SwarmModeChipTest` now matches the `"Swarm mode"` description.
- **C2 — done as the minimal fix.** New `ImageBytesCache` (ui/chat, `@Singleton`,
  pure Kotlin so it runs on the JVM, unlike `android.util.LruCache`). It is a
  synchronized access-ordered `LinkedHashMap` with a 16 MiB byte budget, keyed by
  `(serverUrl, sessionId for session_media / null for /files, fileId)`. An entry
  larger than the budget is not kept. `resolveImageBytes` checks it before the
  session-media or `/files` GET and stores only successes, so a failed fetch retries
  on the next entry. Inline base64 is not cached. It is injected into `ChatViewModel`
  (passed to `ChatAttachments`) and `SubagentViewModel`, so the chat and the sub-agent
  page share it. Their internal test constructors default to a fresh instance. The
  decoded bitmap is still rebuilt on re-entry: that is a bounded CPU decode, not a
  download. Tests: `ImageBytesCacheTest` (key isolation, LRU eviction order,
  replacement accounting, oversize entry) and
  `ChatAttachmentsTest."a re-loaded session media image is served from the cache without a second fetch"`.
- **C3 — done, keyed inside the card.** The key is inside `ApprovalCard` rather than
  only at the `ChatScreen` call site: `key(approval.approvalId) { ApprovalCardBody(…) }`,
  so every caller resets the card's state, the plan's `rememberScrollState()`
  included, when the head changes. Test (instrumented, compile-only here):
  `ApprovalCardTest.theNextPlanReviewOpensAtTheTopOfItsPlan` scrolls plan A to its
  end, swaps the head to plan B, and asserts that B's first step is displayed and its
  last step is not.
- **C4 — done, IME-aware.** `planMaxHeight()` returns 30% of
  `LocalWindowInfo.current.containerSize.height` minus `WindowInsets.ime`, clamped to
  72–240dp. The review's formula ignored the keyboard, but the window size does not
  shrink when the IME opens, so the IME has to be subtracted. The function is read
  in composition inside the plan `Surface`'s content, so a rotation or the IME's
  animation recomposes only that scope. The `planHeight <= 240.dp` test still holds.
- **C5 — done as suggested.** `EmptyState(message, modifier, supportingText: String? = null)`.
  The optional line is `bodyMedium`/`onSurfaceVariant`, `TextAlign.Center`. The column
  uses `spacedBy(8.dp, CenterVertically)`, which is the same as `Arrangement.Center`
  for the single-text callers. `SearchDisabledState` is deleted. Its rationale (no
  Retry) is now a comment at the call site. One small visual difference: the main
  message is no longer `TextAlign.Center`, the same as every other `EmptyState`.
  It fits on one line on a 360dp phone. `SearchScreenTest` matches on text and is
  unchanged.
- **C6 — done as suggested.** `JobOutputBlock` is one root
  `Column(modifier.fillMaxWidth(), spacedBy(6.dp))`. The store-backed placeholder,
  the loading box and the output `Surface` take `Modifier.weight(1f).fillMaxWidth()`,
  the caller's former weight, so the layout is the same. The error `Text` has
  `liveRegion = LiveRegionMode.Polite`. `JobOutputBlockTest.aWatchErrorShowsAboveTheFallbackSummary`
  asserts the live region.
- **C8 — done as suggested.** `HistoryImageThumbnail` takes
  `contentDescription: String = "Attached image"`. User bubbles keep the default.
  `ToolMediaBlock` passes `"Tool result image <name>"` (or `"Tool result image"`).
  The video chip is `clearAndSetSemantics { testTag; contentDescription = "Video <name>" }`,
  so TalkBack reads one description instead of the "▶" glyph's text. The visible
  name caption under a thumbnail keeps its own semantics on purpose: it is the only
  carrier of the name when the image fails to load, because the placeholder tiles
  have no description. Tests: `ToolRowTest.mediaOnlyResultShowsTheIndicatorAndExpandsToTheImageAndVideoChip`
  now finds the chip by its `"Video"` description. The new
  `aLoadedToolResultImageIsDescribedAsSuch` loads a real 4×4 PNG and waits for
  `"Tool result image one.png"`, with no `"Attached image"`.
- Docs: docs/conventions.md covers the icon-only swarm chip and the Steer padding,
  the plan cap and its key, the `EmptyState` supporting text (search, and
  `ui/common`), the `ImageBytesCache` and the tool-image/video descriptions (media
  rows), and the job output live region and root Column (job screen).
- Gate: `./gradlew :app:testDebugUnitTest :app:compileDebugAndroidTestKotlin` is
  green (1098 tests, 0 failures, 1 skipped). The instrumented suite was not run in
  this step: the new and updated `ComposerLayoutTest`, `ApprovalCardTest`,
  `JobOutputBlockTest`, `SwarmModeChipTest` and `ToolRowTest` cases only compile here.
  The 360dp mid-turn width assertion relies on the layout arithmetic above (a margin
  of about 5–10dp at font scale 1.0), so check it first on the runner.

## Implementation notes — 4-tests

*2026-11-06.* Skills loaded: `android-testing`, `kotlin-coroutines`, `kotlin-flows`,
`compose`. One project convention won over a skill rule: the unit tests keep the
real-I/O harness (`runBlocking`, real-clock `awaitCondition`/`awaitState`, an
Unconfined Main) instead of android-testing's `runTest` on one shared scheduler, as
the review's "Conflicts" section and this ticket's Out of scope say. The fixes make
that harness honest where it was not; they do not replace it. Only tests changed,
plus the media-row test tags in `ToolRows.kt`.

- **D1 — done as suggested, plus a sentinel.** `opening a running job streams its
  output with one watch and no poll` now advances the Main test scheduler
  (`mainDispatcher`, already a field since B2) by 2.5 s and runs it, so any
  `viewModelScope` timer really fires. The 2.5 s of wall time is gone. An
  OkHttp send is asynchronous, so the test then calls `onScreenStopped()`: its
  `task.unwatch` goes out on the same socket after anything a timer sent, and the
  test awaits it before asserting one watch, one unwatch (the sentinel's own) and no
  REST request. Confirmed red: a temporary `delay(2_000)` + `watchTaskOutput` on
  `viewModelScope` in `onScreenStarted` failed this test (and the B2 retry test).
  The old `delay(2_500)` would have stayed green. The change was then reverted.
- **D2 — done as suggested.** `newViewModel` records each instance in
  `viewModels`, and `connect()` records its collection in `socketJobs`. `tearDown`
  cancels and joins both in `runBlocking` before `appScope.cancel()`,
  `server.close()` and `resetMain()`.
- **D3 — done as suggested.** `SessionSocketTest`'s watcher is launched
  `CoroutineStart.UNDISPATCHED`. `observeTaskOutput` is a `filter` on the
  `MutableSharedFlow`, so the collector subscribes before `launch` returns. The
  `delay(50)` is gone.
- **D4 — done, second option.** The compaction-failure test drops the `uiState`
  collector and the `Thread.sleep(100)`. After the `CompactionFailed` effect, it
  asserts `isCompacting == false` and that no `CompactionDivider` exists. The
  comment now says it proves the end state only: a conflated `StateFlow` collector
  could never prove "never entered". `delay(100)` would only have made a
  claim run that it could not prove.
- **D5 — done.**
  - `TranscriptColdStartTest` (one pending approval seen three times): a trailing
    `taskFrame(taskState("bash-sentinel"))` is awaited (`jobs.isNotEmpty()`) in
    place of `delay(300)`.
  - `ChatJobsTest` (to background): a trailing `bash-sentinel-2` task frame is
    awaited in place of `delay(150)`, and the row list must be exactly
    `[bash-sentinel, agent-fg, bash-sentinel-2]`. I also removed the `delay(100)` in
    `a background question task is never rendered as a job`: it came after an
    already-awaited sentinel and added nothing.
  - `ChatTodosTest` (cold open): before the turn ends, `messagesBody` switches to
    the same page plus `msg_4`. The test awaits `msg_4` in the state, which only the
    refetched page produces, in place of `delay(200)`.
  - `SearchViewModelTest` (disabled 50001): it consumes the first request, then
    checks `isLoading`/`isLoadingMore` straight after each of
    `onRetry`/`onSearch`/`onEndReached`. A load flips them at launch, synchronously
    under the Unconfined Main. It ends with `assertNull(server.takeRequest(200 ms))`.
  - `OkHttpSessionListSocketTest` (newly loaded ids): `delay(100)` became `yield()`,
    with a different reason than the review gave. On inspection, the socket's id
    collector is an `UNDISPATCHED` child running on the test's `runBlocking` event
    loop. The `sessionIds` assignment queues its resumption, so one `yield()`
    applies the ids before the frames are even sent. No production "ids applied"
    signal is needed, and the old delay was not racy either, just slower.
  - Not touched, outside the review's list: the `delay(200)` settles in
    `OkHttpSessionListSocketTest`'s ping/reconnect tests, and the other
    `delay(100/150)` negatives in `JobDetailViewModelTest`.
- **D6 — done as suggested.** `SwarmModeChipTest` and `ComposerLayoutTest` now
  click, check on/off and measure bounds through
  `onNodeWithContentDescription("Swarm mode")`. C1 renamed the description from the
  review's "Toggle swarm mode". One
  `onNodeWithTag(SWARM_MODE_CHIP_TAG).assertIsOff()` keeps ticket 174's stable tag
  covered.
- **D7 — done.** The indicator is selected by `"1 image, 1 video"`.
  `resultWithoutMediaShowsNoIndicator` first asserts the row's `"ls"` preview is
  displayed, so the absence checks are not vacuous, then asserts no `"image"` and no
  `"video"` description (substring). The video chip's tag lookup is dropped. Since C8
  it is `clearAndSetSemantics`, so the "▶ video" text is not in the semantics: the
  test asserts the `"Video"` description, and that `"▶ video"` text does not exist.
  In production, the `toolMediaIndicator` and `toolMediaVideoChip` tags are deleted
  (nothing else used them). The image container keeps a tag, because an unloaded
  thumbnail has no semantics of its own. That tag is now
  `internal const val TOOL_MEDIA_IMAGE_TAG` (`ToolRows.kt`), shared with the test.
- **D8 — done, turned into a wiring check.** `aSendWithTheChipOnCarriesSwarmModeTrue`
  became `togglingTheChipNeverSendsAndSendInvokesOnSendOnce` (a tap on the chip
  sends nothing, and Send is one `onSend`, with the chip still on). The class KDoc no
  longer claims that the swarm flag reaches the send. It points to the JVM tests that
  cover it (`ChatViewModelTest` swarm-chip cases, `ChatRepositoryTest`
  "sendPrompt carries swarm_mode…").
- Docs: docs/conventions.md has a new Tests bullet with the harness's rules: the
  virtual Main clock for `viewModelScope` timers, trailing sentinels instead of
  margins, `UNDISPATCHED` SharedFlow collectors, cancel-and-join teardown, and
  semantics-first selectors with shared `internal const` tags.
- Gate: `./gradlew :app:testDebugUnitTest :app:compileDebugAndroidTestKotlin` is
  green (1098 tests, 0 failures, 1 skipped; forced re-run). The instrumented suite
  was not run in this step: the updated `SwarmModeChipTest`, `ComposerLayoutTest` and
  `ToolRowTest` media cases only compile here.

## Follow-up — 5-rest

*2026-11-07.* The 7 points the four fix steps left out (A1, A4, C7, C9, and the
review's three "Conflicts" items: Coil, `stringResource`, `runTest`). Skill
loaded: `android-dev`. Only A4 and C9 are fixed; the other five need a decision,
so they are left as **to be decided by Louis**, with the options.

- **A4 — done for the review's sites.** Deleted the KDoc that only restated the
  name plus a ticket number: `SessionSocket.watermark`, `CONTROL_TIMEOUT_MILLIS`,
  `ChatRepository.transcriptWatermark`. Cut the others to their contract:
  `SessionSocket.observeTaskOutput` and `ChatRepository.observeTaskOutput` now say
  only "replay-less: collect it BEFORE `watchTask`/`watchTaskOutput`", and
  `unwatchTaskOutput` only "fire-and-forget".
- **C9 — done for the review's sites.** Deleted the name-restating KDoc on
  `APPROVAL_PLAN_TAG`, `SWARM_MODE_CHIP_TAG`, the `onImageClick`/`loadImageBytes`
  parameters (`SubagentScreen`, both `ToolRows` signatures), the duplicated
  two-line call-site comment in `MessageList` (both copies) and the
  `ToolMediaIndicator` call-site comment in `ToolRows`. The why-comments stay (the
  media-always-expands rule, the hoisting rationale in `SubagentScreen`, the
  `TOOL_MEDIA_IMAGE_TAG` reason).
  **To be decided by Louis:** whether to make this a rule. The ticket-tagged,
  name-restating KDoc style is codebase-wide and neither `AGENTS.md` nor
  `docs/conventions.md` documents it; nothing outside the review's sites changed.
  Options: (a) add a "comments earn their place, the ticket number lives in
  `git log`" rule to `docs/conventions.md` and apply it as files are touched,
  (b) a one-off sweep, (c) keep the current style.
- **A1 — not done, to be decided by Louis.** Evicting a session's `tasks`,
  `taskOutputs` and `openConnections` (and `folds`/`watermarks`) on
  `SubagentStreamStore.release` is a short change, but it changes what the job
  screen shows for an ended task opened again after leaving its session: ended
  tasks are never re-listed, so it would fall back to the route seeds. Options:
  (a) evict on release and accept the seed fallback, (b) evict only ended tasks
  older than N, or cap the sessions kept (LRU), (c) keep it as is (the growth is
  small: a few strings per task).
- **C7 — modifier part done under C1, M3 part to be decided by Louis.** Since C1
  the swarm control is icon-only, so `FilterChip` (the review's fix) no longer
  fits. The M3 equivalent is now `IconToggleButton` with
  `iconToggleButtonColors(checkedContainerColor = secondaryContainer)`, which
  gives the 48dp target and toggle semantics. It changes the look (a 40dp circle
  instead of the 32dp rounded tile) and the role (Checkbox instead of Switch), and
  the sibling `PermissionModeChip`/`ModelChip` are hand-rolled too, so restyle the
  row together or not at all.
- **Coil — not done, to be decided by Louis.** A documented project choice
  (`HistoryImageThumbnail` + `loadImageBytes`, docs/conventions.md, media rows);
  C2's `ImageBytesCache` already fixes the re-download. Moving to Coil means a new
  dependency, a custom `Fetcher`/`Keyer` for session media, and rewritten
  thumbnail tests.
- **`stringResource` — not done, to be decided by Louis.** Hard-coded
  user-facing strings are the codebase-wide practice and the app is English-only;
  moving to resources is an app-wide change (and the instrumented tests match on
  text), worth it only if localisation is planned.
- **`runTest` — not done, to be decided by Louis.** The unit tests keep the
  real-I/O harness on purpose (real `MockWebServer`/WebSocket, real-clock waits;
  D1–D8 made it honest, see the Tests bullet in docs/conventions.md). Moving to
  `runTest` on one scheduler would mean replacing the real sockets with fakes.
