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

- [ ] The root cause of both symptoms is written up in this ticket's Notes.
- [ ] 50 consecutive `:app:testDebugUnitTest --rerun` full-suite runs with
  zero failures (script the loop; keep the output).
- [ ] The three recorder-based tests still assert the same emission
  sequences (no weakened assertions — diff review).
- [ ] `./gradlew :app:assembleDebug` succeeds; the rest of the suite is
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
