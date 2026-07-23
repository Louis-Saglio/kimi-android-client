# Markdown styling parity with the web client + streaming performance

- **ID:** 021
- **Created:** 2026-07-21
- **Depends on:** 020

## Context

Product discussion 2026-07-21 (rendering-parity batch). Ticket 020 makes
markdown render at all, with library-default looks. This ticket closes the
remaining visual gap to the web client, whose screenshots show: inline code as
monospace text on a rounded dark chip, fenced code blocks as monospace on a
distinct inset surface, restrained heading sizes, and accent-colored file
links. It also owns the performance question 020 defers: the app re-parses the
in-flight bubble on every `assistant.delta`, and on a phone that must not
jank while a long reply streams.

## Description

- Theme the markdown renderer (dark theme first — that is what Louis uses) to
  match the web look:
  - inline code: monospace on a rounded chip background,
  - fenced code blocks: monospace on a distinct surface, horizontally
    scrollable when lines overflow, no forced line wrap,
  - headings: capped scale — agent output is mostly h2/h3 and library-default
    h1 fills half the phone screen,
  - lists: proper marker indentation, comfortable item spacing,
  - links: theme accent color, underline per web.
- Make streaming smooth: memoize or otherwise throttle markdown re-parsing of
  the live bubble so per-delta recompositions stay cheap (e.g. re-parse only
  when the text snapshot changes, which the library may already give — verify,
  don't assume).

## Acceptance criteria

- [x] Side-by-side on-device check against the web client for the same
  session: inline code, a fenced code block, a heading, and a list each read
  as the same kind of element. *(Louis, 2026-07-23, on the release APK: chips
  and surfaces read correctly after the two fixes below — "I tested and it's
  fixed". His screenshots of the broken first iteration are in the session
  thread, not attached here.)*
- [x] A code block with lines longer than the screen width scrolls
  horizontally instead of wrapping. *(Built into the library's `MarkdownCode`
  — `horizontalScroll` on the code text, verified in the 0.26.0 source;
  covered by `MarkdownBubbleTest.longCodeLineScrollsHorizontally` asserting
  the horizontal axis-range semantics.)*
- [x] Streaming a long markdown reply on-device shows no visible stutter and
  the composer stays responsive. *(Louis, 2026-07-23, on his phone with the
  release APK — confirmed together with the styling check; see perf note
  below.)*
- [x] `./gradlew :app:assembleDebug` succeeds, unit tests green.
  *(2026-07-22: assembleDebug green; 113 unit tests green.)*

## Out of scope

Syntax highlighting inside code blocks (raise a follow-up ticket if missed).
Per-code-block copy button (same — cheap and useful, but its own ticket).
Tables. Light-theme fine-tuning beyond "not broken". Markdown in user bubbles
beyond what 020 already does.

## Implementation notes (2026-07-22)

- All theming lives in `MarkdownBody` (`ChatScreen.kt`) — no component
  overrides were needed; the stock colors/typography/padding/extended-spans
  hooks cover the whole spec.
- Inline-code chip: `markdownColor(inlineCodeBackground = …)` plus the
  library's `ExtendedSpans(RoundedCornerSpanPainter())`. The factory must
  create a **fresh instance per text node** (as the library intends) —
  `ExtendedSpans` holds per-node draw state, and a single shared instance
  scrambled chip positions across paragraphs (caught on-device 2026-07-22).
- Chip/code surface color: `Color.Black.copy(alpha = 0.3f)` — reads as a
  subtle darkening of any bubble; the first pick (`surfaceContainerLowest`,
  near-black) was a hard blob on the grey bubble (same on-device check).
- Code blocks: alpha-black inset surface (see above), corner radius from
  library dimens (8dp). Horizontal scroll is the library default — no wrap,
  long lines scroll.
- Headings capped: h1 = `headlineSmall` (24sp, vs the library default
  `displayLarge` 57sp), h2 = `titleLarge`, h3 = `titleMedium`, h4–h6 =
  `titleSmall`. Covered by `MarkdownBubbleTest.headingFontSizeIsCapped`.
- Lists: `markdownPadding(indentList = 16.dp)` for clearer marker
  indentation; item spacing stays at library defaults (comfortable enough).
- Links: `linkText = colorScheme.primary`; underline is the library default.
- Perf verification (ticket asked to verify, not assume): in 0.26.0 the
  `Markdown` composable calls `MarkdownParser.buildMarkdownTreeFromString`
  unconditionally in composition — there is no internal memoization, and its
  interface-typed params make the scope non-skippable. Mitigation:
  `MarkdownBody` itself takes only stable params (`String`, `Modifier`), so
  it is skippable and the parse is bounded to one per text snapshot (i.e.
  per delta) — recompositions from composer typing, scroll, timers, etc. no
  longer re-parse. Per-delta cost of the live bubble is inherent to any
  whole-text reparse and stays as-is.
- Tests: two new semantics tests in `MarkdownBubbleTest`
  (`longCodeLineScrollsHorizontally`, `headingFontSizeIsCapped`). Full
  instrumented suite green on Firebase Test Lab 2026-07-22
  (MediumPhone.arm, API 34 — 19/19 passed).
- Release APK published to the file server 2026-07-22 (re-published 2026-07-23
  with the chip fixes). On-device verification by Louis 2026-07-23: fixed.

## Notes

- Web reference: session screenshots of 2026-07-21 (in the discussion), or any
  live session side by side.
- If the chosen library cannot style one element to spec, prefer a small
  renderer override over switching libraries.
