# Brand color scheme

- **ID:** 036
- **Created:** 2026-07-26
- **Depends on:** none

## Context

UX review comparing the app with the Kimi web UI. `Theme.kt` uses stock
`darkColorScheme()`/`lightColorScheme()`, so the whole app renders in the M3
baseline purple — most visible in the user bubble (`primaryContainer`) — while
the web client's accent is blue. This is the single biggest reason the app
reads as "generic Material template" instead of "Kimi".

Decisions taken:

- A **static custom brand `ColorScheme`** (light + dark) seeded from the web
  blue. Dynamic color was considered and rejected: Material You would re-tint
  the app to the user's wallpaper, defeating brand parity — this app is a
  companion to one specific service.
- Fold in the audit nit `SuccessGreen` (`ChatScreen.kt`, top-level
  `Color(0xFF4CAF50)`): M3 has no success role, so it becomes a small theme
  extension via `CompositionLocal` instead of a loose val. Same file family,
  same concern.

## Description

- In `ui/theme/` define `kimiLightColorScheme` / `kimiDarkColorScheme` seeded
  from the web client's blue accent. Take the exact hex from the web client
  source (`apps/kimi-web` in MoonshotAI/kimi-code) rather than eyeballing it.
  Every existing role usage stays semantic — no per-component color overrides.
- Check the key pairs for WCAG AA in both themes: `primary`/`onPrimary`,
  `primaryContainer`/`onPrimaryContainer` (the user bubble), and `primary` as
  the markdown link color on the chat background. Desaturate the dark-theme
  primary as needed.
- Add a theme extension, e.g. a `LocalSuccessColor` `CompositionLocal` with a
  `MaterialTheme.successColor`-style accessor, provided by `KimiTheme`.
  Replace the `SuccessGreen` val and its usages in `ToolRow` and
  `CopyMessageAction` with it.

## Acceptance criteria

- [ ] User bubble, send button, markdown links, and focus states render in the
      blue family in both light and dark theme (verify via `ChatContentPreview`
      or a debug build screenshot).
- [ ] `rg 'Color\(0x' app/src/main` matches only inside `ui/theme/`.
- [ ] The `SuccessGreen` top-level val is gone; success glyphs read from the
      theme extension.
- [ ] No layout/structural change on any screen — palette only.

## Out of scope

- Dynamic color toggle, user-selectable themes, high-contrast variants.
- Shape-token cleanup (ticket 040), tool-call grouping (ticket 037),
  composer work (ticket 038).

## Notes

- `MarkdownBody`'s `Color.Black.copy(alpha = 0.3f)` code surface is deliberate
  (alpha-darkening works on any bubble color) and documented — leave it.
