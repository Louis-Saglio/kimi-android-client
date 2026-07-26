# Use shape tokens in the chat UI

- **ID:** 040
- **Created:** 2026-07-26
- **Depends on:** none

## Context

M3 audit nit from the UX review: hardcoded `RoundedCornerShape(12.dp / 8.dp /
6.dp / 3.dp)` across `ChatScreen.kt` instead of `MaterialTheme.shapes` tokens.
Small hygiene fix, deliberately split out of ticket 036 so the color-scheme
ticket stays single-concern.

## Description

Replace hardcoded corner radii in the chat UI composables with
`MaterialTheme.shapes` tokens (`extraSmall` / `small` / `medium` / `large` as
appropriate): `UserBubble` (→ `shapes.large`), `AttachmentChip`,
`AttachmentChipRow`, `HistoryImageThumbnail`, `CopyMessageAction`,
`QuestionCard` option rows, `ToolDetailBlock`, the stop-square in the top bar.
Optional web-parity touch if it stays cheap: asymmetric corners on the user
bubble (tighter trailing corner) via `shapes.large.copy(...)`.

## Acceptance criteria

- [ ] `rg 'RoundedCornerShape\(\s*\d' app/src/main` returns no hits in the
      chat UI (previews/theme excepted).
- [ ] No visual regression beyond the intended token mapping — check
      `ChatContentPreview` in both themes.

## Out of scope

- Defining custom shape tokens beyond the M3 defaults.
- Any color work (ticket 036).

## Notes

- Land after tickets 036–038 to avoid rebase churn — they all edit
  `ChatScreen.kt`. Ordering note only, not a hard dependency.
