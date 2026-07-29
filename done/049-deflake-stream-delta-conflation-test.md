# De-flake the stream-delta conflation test

- **ID:** 049
- **Created:** 2026-07-29
- **Depends on:** none

## Context

`ChatViewModelTest.stream deltas publish the leading edge then coalesce until
a substantive event flushes` fails intermittently in full-suite runs, roughly
1 run in 4-6. Proven pre-existing: during ticket 048's verification the
failure reproduced at the pre-change baseline (`git stash`, 2 failures in 6
runs), so it is NOT a 048 regression.

Two observed symptoms, same test:

- `expected:<[Hello, Hello world!]> but was:<[Hello]>` — the trailing flush
  emission never reaches the recorder.
- `expected: Arrays$ArrayList<[Hello, Hello world!]> but was:
  CopyOnWriteArrayList<[Hello, Hello world!]>` — identical CONTENTS, failed
  equals: the recorder's `CopyOnWriteArrayList` was appended to concurrently
  with the assertion's `equals` traversal.

Both symptoms prove the same thing: the terminal `assertEquals` races the
`assistantTextRecorder` (a `Dispatchers.Unconfined` collector appending to a
shared list from whichever thread the StateFlow resumes it on). The harness
comment claims "the collector never suspends on work, so no emission is
conflated away" — that holds only if the collector's resumption is timely,
which multi-threaded scheduling does not guarantee: the assertion thread has
no happens-before edge with the last recorder append.

Once observed in a failing run: `Dispatchers.Main is used concurrently with
setting it`. Worth checking while there: `tearDown` resets Main and shuts
down the MockWebServer but never cancels the ViewModel's `viewModelScope`,
so a socket-collect/reconnect coroutine can outlive its test and still be
executing when the NEXT test class calls `Dispatchers.setMain`.

Why now: every ticket's acceptance runs this suite; the flake failed 048's
verification repeatedly and forced baseline double-runs to prove innocence.
A suite that cries wolf teaches the user to ignore red builds.

Agreed scope (Louis, 2026-07-29): fix the flake at the harness level without
weakening what the tests assert.

## Description

- Root-cause the recorder race first (suspects above: Unconfined resumption
  timing, StateFlow conflation under a busy collector, missing
  happens-before between the last append and the assertion thread) and write
  it up in this ticket's Notes. The same `assistantTextRecorder` pattern is
  used by three tests (`stream deltas publish…`, `turn ended flushes the
  accumulated text…`, `window zero publishes every delta on arrival`) — fix
  the pattern, not just the one observed failure.
- Fix direction, in preference order:
  1. Make the recorder deterministic — e.g. collect on the same test
     scheduler as the emissions, or have assertions AWAIT the expected
     sequence (`awaitCondition { texts == expected }`) instead of reading a
     shared list at an arbitrary instant, while keeping a hard timeout so a
     genuinely lost emission still fails the test.
  2. If that is structurally impossible, replace the recorder with direct
     `awaitState` assertions on `uiState` (the sequence property then comes
     from successive awaited states, not a side channel).
- Assertions must not be weakened: no sleeps, no retry-until-pass, no
  tolerance for missing emissions — the tests exist to prove per-window
  publishing order.
- Also check the `Dispatchers.Main is used concurrently with setting it`
  angle: if leaked `viewModelScope` coroutines are implicated, cancel them
  in `tearDown` (e.g. clear the ViewModel) rather than masking the warning.
- If the investigation uncovers a REAL race in the production
  delta-conflation path (not just the test harness), note it here and fix it
  in this ticket only if the diff stays small; otherwise file a follow-up.

## Acceptance criteria

- [x] The root cause of both symptoms is written up in this ticket's Notes.
- [x] 50 consecutive `:app:testDebugUnitTest --rerun` full-suite runs with
  zero failures (script the loop; keep the output).
- [x] The three recorder-based tests still assert the same emission
  sequences (no weakened assertions — diff review).
- [x] `./gradlew :app:assembleDebug` succeeds; the rest of the suite is
  untouched and green.

## Out of scope

- Migrating the suite to Turbine or `runTest` wholesale — only reconsider if
  the minimal fix proves impossible, and as its own ticket then.
- Production behavior changes to the conflation window itself (100 ms
  default, injectable) — the flake is a test-harness bug unless proven
  otherwise.
- Other pre-existing suite hygiene (leaked coroutines beyond the Main
  warning angle above).

## Notes

- The recorder: `ChatViewModelTest.assistantTextRecorder` (collects
  `uiState` on `Dispatchers.Unconfined` into a `CopyOnWriteArrayList`);
  failing assertion sites: the three tests listed above.
- Frequency data: 048 verification batches — 3/6, then 1/8, then 3/15
  full-suite runs failed, always this test; pre-change baseline 2/6.
- Ticket 048's Notes (done/048-chat-resync-after-backgrounding.md) flagged
  this flake and proposed this ticket.
- The suite deliberately runs on real time (real socket I/O) with
  `UnconfinedTestDispatcher` as Main; determinism today comes from the
  startup-quiet gate, not from the scheduler — keep that philosophy.

## Root cause (proven 2026-07-29 by instrumented reproduction)

Mechanism, verified against kotlinx-coroutines 1.7.3 bytecode and a
reflection dump of the live `StateFlowImpl` in a failing run:

1. The ViewModel's socket collect runs on `viewModelScope` (Main =
   `UnconfinedTestDispatcher`). Each WS frame resumes it INLINE on the
   OkHttp reader thread (`resumeCancellableWith` → `executeUnconfined`),
   which marks that thread's unconfined event loop ACTIVE for the whole
   frame-processing window.
2. The recorder (launched on `Dispatchers.Unconfined`) is resumed by
   `_uiState.update`'s `StateFlowSlot.makePending` INSIDE that window.
   `executeUnconfined` sees the active loop and ENQUEUES the collector to
   the reader thread's `unconfinedQueue` (stack-overflow avoidance) instead
   of running it inline.
3. The queued collector runs only when the frame processing unwinds and
   `runUnconfinedEventLoop` drains the queue (after the collect suspends
   back into `signals.receive()`), and then processes the LATEST state —
   queued resumes coalesce.
4. The test thread's `awaitState` sees `pendingApprovals` the instant the
   approval CAS lands; the recorder's `"Hello world!"` append lands µs–ms
   later at the drain. The terminal `assertEquals` read the shared list in
   between → symptom 1 (`[Hello]`). Symptom 2 (equals mismatch with
   identical toStrings) is the same race caught mid-drain: the append
   landed between the failed `equals()` and the failure-message formatting.

Evidence from the failing instrumented run (`/tmp/ticket049/failing4_run_8.xml`):
`flush publishing 1` logged on the OkHttp reader thread, final state text
correct (`Hello world!`), recorder job active, and the recorder's slot
`_state` == `Symbol` (PENDING) — its resume had been consumed into the
queue, not delivered. Production published every state in the right order
on the one reader thread, every time.

The harness comment "the collector never suspends on work, so no emission
is conflated away" was therefore wrong twice over: resumes are NOT inline
whenever the publisher itself runs inside an Unconfined resume, and queued
resumes coalesce (masked here only because the dedupe-by-text sees the
accumulated text in the latest state).

Ruled out during the investigation:

- The 100 ms flush timer racing the approval: it never fires in this
  harness — `delay` is scheduled on an unpumped `TestCoroutineScheduler`
  (`UnconfinedTestDispatcher()` creates one per call; nothing advances it).
- A REAL production race: none. Production confines the socket collect,
  the flush loop and `onScreenStopped` to a real Main dispatcher; the
  inline-resume-on-an-OkHttp-thread behavior is an artifact of
  `UnconfinedTestDispatcher` in tests. No production change made.

## Fix

- The three recorder tests now AWAIT the full expected sequence
  (`awaitCondition { texts == ... }`, 5 s hard timeout) instead of reading
  the shared list at an arbitrary instant — same emission sequences
  asserted, so any genuinely lost or extra emission still fails. The
  append is deterministic: the queue always drains at frame unwind and the
  latest state at drain time carries the full accumulated text.
- `Dispatchers.Main is used concurrently with setting it` angle: leaked
  `viewModelScope` coroutines confirmed implicated. Baseline evidence:
  system-err crash "Module with the Main dispatcher had failed to
  initialize" from `SessionSocket.onFailure` resuming a leaked collect
  after `resetMain`. tearDown now runs
  `runBlocking { startedViewModels.forEach { it.viewModelScope.coroutineContext.job.cancelAndJoin() } }`
  BEFORE `resetMain`/`server.shutdown` — cancel alone was not enough (an
  in-flight blocking OkHttp `execute()` is not cancellable and resumed its
  parent after `resetMain` → `CoroutinesInternalError`; joining while Main
  is still set and the server still up lets those resumes complete).
- Verification: 50 consecutive `:app:testDebugUnitTest --rerun` full-suite
  runs, loop scripted at `/tmp/ticket049/acceptance/loop50.sh`, per-run
  logs alongside it — result: 50/50 green (2026-07-29), logs in
  /tmp/ticket049/acceptance/, no stderr noise in any run.
