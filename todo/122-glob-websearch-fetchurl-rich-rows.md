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

- [ ] New tests above pass; existing suite green with no behavior-test
      changes.
- [ ] Observable on device: a Glob row expands to a counted path list (with
      the truncation chip when the server capped the results); a WebSearch
      row expands to rendered markdown, not raw text; a FetchURL row shows
      `host/path` on the row and the full URL inside.
- [ ] Malformed/unexpected output shapes render verbatim — verified by
      tests feeding garbage into each parser.
- [ ] `flock /tmp/kimi-android-gradle.lock ./gradlew :app:testDebugUnitTest
      :app:assembleDebug --max-workers=1 --no-daemon` passes; release APK
      published to the file server per AGENTS.md.

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
