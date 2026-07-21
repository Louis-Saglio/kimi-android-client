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

- [ ] Side-by-side on-device check against the web client for the same
  session: inline code, a fenced code block, a heading, and a list each read
  as the same kind of element (screenshots attached to the ticket when moved
  to `done/`).
- [ ] A code block with lines longer than the screen width scrolls
  horizontally instead of wrapping.
- [ ] Streaming a long markdown reply on-device shows no visible stutter and
  the composer stays responsive (manual check; note the device used).
- [ ] `./gradlew :app:assembleDebug` succeeds, unit tests green.

## Out of scope

Syntax highlighting inside code blocks (raise a follow-up ticket if missed).
Per-code-block copy button (same — cheap and useful, but its own ticket).
Tables. Light-theme fine-tuning beyond "not broken". Markdown in user bubbles
beyond what 020 already does.

## Notes

- Web reference: session screenshots of 2026-07-21 (in the discussion), or any
  live session side by side.
- If the chosen library cannot style one element to spec, prefer a small
  renderer override over switching libraries.
