# User message copy action and timestamp

- **ID:** 039
- **Created:** 2026-07-26
- **Depends on:** none

## Context

UX review comparing the app with the web UI. The web shows a timestamp and
copy/retry affordances under each user message; the app shows nothing. On a
touch screen, always-visible icon rows per message are clutter — the
M3-idiomatic translation of the web's hover actions is long-press → dropdown
menu, with the timestamp as a subtle caption.

The timestamp data exists: history messages carry `created_at`
(`docs/server-api.md`, `GET /api/v1/sessions/{id}/messages`), but the
repository currently drops it — `ChatMessage` has no timestamp field.

## Description

- Plumb `created_at` through `ChatDtos` → `ChatMessage` →
  `ChatItem.UserMessage` (nullable; live spliced messages have none until the
  turn-end refetch commits them).
- Render the timestamp on user bubbles as a `labelSmall` caption in
  `onSurfaceVariant` under the bubble (right-aligned with it), formatted like
  the web (locale short date + time).
- Long-press on `UserBubble` (`combinedClickable` on the `Surface`) opens an
  M3 `DropdownMenu` with a "Copy" entry that puts the exact message text on
  the clipboard. Brief "copied" feedback — the check-swap pattern already
  used by `CopyMessageAction`, or a snackbar; implementer's choice.

## Acceptance criteria

- [ ] History user messages show the formatted timestamp; a live spliced
      message gains its timestamp after the turn-end refetch.
- [ ] Long-press on a user bubble opens a menu whose Copy entry puts the exact
      message text on the clipboard.
- [ ] Long-press does not interfere with tapping attachment chips/images
      inside the bubble.
- [ ] Touch targets ≥ 48dp; menu is reachable by TalkBack
      (`onLongClick` semantics label).

## Out of scope

- Retry / edit-and-resend of a user message (the web has it; file a separate
  ticket if wanted).
- Timestamps on assistant messages or tool rows.
- Swipe actions or always-visible action rows.

## Notes

- Check `ChatDtos.kt` for the messages payload parsing; `ChatTimeline`
  `flatten` and the splice dedup in `appendSpliced` need to carry the new
  field.
