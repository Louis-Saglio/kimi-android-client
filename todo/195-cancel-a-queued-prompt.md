# Cancel a queued prompt from its bubble

- **ID:** 195
- **Created:** 2026-11-12
- **Depends on:** 188

## Context

Server 2.6.0 (nightly drift run) keeps `POST
/api/v1/sessions/{id}/prompts/{prompt_id}:abort`. On a queued prompt it
withdraws it from the queue with no turn and no frame; on the running prompt it
acts like `:abort`; an ended/unknown prompt answers `40402`. The app renders a
queued bubble with its position ("Queued #N") but offers no way to withdraw it:
on a phone, a prompt queued by mistake can only be waited out.

## Description

- Add a cancel affordance to a queued prompt bubble (a small "cancel" action,
  consistent with the existing long-press/row actions).
- Call `ChatApi.abortQueuedPrompt(settings, sessionId, promptId)` →
  `POST .../prompts/{prompt_id}:abort` with `{}`.
- On `code:0`, remove the bubble locally: a queued withdrawal produces no
  frame.
- On `40402`, close the bubble as ended without an error snackbar (the prompt
  started or was withdrawn elsewhere).
- Keep the existing `:abort` for the running turn; the bubble action for a
  running prompt may delegate to it.
- Do not send `prompts:steer` or any other request as part of the cancel.

## Acceptance criteria

- [ ] A queued bubble exposes a cancel action; tapping it sends exactly one
      `POST .../prompts/{prompt_id}:abort`.
- [ ] Success removes the bubble and does not open a turn (no `turn.started`
      in the fake stream).
- [ ] `40402` removes the bubble without an error snackbar.
- [ ] A network failure keeps the bubble and shows the existing retry/error
      treatment.
- [ ] Live against 2.6.0: a prompt queued behind a `sleep` is withdrawn and
      never runs.

## Out of scope

- Reordering the queue.
- Cancelling skill activations.
- A queue-management screen.

## Notes

- `docs/server-api.md` → Prompts → Send (per-prompt actions) and Abort.
