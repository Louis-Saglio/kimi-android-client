# Attribute cold-open approvals and questions to sub-agents

- **ID:** 156
- **Created:** 2026-09-23
- **Depends on:** none

## Context

Server 2.0.2 (commit `2cedfaf1`) closed the gap ticket 139 documented:
approval wire items — snapshot `pending_approvals`,
`event.approval.requested`, and the `/approvals?status=pending` poll —
now carry a required snake_case `agent_id` (`"main"` or the asking
sub-agent), and question wire items (pending poll, snapshot
`pending_questions`, `event.question.requested`) carry an optional one
(docs/server-api.md:42-68, 4958, 5104-5105). 139 shipped the WS-path
approval badge but had to hard-default snapshot approvals to `"main"`
(Snapshot.kt:41-51, "reads 'main' here until upstream stamps them") and
explicitly deferred the question badge: "ticket separately if wanted".
With swarms shipped (134-139), a member's approval or question on
cold-open is now attributable on every surface.

## Description

**1. DTOs.** `ApprovalRequestedPayload` (ChatDtos.kt:277-282 — the
snapshot path rides this shared DTO) gains `agent_id: String = "main"`;
`QuestionRequestedPayload` (ChatDtos.kt:306-310) gains `agentId:
String?`; the snapshot `pending_questions` item DTO likewise. Note the
snake_case on the wire vs the camelCase the WS frame already carries —
decode per surface, do not rename existing fields.

**2. Badge on questions.** The question wizard/card gains the same badge
139 built for approvals: `agentId != "main"` → "Sub-agent · {name}",
name resolved from `SubagentStreamStore` (record name, else description),
plain "Sub-agent" when no record exists yet. Main-agent questions render
exactly as today.

**3. Snapshot approvals.** Cold-open/restored pending approvals from the
snapshot now resolve through `agent_id` — no more `"main"` default when
the stamp says otherwise.

**4. Tests.** Parse tests with the 2.0.2 verbatim captures
(docs/server-api.md:5104-5105); name-resolution tests including the
missing-record fallback; androidTest: question card badge with member
name for a subagent question, absent for main.

## Acceptance criteria

- [ ] Unit + androidTests above pass; existing suite green.
- [ ] On device: cold-open a session with a pending sub-agent approval —
      the card names the member (139's WS path already did; the snapshot
      path now matches); a sub-agent question card shows "Sub-agent ·
      {name}".
- [ ] Main-agent approvals/questions are visually unchanged.
- [ ] `flock /tmp/kimi-android-gradle.lock ./gradlew :app:testDebugUnitTest
      :app:assembleDebug --max-workers=1 --no-daemon` passes; release APK
      published to the file server per AGENTS.md.

## Out of scope

- Per-member auto-approve or session-approve scoping rules (139 out of
  scope, still unwanted).
- The approvals poll route as a new read path (snapshot already delivers
  these items; redundant).
- Approval decision `"cancelled"` enum value (2.0.2, semantics
  undocumented — unverifiable from the wire).

## Notes

- This is the exact follow-up 139 step 1 and its out-of-scope list
  anticipated; keep the badge idiom byte-identical to the approval one.
