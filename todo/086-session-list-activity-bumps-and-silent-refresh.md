# Session list: local activity bumps and silent refresh

- **ID:** 086
- **Created:** 2026-08-02
- **Depends on:** none

## Context

Product discussion with Louis (2026-08-02). Two reported behaviors, one design:

1. Create a session from the list → navigate to its chat → come back: the new
   session is missing until the automatic list reload finishes. Cause: the
   create POST response (a full `Session`) is only used for navigation
   (`Effect.SessionCreated`) and never inserted into the list
   (`SessionListViewModel.onCreateSessionConfirm`).
2. Louis does not want the sessions list to *visibly* reload automatically.
   Today every `ON_START` (back from chat/settings, app foregrounded) runs
   `reload()` with the pull-to-refresh spinner showing
   (`SessionsScreen.kt:94` → `SessionListViewModel.reload()`).

Staleness tradeoff discussed: the ON_START reload is the only updater of the
running dot (011), the approval badge (016), and web-client-side changes.
Louis chose a **silent background refresh** over accepting staleness: keep
refreshing on return, but with no spinner and no visible disturbance. Zero
background requests was explicitly offered and declined.

Bump rule discussed: Louis rejected bump-on-tap — a session opened but not
messaged would jump to the top and then be put back by the silent refresh,
a visible jump for nothing. A session is bumped **only if a user message was
successfully sent in it**; since a sent message also bumps `updatedAt`
server-side, the silent refresh then lands with the same ordering — no jump
back.

Alternatives considered and rejected: (a) no auto-refresh at all (accept
staleness) — declined, badges would go stale; (b) bump-on-tap — rejected by
Louis per above; (c) a settings-fingerprint-guarded reload — moot, every
return refreshes anyway.

## Description

**Create → instant row** (`SessionListViewModel.onCreateSessionConfirm`):
on success, insert the returned `Session` at the top of `sessions` (dedup by
id — replace if somehow already present), then cancel `loadJob`/`loadMoreJob`
so a stale in-flight page landing after the insert cannot drop the new row.
The `Effect.SessionCreated` navigation is unchanged.

**Activity recording** (data layer): new `SessionActivityStore` in
`data/sessions` — a process-lifetime singleton holding pending
`sessionId → Instant` entries (`record`, `consumeAll`). `ChatRepository`
records on **success** of `sendPrompt` and `steer` (the only two user-message
paths; both funnel through `ChatViewModel.onSend`,
`ChatViewModel.kt:611-623`). Recording on success, not attempt: a failed send
must not bump a session the server doesn't consider active.

**Bump on return** (`SessionListViewModel.onScreenStarted`): consume the
store's pending entries and apply them **synchronously, before** the refresh
fires — bumped sessions move to the top of the loaded list (most recent
activity first) with `updatedAt` set to the recorded instant. Entries whose
session is not in the currently loaded page are dropped (the refresh will
place them via server ordering). No activity recorded → the list is
byte-identical on return.

**Silent refresh** (`SessionListViewModel`): split `reload()` into two paths.
- `sessions == null` → the visible initial load (spinner, error state) as
  today; `onRetry` keeps this behavior.
- `sessions != null` (ON_START) → background refresh: never sets `isLoading`
  or `isRefreshing`; on success replaces `sessions` + `hasMore`; on failure
  keeps the old list and emits **no** `RefreshFailed` effect (no snackbar on
  every return while offline).
- `onPullToRefresh` keeps today's explicit behavior: `isRefreshing` spinner +
  `RefreshFailed` snackbar on failure.

## Acceptance criteria

- [ ] After creating a session and returning from its chat, the new session is
  visible at the top of the list immediately, before any refresh completes
  (unit-tested: create success inserts into `uiState.sessions` at position 0).
- [ ] A refresh in flight when a create succeeds cannot drop the inserted
  session (unit-tested: the in-flight job is cancelled / its stale page is
  discarded).
- [ ] ON_START with a loaded list emits no `isRefreshing`/`isLoading`; on
  success the list is replaced; on failure the old list stays and no
  `RefreshFailed` effect is emitted. Pull-to-refresh still sets `isRefreshing`
  and emits `RefreshFailed` on failure (unit-tested in
  `SessionListViewModelTest`).
- [ ] A successfully sent prompt (and a steer) bumps the session to the top
  with the recorded timestamp when returning to the list; a failed send does
  not (unit-tested at `ChatRepository`/store level and
  `SessionListViewModelTest`).
- [ ] With several bumped sessions, their order is most-recent-activity first
  (unit-tested).
- [ ] `./gradlew :app:assembleDebug` succeeds with all unit tests green (VPS
  rules: `flock /tmp/kimi-android-gradle.lock`, `--max-workers=1`,
  `--no-daemon` when parallel).

## Out of scope

- WebSocket-driven live status in the list (busy dot / approval badge updating
  in real time via `event.session.work_changed`, the follow-up flagged in
  ticket 016's notes) — discussed 2026-08-02 as the next product topic; its
  own ticket if confirmed.
- Animated row reordering on refresh.
- Persisting recorded activity across process death (after process death the
  initial load returns server truth anyway).
- Treating approvals, question answers, or aborts as bump triggers — they are
  not user messages; revisit only if Louis reports wanting it.

## Notes

- Key files: `ui/sessions/SessionListViewModel.kt` (reload split, insert,
  bump), `ui/sessions/SessionsScreen.kt` (ON_START wiring — no change
  expected), `data/chat/ChatRepository.kt` (record on send success),
  `data/sessions/` (new `SessionActivityStore` next to
  `SessionsRepository.kt`).
- `SessionListViewModelTest` already exists with a MockWebServer harness;
  chat-side recording can be asserted on the shared `ChatViewModelTestBase`
  harness or at repository level — implementer's choice.
- Scroll stability on the silent replace comes for free: `SessionList` keys
  rows by `Session::id` (`SessionsScreen.kt:294`).
