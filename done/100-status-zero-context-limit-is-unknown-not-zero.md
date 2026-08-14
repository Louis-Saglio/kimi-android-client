# /status mapping: treat a zero context limit as "unknown", not a real 0/0

- **ID:** 100
- **Created:** 2026-08-06
- **Depends on:** none

## Context

Server 0.34.0 made `max_context_tokens` **omittable** in
`GET /api/v1/sessions/{id}/status` (upstream #2696: "omit max_context_tokens
in REST session status when unknown" — `0` is the engine's unknown marker,
never a real limit). Not yet observed omitted live (fresh sessions report the
262144 default), but the wire contract now allows it at any time.

The app's status DTO decodes an omitted field to `0`
(`ChatDtos.kt:83` — `@SerialName("max_context_tokens") val maxContextTokens:
Long = 0`), and the `/status` mapping builds `ContextUsage(it.contextTokens,
it.maxContextTokens)` **unconditionally** (`ChatApi.kt:322`). The snapshot
path already guards this exact case (`ChatMessageMapper.kt:140` —
`?.takeIf { it.contextLimit > 0 }`, the "placeholder = unknown, never render
a 0/0 ring" rule from the 0.32.0 drift), and the WS path uses nullable fields
(`ChatDtos.kt:94`). Only the `/status` path would leak a phantom
`ContextUsage(used, 0)` to the UI; the ring itself survives (`ContextUsageRing.kt:128`
returns 0% for `max <= 0`) but shows the misleading "0 tokens · 0%" state
instead of the unknown/no-ring state.

## Description

In the `/status` mapping (`ChatApi.kt:322`), drop the context usage when
`maxContextTokens <= 0` — same semantics as the snapshot guard: a zero limit
means "unknown", not "empty context". One-line change, no DTO change (the
defaulted `Long = 0` decode stays).

## Acceptance criteria

- [ ] Unit test: a status response **without** `max_context_tokens` (or with
      `0`) and a non-zero `context_tokens` produces **no** `ContextUsage`
      (unknown), not `ContextUsage(used, 0)`.
- [ ] Unit test: a normal status response (`max_context_tokens: 1048576`)
      still yields `ContextUsage(used, 1048576)` unchanged.
- [ ] Existing status/snapshot/ring tests pass unchanged.

## Out of scope

- Surfacing the "unknown limit" state in the UI beyond today's no-ring
  behavior (the snapshot path's established semantics).
- The WS `agent.status.updated` path (already nullable/guarded) and the
  snapshot path (already guarded).
- Fresh-session `thinking_level: ""` (0.34.0, was `"off"`): the app does not
  parse `thinking_level`.
