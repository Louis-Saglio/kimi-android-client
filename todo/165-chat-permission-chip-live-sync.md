# Sync the chat permission chip from agent.status.updated

- **ID:** 165
- **Created:** 2026-10-14
- **Depends on:** none

## Context

Found by the nightly server drift run (2.1.1 → 2.2.0, run of 2026-10-14;
see `docs/server-api.md` "drift from 2.1.1 to 2.2.0"). Server 2.2.0 (upstream
commit `a940f2ff`) adds a third `agent.status.updated` variant: the payload
gains an optional `permission` field (`"manual"`/`"yolo"`/`"auto"`) and the
server pushes the frame the moment the session's permission mode changes —
including when another client writes it via
`POST /api/v1/sessions/{id}/profile {"agent_config":{"permission_mode":...}}`.
Verified live against the running server: the profile write pushed
`{"type":"event","event":"agent.status.updated","data":{"permission":"manual"},...}`
on the session socket.

Today the chat permission chip only learns the mode from the `/status` seed
when the screen opens (`ChatViewModel.seedSessionStatus` →
`syncServerSessionPermission`, app/src/main/java/fr/louissaglio/kimi/android/
ui/chat/ChatViewModel.kt:1336). If the mode changes server-side while the
chat is open — Louis flips it from the web client, or a goal/compact flow
changes it — the phone chip shows the stale value until the screen is
recreated.

## Description

**1. Decode the new field.** Add `val permission: String? = null` to
`AgentStatusUpdatedPayload` (app/src/main/java/fr/louissaglio/kimi/android/
data/chat/ChatDtos.kt:122), same additive pattern as `model`/`phase`.

**2. Map it in the socket.** In the `agent.status.updated` branch of
`SessionSocket` (app/src/main/java/fr/louissaglio/kimi/android/data/chat/
SessionSocket.kt:320), emit a new `SessionEvent` (e.g.
`SessionPermissionUpdated(permission, agentId)`) when `payload.permission`
is non-blank — mirroring the existing model/effort block right below.

**3. Apply it in the view model.** Handle the event in `ChatViewModel` by
routing through the same guard as the seed (`syncServerSessionPermission`,
ChatViewModel.kt:1336): update `uiState.sessionPermission` only when no
local pick is in force (`!pendingPermissionPick && storedPick == null`).
The sticky-pick design of ticket 141 stands — when a pick is stored, the
app re-asserts it per prompt, so showing the server's value would be
misleading. Read the stored pick once when the event first arrives (it is
already read at seed time; keep a cached copy or re-read from the store).

**4. Tests.** Extend `ChatViewModelTest`: an `agent.status.updated` frame
carrying `permission` with no stored pick updates the chip; with a stored
pick it does not. Mirror the existing agent-status/model sync tests'
fixture style.

## Acceptance criteria

- [ ] `AgentStatusUpdatedPayload` decodes the `permission` field; unknown
      absence still yields null (no regression on the other two variants —
      existing tests stay green).
- [ ] With the chat open and no local permission pick, a server-side mode
      change (e.g. a profile write from another client) updates the chip
      without recreating the screen.
- [ ] With a local pick stored, the same frame leaves the chip unchanged.
- [ ] New unit tests for both cases pass, plus the full gate:
      `flock /tmp/kimi-android-gradle.lock ./gradlew :app:testDebugUnitTest
      :app:assembleDebug --max-workers=1 --no-daemon`; release APK
      published to the file server per AGENTS.md.

## Out of scope

- Switching the app to the session-profile endpoint for writes (ticket 141
  deliberately chose per-prompt stamping; this ticket is read-only sync).
- Any UI change to the chip itself (ticket 142 already shipped the picker).
- Seeding changes: the `/status` seed path stays as is.
- Reacting to `permission` on sessions other than the open one (session
  list badges etc.) — no such display exists today.

## Notes

- The frame carries no token/model fields on this variant, so the existing
  null-guards in the mapper already prevent clobbering; only the new event
  emission is needed.
- Verbatim captured frame is in `docs/server-api.md` (drift section and the
  `agent.status.updated` core-payloads entry).
