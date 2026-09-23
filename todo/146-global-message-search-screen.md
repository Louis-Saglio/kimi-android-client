# Global message search screen

- **ID:** 146
- **Created:** 2026-09-23
- **Depends on:** none

## Context

`POST /api/v1/search` (global full-text search over user messages,
assistant replies and session titles, across all sessions) shipped in
server 0.31.0 and was hardened in 0.33.0 (v2 keyset `page_token`,
`index_state.stale`/`degraded`, `incomplete` enum). The drift passes
twice noted "the app never calls `/api/v1/search` — no ticket", and
tickets 010/043 explicitly deferred it: "no ticket yet — create one if
Louis wants it". Louis has hundreds of sessions on the server; finding an
old conversation is the biggest missing feature versus the web client.

Wire reference: docs/server-api.md:2560-2640 (request/response shapes,
keyset semantics, `stale:true` verbatim sample). Deleted sessions' hits
are filtered at query time since 0.43.1, so no client-side filtering is
needed.

## Description

**1. Data layer.** `SearchApi` (Retrofit): `POST /api/v1/search` with
body `{query, mode?, op?, role?, sort?, page_size?, page_token?}` —
defaults: `mode:"terms"`, `op:"AND"`, `sort:"score"`, `page_size:20`
(1–50). Response `data`: `items[] {session_id, workspace_id,
session_title, agent_id, role ("user"|"assistant"|"title"), snippet,
time, turn?, step_id?, score}`, `has_more`, `page_token?` (opaque, only
when `has_more`), `incomplete?`, `index_state {state, indexed_sessions,
total_sessions, documents, stale?, degraded?}`, `source`. Repository
method returns the sealed `DataError` model per `docs/conventions.md`.
Treat `page_token` as opaque; on a `40001` page-token error, restart the
search from page one (the documented signal).

**2. Search screen.** Entry: search icon in the session-list top bar.
Query text field (submit on IME action), results list: role icon, session
title, snippet, hit time (platform-relative, 113). Append next page on
scroll while `has_more` (pass `page_token` back verbatim; never change
query conditions mid-pagination). States: empty query prompt, no results,
loading, error with retry. When `index_state.stale` or `degraded` is
present, show a slim banner "Search index rebuilding — results may be
incomplete" (do not block results).

**3. Tap-through.** Tapping a result navigates to that session's chat
(`session_id` → existing chat route). Jump-to-message is out of scope.

**4. Tests.** Repository mapping test using the verbatim `data` sample at
docs/server-api.md:2627-2630 (stale index); ViewModel test: token passed
only when `has_more`, malformed-token `40001` restarts at page one,
`stale` banner state; androidTest: results render, banner shows, tap
navigates to the session.

## Acceptance criteria

- [ ] Unit + androidTests above pass; existing suite green.
- [ ] On device: searching a known phrase returns hits from several
      sessions with title/snippet/time; tapping one opens that session.
- [ ] Paging appends the next 20 without duplicates; changing the query
      mid-scroll never reuses the old token.
- [ ] `index_state.stale:true` (observable during a server index rebuild)
      shows the banner while results remain usable.
- [ ] `flock /tmp/kimi-android-gradle.lock ./gradlew :app:testDebugUnitTest
      :app:assembleDebug --max-workers=1 --no-daemon` passes; release APK
      published to the file server per AGENTS.md.

## Out of scope

- In-session scoped search (`container.session_id`) — ticket 147.
- Role/time-filter UI (`role`, `start_time`/`end_time` request fields
  exist; add when asked).
- Jump-to-message and match highlighting in the chat.
- `incomplete` surfacing beyond the stale/degraded banner.

## Notes

- `agent_id` on a hit is `"main"`, a sub-agent id, or `""` for title
  hits; `turn` is absent on title hits — keep the DTO fields nullable.
- `score` is 0 in `literal` mode; do not build UI on it.
