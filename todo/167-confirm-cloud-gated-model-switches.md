# Confirm cloud-gated model switches in chat

- **ID:** 167
- **Created:** 2026-10-22
- **Depends on:** none

## Context

Found by the nightly server drift run from 2.2.0 to 2.3.0 on 2026-10-22.
Server 2.3.0, upstream commit `3f80896a79c5`, adds
`POST /api/v1/client_configs` with the registered name
`model_switch_confirm`. An enabled rule can ask clients to confirm a model
switch before existing conversation context is sent to a different model,
which loses the prompt cache and can change output quality. The 2.3.0 probe
returned `config:null`. **Update from the 2.3.1 drift run (2026-10-23): the
gate is now live** — the probe answers `{enable:true, rules:[k3/k3-256k/
kimi-for-coding(+-highspeed) → k3.1-pro(+-highspeed)]}` with bilingual hints
(verbatim capture in `docs/server-api.md` → Client configs), so this ticket is
no longer theoretical.

Chat currently commits a picker tap immediately in
`ChatViewModel.onModelSelected`. There is no client-config DTO or fetch. The
upstream TUI gates only a real switch in a session with history, while idle.
Its rules match upstream model IDs exactly, not the aliases used by prompt
requests and the Android picker. The app's config projection currently drops
the `models` map needed to resolve an alias to that upstream ID.

The phone flow needs a small Continue/Cancel confirmation, not the TUI's four
actions. Settings only changes the server-wide default and does not switch an
open conversation, so it stays immediate.

## Description

Add a repository call for `POST /api/v1/client_configs` with
`{"name":"model_switch_confirm"}`. Decode nullable `config`, `enable`, and
rules whose `from`/`to` values are either one string or a non-empty string
array, with optional `hint.en`. Decode enough of `GET /api/v1/config`'s
`models` map to resolve each picker alias to its upstream `model` ID.

When the chat is idle, has conversation history, and the user picks a
different model, match the current and target upstream IDs against the first
enabled rule. Matching is exact, with no wildcard behavior; either side of a
rule may be an array. A match opens a confirmation dialog showing the two
model names and the optional English hint. Continue uses the existing
`onModelSelected` path. Cancel leaves UI state, stored preferences, and server
config untouched.

Fetch the named config when loading the chat's model data. A null config, a
disabled config, missing alias metadata, or a fetch/decode failure preserves
today's immediate selection. Do not block prompt sending on this optional
gate.

## Acceptance criteria

- [ ] Unit tests cover single-string and array rules, exact matching, disabled
      and null configs, and the absence of wildcard matching.
- [ ] An enabled matching rule opens the dialog only for a different model in
      an idle chat with history; its `hint.en` text is visible when supplied.
- [ ] Continue persists the requested model through the existing path.
      Cancel performs no preference write, config write, or prompt POST.
- [ ] Null/unavailable config and nonmatching rules preserve immediate model
      selection.
- [ ] `:app:testDebugUnitTest` passes.

## Out of scope

- New-session and compact-before-switch actions offered by the TUI.
- Settings' server-wide default-model picker, effort-only changes, re-picking
  the current model, and switches while a turn is running.
- Upgrade and retirement guidance, covered by ticket 166.
- Persisting the cloud config or inventing a TTL. The server fetches it fresh
  on each request.

## Notes

Relevant code: `data/chat/ChatDtos.kt`, `ModelsRepository.kt`,
`ChatRepository.kt`, `ui/chat/ChatViewModel.kt`, and
`ui/chat/components/ModelPickerSheet.kt`. The exact matching and history/idle
rules come from `apps/kimi-code/src/utils/model-switch-confirm-config.ts` and
`apps/kimi-code/src/tui/commands/config.ts` at server 2.3.0.
