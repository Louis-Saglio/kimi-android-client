# Approval card: sub-agent badge

- **ID:** 139
- **Created:** 2026-09-15
- **Depends on:** none

## Context

A subagent's permission request arrives on the session socket with the
payload stamped `agentId:"agent-N"` (verbatim capture
protocol-verify-0.33.0/ws-s1.jsonl:532; shape unchanged at 0.43.0) and
resolves through the same session-level approvals route as main-agent
requests. The app decodes `ApprovalRequested` without the agentId
(ChatEvents.kt:126), so with a swarm running, several members asking for
approval look identical to the main agent asking, and identical to each
other. The user cannot tell whose Bash command they are approving.

Web parity: the approval card carries a "sub agent · {name}" badge
(`approval.subagentBadge`).

This is independent of the swarm tickets: single `Agent` calls already
produce these requests today.

## Description

**1. DTO** (`data/chat/ChatDtos.kt`). `ApprovalRequestedPayload` (:267)
gains `val agentId: String = "main"` (the payload stamp). Check whether the
snapshot's `pending_approvals` items (:435) carry the same stamp upstream;
decode it if present so a cold-open pending request is badged too.

**2. Event and mapping** (`data/chat/ChatEvents.kt:126`,
`data/chat/SessionSocket.kt`). `ApprovalRequested` carries `agentId`; the
socket mapping passes it through.

**3. Badge** (approval card in `ui/chat/components/Cards.kt`). When
`agentId != "main"`, show a badge "Sub-agent · {name}" using the badge idiom
(Surface + secondaryContainer + shapes.small). The name resolves from
`SubagentStreamStore`: the record's subagent name, else its description.
When no record exists yet (the request can race ahead of the spawned event),
fall back to plain "Sub-agent". Main-agent requests render exactly as today.

**4. Resolution flow unchanged.** The session-level approvals route already
handles subagent requests; only the presentation changes.

**5. Tests.** Socket parse test using the verbatim 0.33.0 capture (agentId
lands on the event); a ViewModel/store test for name resolution including
the missing-record fallback; androidTest: badge present with the member name
for a subagent request, absent for a main-agent request.

## Acceptance criteria

- [ ] New unit + androidTests above pass; existing suite green.
- [ ] Observable on device: run a swarm whose members run approval-requiring
      commands; each approval card names the asking member, and two members
      asking simultaneously are distinguishable.
- [ ] Main-agent approval requests are visually unchanged.
- [ ] Approving or rejecting from the card still resolves the request
      (regression check on the existing flow).
- [ ] `flock /tmp/kimi-android-gradle.lock ./gradlew :app:testDebugUnitTest
      :app:assembleDebug --max-workers=1 --no-daemon` passes; release APK
      published to the file server per AGENTS.md.

## Out of scope

- Badging `AskUserQuestion` prompts from subagents with the asking member
  (same idea on a different surface; ticket separately if wanted).
- Per-member auto-approve or session-approve scoping rules.
- Any change to approval resolution semantics.

## Notes

- Verbatim capture to embed in the test:
  `protocol-verify-0.33.0/ws-s1.jsonl:532`
  (`permission.approval.requested` with `agentId:"agent-1"`).
- Since 0.39.0 the approval id is a dedicated `approval_<uuid>` distinct from
  the tool call id (docs/server-api.md:2729-2739); the app already handles
  that split, this ticket does not touch it.
