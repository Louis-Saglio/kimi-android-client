# Harden the status-seed model/effort test against the models-cache race

- **ID:** 163
- **Created:** 2026-09-23
- **Depends on:** none

## Context

`ChatViewModelTest."status seed syncs the server-reported model and
effort into the session prefs"` (app/src/test/.../ui/chat/
ChatViewModelTest.kt:1454) flaked in at least three full-suite runs
during the 146–162 batch (tickets 154, 160, 148 each saw one failure,
always under load, always passing in isolation and in the final gate).

Root cause is structural in the test, not the production code: after
`onScreenStarted()` the test awaits `sessionModel` (set by the `/status`
seed) and the DataStore write-through, then sends and asserts
`"thinking":"low"` in the prompt body. But `ChatRepository.promptThinking`
(ChatRepository.kt:141-143) drops the stamp when
`models.cachedSupportEfforts` returns null — i.e. when the `/models`
fetch has not landed yet. Awaiting `sessionModel` does not imply the
models cache is warm: the `/status` and `/models` fetches race, and on a
loaded runner the models one loses. The sibling test "agent status frame
syncs the server-reported model and effort" does it right — it awaits
`uiState.modelsAvailable` before touching the send path.

## Description

**1. Fix the flaky test.** In the named test, await
`awaitState(viewModel.uiState) { it.modelsAvailable }` before
`onSend()`, mirroring the sibling test's pattern.

**2. Sweep the class.** Grep the test class for every test that asserts
a prompt-body `"thinking"`/model field after `onSend()` without awaiting
models availability first; fix each the same way.

**3. No production change.** The drop-on-cold-cache in `promptThinking`
is intentional (its comment says exactly that); the test was the bug.

## Acceptance criteria

- [ ] The named test (and any sibling with the same pattern) awaits
      models availability before the send assertion.
- [ ] 10 consecutive class runs green:
      `flock /tmp/kimi-android-gradle.lock ./gradlew :app:testDebugUnitTest
      --tests "fr.louissaglio.kimi.android.ui.chat.ChatViewModelTest"
      --rerun-tasks` (run 10 times; zero failures).
- [ ] `flock /tmp/kimi-android-gradle.lock ./gradlew :app:testDebugUnitTest
      :app:assembleDebug --max-workers=1 --no-daemon` passes; release APK
      published to the file server per AGENTS.md.

## Out of scope

- The `SessionListViewModelTest` `TimeoutCancellationException` flakes
  seen once in the 156 run — different class, different cause; ticket
  separately if they recur.
- The 5 s DataStore write-through poll pattern (documented and shared
  with the agent-status test; fine).
- Any change to `promptThinking`'s cold-cache behavior (deliberate).

## Notes

- Failure signature: the prompt body lacks `"thinking":"low"` (model
  assertion may still pass — model does not depend on the models cache).
- `modelsBody` is already set in the test; the missing piece is the
  await, not the fixture.
