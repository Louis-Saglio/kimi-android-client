# In-session message search

- **ID:** 147
- **Created:** 2026-09-23
- **Depends on:** 146

## Context

The same `POST /api/v1/search` endpoint accepts `container.session_id`,
scoping the search to one session (`source:"live"` then; docs/server-api.md
2568-2572). Ticket 146 builds the global search screen; this ticket adds
the scoped variant from the chat screen, which is where "find that message
in this long conversation" actually happens.

## Description

**1. Data layer.** Extend 146's `SearchApi`/repository with an optional
`container: {session_id}` request field (only `session_id` filters —
`agent_id` is ignored server-side; do not send it).

**2. Entry + screen.** Search icon in the chat screen's top bar opens the
same search screen pre-scoped to the current session (title shows the
session name). Results list is 146's; tapping a result returns to the
chat (no jump-to-message — see out of scope).

**3. Tests.** Repository test that `container.session_id` lands in the
request body; ViewModel scoping test; androidTest entry point opens the
scoped screen.

## Acceptance criteria

- [ ] Unit + androidTests above pass; existing suite green.
- [ ] On device: chat top-bar search finds a phrase from earlier in the
      same session and does not return hits from other sessions.
- [ ] `flock /tmp/kimi-android-gradle.lock ./gradlew :app:testDebugUnitTest
      :app:assembleDebug --max-workers=1 --no-daemon` passes; release APK
      published to the file server per AGENTS.md.

## Out of scope

- Jump-to-message (scroll + highlight the hit in chat history).
- Sub-agent scoping (`container.agent_id` is silently ignored by the
  server — verified, docs/server-api.md:2570).
- Search filters UI (role, time bounds).

## Notes

- Reuses 146's paging, banner and empty/error states unchanged.
