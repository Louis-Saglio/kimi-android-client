# Make prompt sends idempotent

- **ID:** 194
- **Created:** 2026-11-12
- **Depends on:** none

## Context

Server 2.6.0 (nightly drift run, 2026-11-12) made `prompt_id` on the prompt
POST (and `/steer`) idempotent: resending the same body with the same id
returns the first result instead of submitting twice, and a different body
with the same id answers `40927`. Records are kept in memory per session (256,
oldest ended evicted). Verified live: an identical resend returned the same
`prompt_id` and `created_at`; a changed body answered `40927`.

The app generates no `prompt_id` (`ChatApi.postPrompt`), so a send that times
out on a mobile network and is retried can create a duplicate turn. The
existing send-recovery logic handles 40931/40932 but cannot recognise its own
earlier send.

## Description

- Generate a stable prompt id per send (a UUID or ULID string is enough; the
  server accepts any string) and send it as `prompt_id`.
- Keep that id for every retry of the same send; a new send gets a new id.
- Use the same id for a direct steer (`POST .../steer`) if the composer's steer
  path is reused.
- Treat `40927` as a terminal conflict (the id was reused with a different
  body): surface the server message, do not retry.
- The local echo is keyed by the prompt id; the server echoes it as
  `prompt_id`/`user_message_id`, and the later `user_message` item carries it
  in `prompt_ids`.
- Keep the retry button behaviour: it resends the same body and id.

## Acceptance criteria

- [ ] `ChatApi.postPrompt` always includes a non-blank `prompt_id`.
- [ ] A simulated timeout followed by a retry sends the same `prompt_id` on
      both requests (MockWebServer records the bodies).
- [ ] A second, different send uses a different `prompt_id`.
- [ ] A `40927` response surfaces the server message and is not retried.
- [ ] Live against 2.6.0: two identical POSTs with one id produce one turn and
      the same `prompt_id`.

## Out of scope

- An offline queue or durable outbox.
- Attachments changing between retries (a changed body correctly answers
  `40927`).
- `/skills/{name}:activate` idempotency (unused by the app).

## Notes

- `docs/server-api.md` → Prompts → Send (idempotency).
- Ids are opaque; do not parse them.
