# Show model upgrade guidance in the picker

- **ID:** 166
- **Created:** 2026-10-22
- **Depends on:** none

## Context

Found by the nightly server drift run from 2.2.0 to 2.3.0 on 2026-10-22.
Server 2.3.0, upstream commit `0327e8e9a875`, adds an optional `upgrade`
object to each model catalog item. It names a replacement model, carries
migration Markdown, and can include a retirement date. The catalog grew to
twelve models by server 2.3.3 (verified 2026-10-28), and none of them
publishes this object yet, so there is no immediate breakage.

The Android decoder ignores the field. `ModelDto`, `ServerModel`, and
`ModelsRepository` therefore drop guidance that a provider may publish later.
This matters on the phone because model choices are sticky: `ChatRepository`
prefers the stored per-session model over the server default. A session can
keep stamping a retiring model without the user seeing the available upgrade.

The chosen behavior is to inform, not migrate. Rewriting a saved model or
silently changing the next prompt would be surprising and would discard the
user's explicit choice.

## Description

Decode `upgrade` in the model DTO and map it through `ServerModel`, including
`model`, `migration_markdown`, and optional `retirement_at`. In the existing
shared `ModelPickerSheet`, mark rows that have an upgrade and show the
replacement alias and retirement date when present. Make the migration guide
available from the row in a read-only dialog, using the existing `InfoDialog`
and `MarkdownBody` components rather than adding a Markdown renderer.

Keep model selection and persistence unchanged. The upgrade information must
not rewrite a session preference, change the server default, or select the
replacement automatically.

## Acceptance criteria

- [ ] DTO tests prove that a catalog item with a complete `upgrade` object
      decodes all three fields, and that items without it still decode.
- [ ] A model with upgrade metadata shows the replacement alias in the shared
      picker; its retirement date appears only when supplied.
- [ ] Opening the migration guide renders the server's Markdown, and closing
      it leaves the current model unchanged.
- [ ] Existing selection behavior is unchanged: no model preference or server
      config changes until the user taps a model row.
- [ ] `:app:testDebugUnitTest` passes.

## Out of scope

- Automatic model migration, retirement countdowns, or push notifications.
- Applying `retired_default` event payloads. Ticket 110's cache invalidation
  already covers the app's refresh behavior.
- Cloud-gated confirmation before a deliberate switch, covered by ticket 167.
- Rating prompts and feedback-survey settings.

## Notes

Relevant code: `data/chat/ChatDtos.kt`, `ServerModel.kt`,
`ModelsRepository.kt`, and `ui/chat/components/ModelPickerSheet.kt`.
`SheetCheckRow` already supports subtitles and badges. The protocol schema and
live-catalog absence are recorded in `docs/server-api.md`.
