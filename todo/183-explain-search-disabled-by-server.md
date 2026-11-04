# Explain "search disabled" instead of showing the server's error text

- **ID:** 183
- **Created:** 2026-11-04
- **Depends on:** none

## Context

Found by the nightly server drift run from 2.4.1 to 2.5.0 on 2026-11-04
(server 2.5.0, upstream commit `be89803b`, "turn the global search index off
by default"). `POST /api/v1/search` now answers, before any validation:

```json
{"code":50001,"msg":"global search is disabled; set [database] global_search = true or KIMI_CODE_GLOBAL_SEARCH=1 to enable it","data":null,"request_id":"…","stack":"GlobalSearchError: …"}
```

Verified live. The app's `SearchViewModel` maps any `DataError.Api` to
`error.toUserMessage()`, so the search screen becomes a full-screen
developer-facing message naming a server config key — useless on a phone.
Search is a shipped feature (tickets 146/147); on a default 2.5.0 server it
is simply unavailable, and the UI should say so rather than look broken.

## Description

1. In the search error mapping, special-case `DataError.Api` with code
   `50001` and a message starting `global search is disabled` (exact server
   prefix; keep the check narrow so other `50001`s stay generic).
2. Render a dedicated empty state: "Search is disabled on this server" with a
   one-line hint that the server operator can enable the search index; no
   retry button that can only fail the same way. Keep the query field
   disabled in that state.
3. On the session-list/chat entry point, either hide the search action while
   the server reports disabled or leave it and let the screen explain; choose
   the cheaper option that does not add a new server probe. Do not probe on
   app start.
4. Any other error (network, validation, page-token) keeps today's
   `toUserMessage()` behaviour.

## Acceptance criteria

- [ ] A `50001` with the "global search is disabled" prefix renders the
      dedicated disabled state, not the raw server sentence (unit test on the
      mapper).
- [ ] A different `50001` still renders the generic error path.
- [ ] Retrying is not offered in the disabled state; changing the query does
      not re-issue the request.
- [ ] The normal search flow (when the index is enabled) is unchanged.
- [ ] `:app:testDebugUnitTest` passes.

## Out of scope

- Enabling the search index server-side or adding a config toggle to the
  app's settings.
- A local/offline search fallback.
- The search result UI and pagination (tickets 146/147).

## Notes

Relevant code: `ui/search/SearchViewModel.kt`, `ui/search/SearchScreen.kt`,
`data/DataError.kt` (`toUserMessage`), `data/search/SearchApi.kt`.
Wire reference: `docs/server-api.md` → Search.
