# Permission mode picker in chat

- **ID:** 141
- **Created:** 2026-09-15
- **Depends on:** none

## Context

The server supports a per-session permission mode (`manual`/`auto`/`yolo`)
that controls how often the agent asks for approval. The web UI exposes it as
a pill in the composer; the Android app has no way to choose it, so sessions
started from the phone always ride the server's default (`yolo` on Louis's
server). Louis wants the same control natively.

Decisions taken with Louis:

- Use the current upstream labels, not the wire enum names: `manual` =
  "Always Ask", `yolo` = "Ask When Needed", `auto` = "Never Ask". The mapping
  is not positional (yolo is the middle option because the dangerous-command
  guard still asks in yolo while auto gates nothing), so hardcode the mapping
  explicitly and never sort by enum name.
- Send the choice as the per-prompt `permission_mode` body field (verified
  live: it binds the session for its remaining turns), following the existing
  model/effort picker pattern. The web UI's session-profile confirmation
  protocol (`POST /sessions/{id}/profile` + one-shot echo tracking) was
  considered and rejected as more machinery than the app needs; per-prompt
  stamping covers new-session drafts and self-heals.
- The server-wide default in Settings ships in the same release (ticket 142).

## Description

Add a permission-mode picker to the chat screen, mirroring the model/effort
picker architecture (tickets 032/107/108):

1. Data layer:
   - Add `permission: String? = null` to `SessionStatusData`
     (`data/chat/ChatDtos.kt:80`) and surface it on the domain `SessionStatus`
     (`ChatApi.kt:334`).
   - Add a `permission_for_session_<id>` string key to
     `data/chat/SessionPrefsStore.kt` (absent = follow server).
   - Add `promptPermissionMode(sessionId)` to `ChatRepository` (session pref,
     else null) and pass it through the single `postPrompt` choke point
     (`data/chat/ChatApi.kt:139`) as the `permission_mode` body field,
     alongside `model`/`thinking`. Both `sendPrompt` and `steer` get it for
     free.
2. ViewModel (`ui/chat/ChatViewModel.kt`):
   - `UiState` gains the session's permission mode plus a
     `pendingPermissionPick` flag, same lifecycle as `pendingModelPick`
     (local pick wins until send success; `seedSessionStatus()` at `:1168`
     syncs the server-reported `permission` into the per-session store).
   - Intent `onPermissionModeSelected(mode)`: update `UiState` immediately,
     persist via the prefs store.
3. UI:
   - New `PermissionModeChip` in the `ChatInputBar` action row
     (`ui/chat/components/Composer.kt:56`), next to `ModelChip`: shield icon +
     short label, tinted by risk (Always Ask = default outline, Ask When
     Needed = `tertiary`, Never Ask = `error`).
   - New `PermissionPickerSheet` `ModalBottomSheet` in
     `ui/chat/components/`, cloned from `ModelPickerSheet`: three rows ordered
     Always Ask, Ask When Needed, Never Ask. Each row shows icon, label, and
     the description (see Notes), with a check on the current value. Tap
     selects and dismisses.
   - Wire through `ChatScreen.kt` with a `rememberSaveable` sheet flag,
     exactly like `showModelPicker` at `ChatScreen.kt:296`.

## Acceptance criteria

- [ ] The composer action row shows a permission chip reflecting the current
      session's effective mode after `seedSessionStatus()` runs (server
      `yolo` default shows "Ask When Needed" on a fresh session).
- [ ] Selecting a mode in the sheet persists it in DataStore and the chip
      updates immediately.
- [ ] A prompt sent after selecting "Always Ask" carries
      `"permission_mode":"manual"` in the POST body (unit test on the body
      builder or a logged request), and the session then surfaces approvals
      for tool calls.
- [ ] `steer` also stamps the picked mode.
- [ ] With no local pick, prompts omit `permission_mode` entirely.
- [ ] Killing and reopening the chat re-syncs from `GET /status` and a local
      pick that was never sent still wins.
- [ ] `./gradlew :app:assembleRelease` passes and the APK is copied to the
      file server per AGENTS.md.

## Out of scope

- Server-wide default permission row in Settings: ticket 142.
- `docs/server-api.md` updates (profile endpoint, `permission` status field,
  `default_permission_mode`): ticket 143.
- `POST /sessions/{id}/profile` writes: rejected in favor of per-prompt
  stamping (see Context).
- Decoding `session.status_changed` for live mode updates: no ticket yet;
  cold-start `/status` sync is enough for a single-client setup.
- Queued-prompt "most restrictive mode" merge (web UI behavior): the app
  sends one prompt at a time.

## Notes

- Labels and descriptions (from the 0.42.0 web bundle, en locale):
  - `manual` / Always Ask: "Auto-read only; everything else needs your
    approval first"
  - `yolo` / Ask When Needed: "Routine edits and commands run automatically;
    risky actions, questions, and plans still ask"
  - `auto` / Never Ask: "Never interrupts you; everything runs and is
    decided automatically"
- Icons used upstream: hand / shield-question / full-access. Material
  equivalents: `Icons.Outlined.PanTool` (or `BackHand`), `Shield`, and
  something like `Bolt` or `LockOpen` for Never Ask.
- M3: no confirmation dialog even for Never Ask (web parity); the `error`
  tint on chip and row carries the risk signal. Touch targets >= 48dp, rows
  merged for TalkBack with the icon `contentDescription = null`.
