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
returned `config:null`. The 2.3.1 drift run (2026-10-23) found the gate live.
**Server 2.3.3 (2026-10-28, upstream commit `f895f305`) extended the live
schema again:** every rule now carries a required `session_type` (`old`,
`new`, or `all`), `from` and `to` accept `*` as a wildcard, and a rule may
carry an English `warning` in addition to `hint`. The live config now gates
the K3.1 Pro Highspeed price warning for new sessions and the quality warning
for existing sessions. The live config capture is in `docs/server-api.md`
under Client configs.

Chat currently commits a picker tap immediately in
`ChatViewModel.onModelSelected`. There is no client-config DTO or fetch. The
upstream TUI gates an idle, real model switch. It labels a session `old` once
the conversation has at least one real user message, otherwise `new`, and
matches a rule whose `session_type` is `all` or equal to that label. Rules
match upstream model IDs, not the aliases used by prompt requests and the
Android picker. The app's config projection currently drops the `models` map
needed to resolve an alias to that upstream ID.

The phone flow needs a small Continue/Cancel confirmation, not the TUI's
compact-or-continue choice. Settings only changes the server-wide default and
does not switch an open conversation, so it stays immediate.

## Description

Add a repository call for `POST /api/v1/client_configs` with
`{"name":"model_switch_confirm"}`. Decode nullable `config`, `enable`, and
rules whose `from`/`to` values are either one string or a non-empty string
array, with required `session_type` (`old`, `new`, or `all`), optional
`hint.en`, and optional `warning.en`. Decode enough of `GET /api/v1/config`'s
`models` map to resolve each picker alias to its upstream `model` ID.

When the chat is idle and the user picks a different model, derive the session
type from conversation history: `old` once a real user turn exists, otherwise
`new`. Match the current and target upstream IDs against the first enabled
rule whose `session_type` is `all` or equal to that type. Either side of a
rule may be an array, and `*` matches any upstream ID. A match opens a
confirmation dialog showing the two model names, the optional English hint,
and the optional English warning. Continue uses the existing
`onModelSelected` path. Cancel leaves UI state, stored preferences, and server
config untouched.

Fetch the named config when loading the chat's model data. A null config, a
disabled config, missing alias metadata, or a fetch/decode failure preserves
today's immediate selection. Do not block prompt sending on this optional
gate.

## Acceptance criteria

- [ ] Unit tests cover single-string and array rules, `*` on either side,
      `session_type` `old`/`new`/`all`, disabled and null configs, and
      nonmatching rules.
- [ ] An enabled matching rule opens the dialog only for a different model in
      an idle chat; the live `new` rule also gates a chat before its first
      turn, and the live `old` rule gates one with history.
- [ ] `hint.en` and `warning.en` are visible when supplied.
- [ ] Continue persists the requested model through the existing path.
      Cancel performs no preference write, config write, or prompt POST.
- [ ] Null or unavailable config preserves immediate model selection.
- [ ] `:app:testDebugUnitTest` passes.

## Out of scope

- The TUI's compact-before-switch action for existing sessions.
- Settings' server-wide default-model picker, re-picking the current model,
  and switches while a turn is running.
- Effort switching prompts and descriptions, covered by ticket 170.
- Upgrade and retirement guidance, covered by ticket 166.
- Persisting the cloud config or inventing a TTL. The server fetches it fresh
  on each request.

## Notes

Relevant code: `data/chat/ChatDtos.kt`, `ModelsRepository.kt`,
`ChatRepository.kt`, `ui/chat/ChatViewModel.kt`, and
`ui/chat/components/ModelPickerSheet.kt`. The matching and session-type rules
come from `apps/kimi-code/src/utils/model-switch-confirm-config.ts` and
`apps/kimi-code/src/tui/utils/model-switch.ts` at server 2.3.3. The server
requires `session_type`; rules missing it are dropped during parsing. The live
config and a verbatim capture are in `docs/server-api.md` under Client configs.
