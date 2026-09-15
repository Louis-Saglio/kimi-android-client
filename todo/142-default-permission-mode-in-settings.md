# Default permission mode in server settings

- **ID:** 142
- **Created:** 2026-09-15
- **Depends on:** 141

## Context

The chat picker (ticket 141) sets the permission mode per session. The server
also has a daemon-wide default, `default_permission_mode` on
`GET/POST /api/v1/config` (verified live on 0.42.0 on 2026-09-15; currently
`yolo` on Louis's server). The web UI exposes it in desktop Settings with the
hint "Only affects newly-created sessions". Louis confirmed this belongs in
the same release as the chat picker.

This mirrors ticket 109 (server default model in Settings): server-wide
defaults live in Settings, not in the chat picker.

Depends on 141 for the shared label/description mapping and risk colors; the
row UI itself is independent.

## Description

1. Data layer:
   - Add `default_permission_mode: String? = null` to `ConfigData`
     (`data/chat/ChatDtos.kt:110`) and to the domain `ServerConfig`
     (`data/chat/ServerConfig.kt`).
   - Add `setDefaultPermissionMode(mode)` to `ModelsRepository`
     (`data/chat/ModelsRepository.kt`, next to `setDefaultModel` at `:71`):
     POST `/api/v1/config` with `{"default_permission_mode": "<mode>"}`,
     patch the cached config on success, follow the existing
     `Result`/`DataError` pattern. The existing `event.config.changed`
     invalidation (ticket 110) already covers external writes.
2. UI (`ui/settings/`):
   - New `SettingsRow("Default permission", "<label>")` under
     `ServerConfigSections` (`SettingsScreen.kt:188`), with the supporting
     text "Applies to new sessions".
   - Tap opens the same three-option chooser as the chat sheet (reuse the
     row content from ticket 141's `PermissionPickerSheet`; a shared
     composable or a second sheet instance, whichever is simpler).
   - Follow ticket 109's write UX (`SettingsViewModel.applyConfigWrite`,
     `:138`): the row moves only on success, failure shows a snackbar and
     leaves the UI unchanged.

## Acceptance criteria

- [ ] Settings shows the current `default_permission_mode` from
      `GET /api/v1/config` with the upstream label (e.g. "Ask When Needed"
      for `yolo`).
- [ ] Choosing "Always Ask" POSTs `{"default_permission_mode":"manual"}` and
      the row updates only after the server accepts.
- [ ] A failed write (e.g. airplane mode) shows a snackbar and the row keeps
      showing the old value.
- [ ] A session created after changing the default reports the new mode in
      `GET /status` `permission` (manual verification against the live
      server).

## Out of scope

- Per-session picker: ticket 141.
- `docs/server-api.md` updates: ticket 143.
- Editing any other `/api/v1/config` field.

## Notes

- Label mapping and descriptions: see ticket 141 Notes.
- The POST body is snake_case, matching the existing config writes.
