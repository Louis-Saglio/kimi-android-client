# Glob, WebSearch, FetchURL: rich rows and expansions

- **ID:** 122
- **Created:** 2026-09-01
- **Depends on:** 118

## Context

Three read-only lookup tools still dump JSON on expansion. They share one
archetype — compact query on the row, clean result list/text on expansion —
so they ship as one slice (2026-09-01 design). Grep is deliberately split
out into 123 (its three output modes make it the parser-heavy one).

Field decisions: Glob shows `pattern` (monospace) + scope `path` when set;
`include_ignored: true` gets a chip on expansion (default false → hidden).
WebSearch's `query` is prose — rendered as prose, not monospace. FetchURL's
preview leads with the **host** (the identifying part of a URL on a narrow
row), full URL on expansion.

## Description

**1. Payloads.** `ToolPayload.Glob(pattern, path, includeIgnored)`,
`ToolPayload.WebSearch(query)`, `ToolPayload.FetchUrl(url)` on the 119
sealed type + parser tests.

**2. Rows.** Custom path-data icons (magnifier, globe, link;
`WorkspaceIcon` precedent). Previews: Glob → pattern monospace; WebSearch →
query; FetchURL → `host` + shortened path (`example.com/docs/…`). These
replace the generic `ToolPreview` output for the three tools (extend
`ToolPreview`'s per-tool keys: `pattern`, `query`, `url` — host-first
formatting lives in the row, not the preview string).

**3. Expansions** (parsed payload replaces the generic table):

- **Glob:** "N matches" header counting the output's non-blank lines, then
  the path list (monospace, internally scrollable capped block); when the
  output carries the truncation marker (the tool's "(truncated…)" tail —
  verify the exact wording on the live server during implementation),
  render it as a chip and strip it from the list. Any parse doubt →
  verbatim block.
- **WebSearch:** the result text through the app's existing markdown
  renderer (read-only, the assistant-bubble component) so result links and
  titles are readable; no custom link handling beyond what the renderer
  already does.
- **FetchURL:** full URL, selectable, `bodySmall` monospace; the fetch
  note ("main text extracted" vs full verbatim — the tool's preamble line)
  as a caption when it parses; content in the capped scroll block,
  verbatim.

**4. Tests.** Glob line counting + truncation-marker strip (clean and
doubtful shapes); preview formatting (host-first URL shortening); row
androidTests (icons, previews); expansion androidTests (list vs verbatim
fallback, markdown block present for WebSearch).

## Acceptance criteria

- [x] New tests above pass; existing suite green with no behavior-test
      changes (two additive fixture updates, listed in the implementation
      notes).
- [x] Observable on device: a Glob row expands to a counted path list (with
      the truncation chip when the server capped the results); a WebSearch
      row expands to rendered markdown, not raw text; a FetchURL row shows
      `host/path` on the row and the full URL inside. (Verified through the
      Firebase Test Lab instrumentation assertions, not a manual phone
      check.)
- [x] Malformed/unexpected output shapes render verbatim — verified by
      tests feeding garbage into each parser.
- [ ] `flock /tmp/kimi-android-gradle.lock ./gradlew :app:testDebugUnitTest
      :app:assembleDebug --max-workers=1 --no-daemon` passes; release APK
      published to the file server per AGENTS.md. (Gradle passes; the release
      APK is published by the merging session.)

## Out of scope

- Grep (ticket 123).
- Opening a Glob result in the file viewer (no file content client-side).
- Opening WebSearch/FetchURL links in a browser beyond the markdown
  renderer's existing behavior.
- Favicons or any network fetch from these rows.

## Notes

- Verify the live wire shape of Glob's truncation marker and FetchURL's
  note line against the current server before writing the parsers; record
  verbatim captures in this file or `docs/server-api.md` as appropriate.

## Implementation notes (2026-09-01)

- Wire-shape verification: the running server is 0.39.1 (`GET /api/v1/meta`),
  the exact binary whose bundled agent-core tool sources (`glob.ts`,
  `fetch-url.ts`, `web-search.ts`) were extracted with `strings` from
  `~/.kimi-code/bin/kimi` (the service's ExecStart path — same bytes the
  server runs). Verbatim captures recorded in `docs/server-api.md`, Tools →
  Lookup tool output text. Findings vs the ticket's guesses: the Glob
  truncation marker is a HEAD, not a tail — a three-line block
  (`[Truncated at 100 matches ` / ` use a more specific pattern]` — the
  tool's own line break — then `Only the first 100 matches are returned.`)
  pushed before the path lines; FetchURL's note is the first sentence of a
  two-sentence head preamble closed by a blank line.
- Payloads: `ToolPayload.Glob(pattern, path?, includeIgnored)` (`pattern`
  required; blank `path` parses as absent; a string `"true"` is not the flag
  — Edit's replace_all rule), `WebSearch(query)`, `FetchUrl(url)`.
- ToolPreview gained the per-tool identifying keys `pattern`/`query`/`url`.
  FetchURL's host-first reformatting lives in the row (`hostFirstUrl` in
  ToolRows.kt — java.net.URI, non-default port kept, first path segment +
  `…`; unparseable → the one-lined raw URL).
- Rows: magnifier `SearchIcon` (Glob — generic name, Grep reuses it in 123),
  globe `GlobeIcon` (WebSearch), link `LinkIcon` (FetchURL); Material Icons
  search/public/link path data, verified against google/material-design-icons.
  WebSearch's preview renders prose (default font), the others stay monospace.
- Expansions (parsed payload replaces the args table; the row keeps the
  inline expansion like Bash): Glob → scope chips (path folder chip,
  "including ignored" — non-default only), "N matches" header, the path list
  in the capped scroll block, the "truncated" chip; WebSearch → the output
  through MarkdownBody in a capped scroll surface; FetchURL → the full
  selectable URL, the note caption, the content verbatim. Unparseable calls
  keep the generic table; a running WebSearch without output stays
  non-expandable (nothing to show).
- `GlobOutputs` (the BashOutputs pattern): head-anchored exact-wording marker
  strip → `truncatedCount`; the `Found N matches` footer (the exactly-100 edge)
  is stripped too — line-boundary anchored, so a path literally named that
  survives. Blank/status-only outputs (`No matches found`, `No non-sensitive
  matches found (…)`) → null → verbatim block; error rows never parse (the
  verbatim error surface). `FetchUrlOutputs` lifts the note sentence
  head-anchored, content verbatim; any drift → no caption, untouched output.
- Fixture updates (additive only, no assertion logic touched):
  ToolPreviewTest's compact-JSON fallback case moved Glob →
  `mcp__kiln__list_runners` (Glob previews `pattern` now — the ticket's
  point); ToolRowTest's 118 generic-table case renamed its tool Glob →
  `mcp__kiln__submit_batch` (a parseable Glob gets the rich row now).
- Test Lab note: the three icon assertions use `useUnmergedTree = true` — the
  row's `clickable` merges descendants in the merged tree and TestTag's merge
  policy never leaks tags into the merged parent, so the tag node exists only
  in the unmerged tree (a missing merged-tree node surfaces as the misleading
  "not displayed", per `checkIsDisplayed`'s empty-selection path).
