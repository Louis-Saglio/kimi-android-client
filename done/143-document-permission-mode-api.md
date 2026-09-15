# Document the permission-mode API in server-api.md

- **ID:** 143
- **Created:** 2026-09-15
- **Depends on:** none

## Context

`docs/server-api.md` is the wire-protocol ground truth for the app and feeds
the nightly drift check. While planning tickets 141/142, two permission-mode
behaviors were verified live on 0.42.0 (2026-09-15) that the doc does not
cover:

1. `POST /api/v1/sessions/{id}/profile` accepts
   `{"agent_config":{"permission_mode":"manual"}}` and answers `code:0` with
   the full session object; a following `GET /status` reports
   `"permission":"manual"`. Verified with a throwaway session created and
   deleted for the probe.
2. `GET /api/v1/config` returns `default_permission_mode` (observed
   `"yolo"`). Whether `POST /api/v1/config` accepts a write is exercised by
   ticket 142; document the write once verified there.

The doc already mentions the per-prompt `permission_mode` field and the
`permission` status field, but never explains the CLI label rename mapping
(`manual` = "Always Ask", `yolo` = "Ask When Needed", `auto` = "Never Ask",
commit `08a2fb00`), which is non-positional and easy to get wrong.

## Description

Update `docs/server-api.md`:

1. Add a `### Session profile — POST /api/v1/sessions/{id}/profile`
   subsection under Sessions, with the verified request/response capture and
   the note that `agent_config.permission_mode` writes through to
   `/status`.
2. In the `/status` section, note that the app now consumes `permission`
   (ticket 141) and record the label-rename mapping next to the existing
   `08a2fb00` mention so future readers stop second-guessing the enum order.
3. In the `/api/v1/config` section, add `default_permission_mode` to the
   known fields; mark the POST write as verified once ticket 142 lands.

Keep the doc's existing style (dated "verified live" claims, throwaway
session hygiene).

## Acceptance criteria

- [ ] The profile endpoint is documented with a real 0.42.0 capture.
- [ ] The label mapping appears in the doc exactly once, referenced from the
      `/status` section.
- [ ] `default_permission_mode` is listed as a `/config` field.
- [ ] No claims beyond what was verified live are stated as fact.

## Out of scope

- App code changes: tickets 141/142.
- Re-running the full protocol verification; this is a targeted addition.
