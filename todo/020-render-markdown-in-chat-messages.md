# Render markdown in chat messages

- **ID:** 020
- **Created:** 2026-07-21
- **Depends on:** none

## Context

Product discussion 2026-07-21 (rendering-parity batch). The chat screen draws
message text as a plain Compose `Text` (`ChatScreen.kt` `AssistantBubble` /
`UserBubble`), so agent replies show raw markdown: literal `**Root cause:**`,
backticks around identifiers, unstyled `-` bullets, unreadable file links. The
web client renders all of this formatted, and on a phone screen the raw syntax
is the single biggest readability gap between the two clients. Implementation
decision taken in the discussion: **use one maintained Compose markdown
library** — a hand-rolled parser was rejected (parsing edge cases become our
maintenance burden forever), and "implementer's choice" was overridden by
Louis in favor of committing to a library outright. Candidate named in the
discussion: `com.halilibo.compose-richtext` (`richtext-ui` +
`richtext-commonmark`, CommonMark via commonmark-java); another
Compose-native, actively maintained library is acceptable if it covers the
same subset.

## Description

- Add exactly one markdown library via the version catalog
  (`gradle/libs.versions.toml`); Compose-native, no WebView, no
  `TextView`-in-AndroidView wrappers.
- Replace the plain `Text` for message bodies in `AssistantBubble` and
  `UserBubble` with library-rendered markdown. Both committed (history) and
  live (streaming) bubbles must render formatted text.
- Required subset (what the agent actually emits): bold, italic, inline code,
  links, bullet and numbered lists, headings, fenced code blocks. Default
  library theming is acceptable in this ticket — visual parity with the web
  look is ticket 021.
- Links open in the device's default browser.
- Thinking text stays as-is (plain italic); markdown applies to message text
  only.

## Acceptance criteria

- [ ] A fixture assistant message exercising the whole required subset (bold,
  italic, inline code, a link, a bullet list, a numbered list, a heading, a
  fenced code block) renders formatted in the chat — verified by a Compose
  semantics/screenshot test if the harness allows, else by on-device check
  against a live session.
- [ ] Tapping a rendered link opens it in the browser.
- [ ] Streaming bubbles render formatted text while deltas are still
  appending (no raw-syntax flash persisted at end of turn).
- [ ] `./gradlew :app:assembleDebug` succeeds, unit tests green.

## Out of scope

Web-look styling: inline-code chips, code-block surface and horizontal scroll,
heading scale, list indentation — ticket 021. Streaming re-parse performance
work — 021. Syntax highlighting inside code blocks (no ticket yet; raise one
if missed). Tables, KaTeX, Mermaid (permanently deferred, see ticket 005).
Markdown in the thinking section.

## Notes

- Check the library's Compose BOM / Kotlin compatibility before committing to
  a version; the project pins Compose via `gradle/libs.versions.toml`.
- Re-parsing on every streaming delta is the known cost driver — fine to land
  naive here, but do not architect against memoization; 021 will need it.
- Load `android-skills:compose` when implementing.
