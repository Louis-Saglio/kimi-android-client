# Thinking-effort picker (per session)

- **ID:** 032
- **Created:** 2026-07-24
- **Depends on:** 031

## Context

Follow-up to the per-session model picker (031). Of the models the server
exposes, only K3 supports thinking effort
(`support_efforts: ["low","high","max"]`, `default_effort: "high"` — see
`GET /api/v1/models`); the K2.7 models expose none, so the effort picker's
options depend on the session's selected model — which is why this is a
separate ticket built on 031's per-session model state rather than bundled
into it.

The wire shape is **unverified**: `docs/server-api.md` lists an optional
`thinking` field among the per-prompt overrides on `POST .../prompts`, but
its exact shape was never verified live. `GET /api/v1/config` suggests
`{"enabled": true, "effort": "high"}`, and the session status endpoint
exposes `thinking_level` (e.g. `"high"`), which can serve as the
verification read-back.

Product decisions (confirmed with Louis, 2026-07-24):

- The effort selector lives as a **second section inside the model bottom
  sheet** from 031 — the top bar keeps a single chip.
- The current effort **is shown in the sheet** (selected state on the
  current value); it is not added to the top-bar chip.
- Effort is chosen **per session**, like the model; no stored value = don't
  send a `thinking` field (server default applies).

## Description

1. **Spike first, then document.** Against the live 0.29.0 server, pin down
   the `thinking` prompt field: create a throwaway session, send prompts
   with candidate shapes (start from `{"enabled": true, "effort": "low"}`),
   and read back `GET /api/v1/sessions/{id}/status` (`thinking_level`) and
   the `agent.status.updated` `thinkingEffort` WS event to confirm what the
   server actually honored. Also probe the failure modes: unknown effort
   value, and an effort sent for a model without `support_efforts`.
   (Pattern of tickets 013/017; note the server has no session-delete
   endpoint, so keep throwaway sessions to a minimum.)
2. **Document the API surface** in `docs/server-api.md`: the verified
   `thinking` prompt-field shape and accepted values, the observed error
   behavior, and the `support_efforts`/`default_effort` fields on
   `GET /api/v1/models` (031 adds the endpoint section; this ticket extends
   it with whatever the spike reveals). Keep
   `scripts/protocol-update-prompt.md` aligned if the doc's structure
   changes.
3. **Data layer:** persist the chosen effort per session ID in DataStore
   next to 031's per-session model. `ChatRepository.postPrompt` includes
   the verified `thinking` field when an effort is stored for the session;
   absent otherwise. Covers `sendPrompt` and `steer` through the same choke
   point.
4. **Validation:** the set of offered efforts comes from the selected
   model's `support_efforts` (031's models cache). If the session's stored
   effort is not in the current model's `support_efforts` (e.g. user
   switched from K3 to K2.7), ignore it — do not send it, and show no
   effort selection for that model. The effort section of the sheet is
   hidden entirely for models without `support_efforts`.
5. **UI:** in 031's `ModalBottomSheet`, below the model list, a "Thinking
   effort" section listing the current model's efforts with the selected
   state on the active one (and a "Default" hint on `default_effort` when
   nothing is stored). Tapping persists, dismisses, applies to subsequent
   prompts.

## Acceptance criteria

- [ ] Spike findings recorded in the ticket (or commit message) and
  `docs/server-api.md` updated: verified `thinking` field shape, accepted
  values, error behavior for invalid effort / unsupported model, and the
  models-endpoint effort fields.
- [ ] Unit test: with an effort stored for the session, the `POST /prompts`
  body contains the verified `thinking` payload — for `sendPrompt` and
  `steer`.
- [ ] Unit test: with no effort stored, no `thinking` field is sent.
- [ ] Unit test: stored effort incompatible with the session's model → not
  sent, and the ViewModel exposes no effort selection.
- [ ] `./gradlew testDebugUnitTest` passes.
- [ ] Live check: pick `low` on a K3 session, send a prompt, and the
  session status / `agent.status.updated` reports the low effort.
- [ ] APK built and copied to the file server per AGENTS.md.

## Out of scope

- Showing the effort on the top-bar chip (decided against — sheet only).
- Any effort UI for models without `support_efforts`.
- Changing effort semantics mid-turn (a new selection applies to the next
  prompt, like the model choice).
- Server-side or nightly-job changes beyond keeping
  `scripts/protocol-update-prompt.md` aligned with the doc.

## Notes

- Reuse 031's per-session DataStore pattern and its models cache — do not
  add a second models fetch.
- Session status read-back: `GET /api/v1/sessions/{id}/status` →
  `data.thinking_level`; live read-back: `agent.status.updated` WS event,
  model/usage variant, `thinkingEffort` field (0.29.0, docs/server-api.md).
