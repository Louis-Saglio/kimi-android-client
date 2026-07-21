# Hide internal protocol envelopes from the chat timeline

- **ID:** 019
- **Created:** 2026-07-21
- **Depends on:** none

## Context

Product discussion 2026-07-21 (rendering-parity batch). Screenshots of a real
session show purple user bubbles containing raw internal traffic:
`<notification category="task" type="task.completed"
source_kind="background_task" source_id="…"> … </output-file>
</notification>` and `<system-reminder>` blocks — one literally reading "Make
sure that you NEVER mention this reminder to the user", displayed to the user.
These are server-injected user-role messages (background-task notifications,
system reminders) that the web client filters out. `ChatTimeline.flatten()`
joins every text part of a user message without inspection, so the app leaks
them. It is the only bug (as opposed to polish) in this batch, it is small,
and it leaks instructions the user was never meant to see — hence first in the
batch. Hiding (not restyling) was chosen: Louis has no use for these events on
the phone today; surfacing them as a proper UI element is a possible follow-up
if he ever misses the information.

## Description

- Establish the exact set of internal envelopes the web client hides: check
  the web client source (`apps/kimi-web` in MoonshotAI/kimi-code) for its
  filter list. Known so far from on-device screenshots: `<system-reminder>`,
  `<notification …>` (background-task completions). Document any additional
  tags found.
- Filter at the domain boundary (repository DTO → domain mapping or
  `ChatTimeline`): strip internal-envelope blocks from the text of user
  messages. If nothing but whitespace remains, drop the message entirely — no
  empty bubble.
- Apply on all three ingestion paths: history flatten, `context.spliced`
  echo (sent-prompt and steer echoes), and any future path sharing
  `MessageDto.toDomain()`.
- A user message mixing real text and an envelope keeps the real text only.

## Acceptance criteria

- [ ] Unit test: a user message whose text is entirely a `<system-reminder>`
  block produces no `ChatItem.UserMessage` row.
- [ ] Unit test: a user message wrapping a `<notification …>` background-task
  completion (payload shape as seen on-device) produces no row.
- [ ] Unit test: a user message mixing genuine text with an envelope renders
  only the genuine text.
- [ ] Unit test: the same filtering applies on the `context.spliced` path.
- [ ] Manually verified against the live server: the session that showed the
  leaked reminders no longer shows any envelope content.
- [ ] `./gradlew :app:assembleDebug` succeeds, unit tests green.

## Out of scope

Rendering background-task completions as first-class UI (e.g. a subtle
"background task finished" row) — follow-up only if Louis misses the signal.
Filtering assistant-role envelopes (none observed). Changing what the web
client does.

## Notes

- Filter lives in one place and is pure/string-based — keep it trivially
  unit-testable like `ChatTimeline` itself.
- If the web client's detection turns out to be "text starts with `<` tag X",
  prefer that over a full XML parse; these envelopes are generated, not
  user-typed.
- `docs/server-api.md` does not document these envelopes yet; add a short
  paragraph there while touching this area (the nightly doc agent will keep
  it aligned).
