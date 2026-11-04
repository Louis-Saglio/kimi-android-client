# Show the plan text in `plan_review` approval cards

- **ID:** 173
- **Created:** 2026-10-28
- **Updated:** 2026-11-04 for server 2.5.0
- **Depends on:** 171

## Context

Improvement found by the nightly server drift run from 2.3.3 to 2.4.0 on
2026-10-28 (server 2.4.0, upstream commit `0325b755`, "leave Plan mode to the
user and review every plan"). Since 2.4.0 the agent can no longer enter Plan
mode by itself, `auto` no longer approves plans silently, and **every plan
waits for an explicit approval**. Live on 2.4.0, entering plan mode through
`POST /sessions/{id}/profile {"agent_config":{"plan_mode":true}}` and letting
the agent call `ExitPlanMode` produced an approval whose
`tool_input_display` is:

```json
{"kind":"plan_review",
 "plan":"# Add README.md with \"hello\"\n\n## Context\n\n…",
 "path":"/path/to/session/agents/main/plans/plan.md"}
```

The app's `ToolInputDisplayDto` only decodes `command` and the `agent_call`
fields, and `ChatMessageMapper.summary` falls back to the action string. The
phone therefore shows an Allow/Deny card reading "Presenting plan and exiting
plan mode" with no plan to read — the user is asked to approve a plan they
cannot see. Because 2.4.0 makes this review mandatory, every plan-driven task
now hits it.

`selected_label` exists on the response for approvals that offer labeled
choices, but the captured plan approval carried no option labels, so this
ticket only covers the plan body.

**2.5.0 update (nightly drift run 2026-11-04).** The `interaction` frame and
its `request.tool_input_display` are unchanged, but approvals now arrive only
on a `subscribe_v2` subscription. For a cold read (or when the display is
absent), `GET /api/v1/sessions/{id}/transcript/plan?agent_id=main` returns
`plans:[{tool_call_id, turn_id, source:"interaction"|"display"|"output",
plan, path?, options?, review?}]` — a second source of the same text; use it
only as a fallback, not as a second rendering path.

## Description

1. Extend the approval display model with the `plan_review` kind: decode
   `kind`, `plan`, and `path` from `tool_input_display` (add fields to
   `ToolInputDisplayDto` or introduce a typed display sealed class; unknown
   kinds keep today's fallback).
2. Render the plan Markdown in the approval card whenever the display kind is
   `plan_review`: scrollable body with a sensible collapsed height, the
   existing Allow/Deny buttons below it. Reuse the chat's Markdown renderer if
   one is already used for assistant text; otherwise render the raw text
   (a plain monospace/text block is acceptable for the first cut).
3. Keep `summary`/preview behavior for `command` and `agent_call` approvals
   unchanged; the plan card's compact summary can stay the action string.
4. The approval id, decision route, and clearing behavior come from ticket
   171 — do not duplicate that plumbing.

## Acceptance criteria

- [ ] A `plan_review` approval renders the plan text in the card (unit test on
      the display mapping plus a Compose test asserting the plan string is
      visible after expanding/rendering).
- [ ] A long plan scrolls inside the card without pushing the Allow/Deny
      buttons off-screen.
- [ ] A command approval and an agent-call spawn look exactly as today.
- [ ] An approval with an unknown or missing display kind still shows the
      action string and works.
- [ ] Approving posts the same `{"kind":"approval","decision":"approved"}`
      body as any other approval.
- [ ] `:app:testDebugUnitTest` passes.

## Out of scope

- Sending `selected_label` or rendering labeled option buttons — no live plan
  approval carried options; revisit if one is observed.
- Editing the plan or jumping to the plan file path.
- Plan-mode controls in the app (entering/exiting plan mode); this ticket only
  makes the resulting approval readable.
- Questions (ticket 172) and the approval transport (ticket 171).

## Notes

Relevant code: `data/chat/ChatDtos.kt` (`ToolInputDisplayDto`),
`data/chat/ChatMessageMapper.kt` (`summary`), the approval card in
`ui/chat/components/Cards.kt`. Wire reference: `docs/server-api.md` →
Interactions → Plan review approvals.
