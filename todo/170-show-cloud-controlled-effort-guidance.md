# Show cloud-controlled effort guidance in the model sheet

- **ID:** 170
- **Created:** 2026-10-28
- **Depends on:** 167

## Context

Found by the nightly server drift run from 2.3.1 to 2.3.3 on 2026-10-28.
Server 2.3.3, upstream commit `0051ef10`, registers a second client config,
`effort_switch_confirm`. It carries English and Chinese descriptions for each
thinking effort plus optional confirmation rules for effort changes. The live
server returns an enabled config with descriptions for `low`, `high`, and
`max`, and one `old`-session rule from `*` to `*` warning that changing effort
can affect output quality, invalidate the prompt cache, and consume more
tokens. The live response capture is in `docs/server-api.md` under Client
configs.

The Android picker currently shows bare effort segments in
`ModelPickerSheet` and commits a tap immediately through
`ChatViewModel.onEffortSelected`. On a phone the three one-word labels give no
clue what `max` costs or when `low` is appropriate, and an accidental tap
during a long conversation can silently discard the prompt cache. Upstream
shows these descriptions only for the managed Kimi provider, and gates an
effort change only while idle.

This is separate from ticket 167 because it has a different config name, a
different payload, and a different trigger. Ticket 167 establishes the shared
client-config fetch; this ticket reuses it instead of adding a second HTTP
path or cache.

## Description

Extend the client-config layer from ticket 167 with
`POST /api/v1/client_configs` named `effort_switch_confirm`. Decode
`descriptions.enable`, `descriptions.items` keyed by effort, and
`confirm.enable` plus the same rule shape used by the model config: string or
non-empty string arrays for `from`/`to`, `*` as a wildcard, required
`session_type` (`old`, `new`, `all`), and optional `hint.en` / `warning.en`.

When the current model's provider is `managed:kimi-code` and descriptions are
enabled, show the English description for the active effort in the picker,
below the segmented row. Refresh the config with the rest of the chat's model
data; do not apply Chinese strings.

When the user picks a different effort for the same model while idle, derive
the session type from conversation history (`old` once a real user turn
exists, otherwise `new`) and match the first enabled rule whose `session_type`
is `all` or equal to that type. A match opens a small confirmation dialog
showing the old and new effort, the optional hint, and the optional warning.
Continue calls the existing `onEffortSelected` path. Cancel leaves UI state,
stored preferences, and the server untouched.

A null config, disabled sections, missing provider/model metadata, or a
fetch/decode failure leaves today's picker and immediate effort selection
unchanged. Do not block prompt sending on this optional gate.

## Acceptance criteria

- [ ] Unit tests cover the `effort_switch_confirm` payload, wildcard and array
      rules, all three `session_type` values, disabled sections, and null or
      malformed configs.
- [ ] For a `managed:kimi-code` model with descriptions enabled, the active
      effort shows its English description; no description renders for other
      providers or when descriptions are disabled.
- [ ] An enabled matching `old` rule opens Continue/Cancel for an effort
      change in a chat with history; a `new` rule does the same before the
      first turn.
- [ ] `hint.en` and `warning.en` are visible when supplied.
- [ ] Continue persists the effort through `onEffortSelected`; Cancel performs
      no preference write or config write.
- [ ] Nonmatching rules and unavailable config preserve immediate effort
      selection.
- [ ] `:app:testDebugUnitTest` passes.

## Out of scope

- Model switches and their separate config, covered by ticket 167.
- Settings' server-wide default-effort picker and effort changes while a turn
  is running.
- New-session or compact-before-switch actions.
- Chinese localization. The app currently uses English UI strings and already
  reads the English arm of server hints.
- Persisting the cloud config or inventing a TTL. The server fetches it fresh
  on each request.
- Provider-supplied descriptions for non-managed models.

## Notes

Relevant code: `data/chat/ChatDtos.kt`, `ModelsRepository.kt`,
`ChatRepository.kt`, `ui/chat/ChatViewModel.kt`, and
`ui/chat/components/ModelPickerSheet.kt`. The provider gate and matching rules
come from `apps/kimi-code/src/tui/commands/config.ts` and
`apps/kimi-code/src/utils/effort-switch-config.ts` at server 2.3.3. The live
effort config and its descriptions are recorded in `docs/server-api.md` under
Client configs.
