# Session list: spinner for running, unread dot for finished turns

- **ID:** 145
- **Created:** 2026-09-16
- **Depends on:** none

## Context

The session row's trailing slot (`SessionsScreen.kt:627`) shows a blue dot when
`session.isRunning`. Verified against the web bundle served by the running
server (2026-09-16), that is not what the dot means upstream. The web
`SessionRow` computes one state with this priority:

```
approval/question pending → attention badge
busy                      → "running"   → 14px rotating SVG arc, accent color
lastTurnReason "failed"   → "aborted"   → danger badge
unread                    → "unread"    → 7px blue dot (class `unread-dot`)
else                      → timestamp
```

So on the web the blue dot is an **unread marker**: a session is marked when
its turn ends (reason `"completed"`; aborts and failures don't mark) while the
session is not open, and the mark clears when the session is opened. Unread is
purely client-side, persisted in `localStorage` under `kimi-web.unread` — the
server knows nothing about it. The app currently renders the web's "unread"
glyph for "working right now" and has no unread concept at all.

Decisions (discussion with Louis, 2026-09-16):

- Running gets a spinning wheel (`CircularProgressIndicator`), the dot moves
  to "turn finished, not yet viewed", matching the web's semantics and the
  web's priority order (attention > running > unread).
- Unread lives on the device in a per-server store, mirroring the web's
  `localStorage` behavior: survives process death, never sent by the server.
- The mark trigger is the list socket's `event.session.work_changed`: the
  frame already carries `last_turn_reason` (docs/server-api.md § WS), the app
  just doesn't decode it yet. Mark when a row that was running gets
  `busy: false` with `last_turn_reason == "completed"`.
- Clear trigger: opening the session, like the web's `selectSession`.

Alternatives rejected:

- `hasUnread` on `Session` + persisting it in `SessionListCache`: REST
  refreshes rebuild `Session` objects from DTOs, so the flag would drift from
  the store unless the join happens before every cache write. A
  `Set<String>` of unread ids on `UiState`, with the store as the single
  source of truth, has no drift and no cache schema change.
- Deriving unread from REST state (e.g. comparing `updated_at` to a last-seen
  stamp): diverges from the web, which only marks turn endings a client
  actually observes. Matching the web exactly is the point of this ticket.

## Description

**1. Decode the end reason** (`data/sessions/SessionDtos.kt`,
`data/sessions/SessionListSocket.kt`, `OkHttpSessionListSocket.kt`).
`WorkChangedPayload` gains
`@SerialName("last_turn_reason") val lastTurnReason: String? = null`;
`SessionListEvent.WorkChanged` gains `val lastTurnReason: String? = null`
(default keeps existing test constructions compiling); the OkHttp socket maps
it through.

**2. Unread store** (`data/sessions/UnreadSessionsStore.kt`). `@Singleton`,
same per-server JSON-file pattern as `SessionListCache` (file name = SHA-256
hex of the base URL, `filesDir`, corrupt file deleted and treated as empty):

- `fun unreadIds(serverKey: String): Flow<Set<String>>` — a
  `MutableStateFlow` per server key, lazily loaded from disk on first access.
- `suspend fun mark(serverKey: String, sessionId: String)` and
  `suspend fun clear(serverKey: String, sessionId: String)` — update the
  in-memory flow and write the file through (write failure logged, never
  thrown, like `SessionListCache.save`).
- No pruning: ids of archived sessions linger harmlessly, same as the web's
  `localStorage` map.

**3. ViewModel** (`ui/sessions/SessionListViewModel.kt`). Injected
`UnreadSessionsStore`; `UiState` gains `unreadIds: Set<String> = emptySet()`.

- Collect `store.unreadIds(serverUrl)` into the state (alongside the existing
  cache-load init).
- In `onSocketEvent`'s `WorkChanged` branch: before `patchRow`, look up the
  row's current `isRunning` in the active mode's list. If it was `true` and
  the event has `busy == false` and `lastTurnReason == "completed"`,
  `store.mark(serverUrl, sessionId)`. The `patchRow` call itself is unchanged.
- New `clearUnread(sessionId: String)` calling `store.clear`.

**4. Clear on open** (`ui/sessions/SessionsScreen.kt`, chat). The row click
path (`onSessionClick`, wired at `SessionsScreen.kt:154`) calls
`viewModel.clearUnread(session.id)` before navigating. Backstop for sessions
opened without a list tap (process-death restore straight into a chat):
`ChatViewModel`'s session-open init calls `store.clear` too — a no-op when the
session was never marked.

**5. Row** (`ui/sessions/SessionsScreen.kt`). `SessionRow` gains
`hasUnread: Boolean`; both call sites (flat list :409, grouped :459) pass
`session.id in uiState.unreadIds`. Trailing content, web priority order:

```kotlin
when {
    session.isAwaitingInteraction -> NeedsAttentionBadge()
    session.isRunning -> CircularProgressIndicator(
        Modifier.size(14.dp).semantics { contentDescription = "Session running" },
        strokeWidth = 2.dp,
    )
    hasUnread -> Box(
        Modifier.size(7.dp).clip(CircleShape)
            .background(MaterialTheme.colorScheme.primary)
            .semantics { contentDescription = "Session has unread activity" },
    )
}
```

The preview (:737) gains a third row with `hasUnread = true`.

**6. Tests.**

- Unit: new `UnreadSessionsStoreTest` (mark/clear round-trip, per-server
  isolation, flow emits on change, corrupt file → empty set).
- Unit: `SessionListViewModelTest` — running → `busy:false` + `"completed"`
  marks unread; same with `"cancelled"` does not; `clearUnread` removes it;
  existing `WorkChanged` constructions keep compiling (new field defaulted).
- androidTest: `SessionRowTest` — running renders the spinner (existing
  "Session running" description) and no dot; unread renders the dot; awaiting
  interaction still wins over both; running wins over unread.

## Acceptance criteria

- [ ] New and updated tests above pass; existing suite green.
- [ ] Observable on device/emulator: with the list visible, a turn run in
      another session (from the web UI or CLI) shows the spinner while running
      and the blue dot when it finishes. Opening that session clears the dot,
      and it stays cleared after swiping the app away and relaunching.
- [ ] Aborting a turn (POST `:abort` or the app's abort) produces no dot.
- [ ] `flock /tmp/kimi-android-gradle.lock ./gradlew :app:testDebugUnitTest
      :app:assembleDebug --max-workers=1 --no-daemon` passes; release APK
      published to the file server per AGENTS.md.

## Out of scope

- **Background connection — planned follow-up.** The list socket only runs
  while the list is visible (ON_START/ON_STOP gate, ticket 087), so a turn
  ending while the user is inside a chat or the app is backgrounded produces
  no event and no dot. The web has the same gap only when its tab is closed.
  Closing it needs a persistent app-level connection, implemented as a
  separate follow-up ticket after this one lands.
- The web's "aborted" danger badge (`lastTurnReason == "failed"`): a separate
  state the app doesn't surface anywhere yet; file its own ticket if wanted.
- The web's goal guard (no unread mark while a goal is active): the app has
  no goal concept; revisit when it grows one.
- Unread dots on the archived-sessions screen.

## Notes

- Web verification source: the live bundle (`/assets/index-*.js` on the
  running server), `SessionRow` component + the `W2` state function; unread
  store key `kimi-web.unread`; mark happens on settled turn end with reason
  "completed", cleared by `selectSession`.
- Wire facts: `event.session.work_changed` carries `busy`,
  `main_turn_active`, `pending_interaction`, `last_turn_reason`
  (`"completed" | "cancelled"`), docs/server-api.md § WS events.
- A background task completing mid-turn emits `busy:false` + `"completed"`
  immediately followed by `busy:true` (docs verbatim seq 86→88): the dot may
  flicker to the spinner and back. The web's settled-turn marking behaves the
  same way on task turns; accepted.
