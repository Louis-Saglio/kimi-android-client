# Cancel a queued prompt from its bubble

- **ID:** 195
- **Created:** 2026-11-12
- **Depends on:** none

## Context

Server 2.5.3 (re-verified live by the nightly drift run on 2026-11-13; the
route dates from 2.5.0) has `POST
/api/v1/sessions/{id}/prompts/{prompt_id}:abort`. Live today: a prompt queued
behind a running turn answered `{"aborted":true}` and left the queue
immediately; a second call for the same id, and an unknown id, answer `40402`
(`prompt … not found`, with `stack`). Withdrawing a queued prompt produces no
WebSocket frame — the queue snapshot simply drops it. On the running prompt
the route aborts it like `:abort`.

The app renders a queued bubble with its position ("Queued #N") but offers no
way to withdraw it: on a phone, a prompt queued by mistake can only be waited
out.

The 2.6.0-era version of this ticket depended on the turns migration (ticket
188); that protocol was withdrawn with the 2.6.x release, but the route itself
is unchanged on 2.5.3, so the work stands on its own.

## Description

- Add a cancel affordance to a queued prompt bubble (a small "cancel" action,
  consistent with the existing long-press/row actions).
- Call `ChatApi.abortQueuedPrompt(settings, sessionId, promptId)` →
  `POST .../prompts/{prompt_id}:abort` with `{}`.
- On `code:0`, remove the bubble locally: a queued withdrawal produces no
  frame.
- On `40402`, close the bubble as ended without an error snackbar (the prompt
  started or was withdrawn elsewhere).
- Keep the existing session-level `:abort` for the running turn; the bubble
  action for a running prompt may delegate to it.
- Do not send `prompts:steer` or any other request as part of the cancel.

## Acceptance criteria

- [ ] A queued bubble shows a cancel action; tapping it sends exactly one
      `POST .../prompts/{prompt_id}:abort` and removes the bubble.
- [ ] A `40402` response closes the bubble as ended with no error snackbar.
- [ ] The running bubble still uses the existing session `:abort` path.
- [ ] A MockWebServer test covers both the success and `40402` branches.
- [ ] Live against 2.5.3: queue a prompt behind a slow turn, cancel it, and
      `GET .../prompts` no longer lists it while the running turn continues.

## Out of scope

- Reordering the queue.
- Cancelling several prompts in one action.
- Any change to how queued prompts are seeded into the timeline.

## Notes

- `docs/server-api.md` → Prompts → Send (per-prompt actions), and the
  Queue snapshot route.
