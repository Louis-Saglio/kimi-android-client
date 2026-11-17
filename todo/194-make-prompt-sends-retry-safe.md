# Make prompt sends retry-safe

- **ID:** 194
- **Created:** 2026-11-12
- **Depends on:** none

## Context

Server 2.6.3 (nightly drift run, 2026-11-17, after the 2.6.x line was reinstated
from the 2026-11-13 withdrawal) makes `prompt_id` on the prompt POST and the
direct `/steer` route idempotent. Resending the same body with the same id
returns the first result without submitting again, and a different body with
the same id answers `40927` ("prompt_id '…' is already used by a different
request"). Records are kept in memory per session (256, oldest ended evicted;
cleared on restart). Verified live on 2.6.3: an identical resend returned the
same `prompt_id` and `created_at`; a changed body answered `40927` with no
`details` and no `stack`. Requests without `prompt_id` have no protection.

The app generates no `prompt_id` (`ChatApi.postPrompt`), so a send that times
out on a mobile network and is retried can create a duplicate turn. The
existing send-recovery logic handles 40931/40932 but cannot recognise its own
earlier send.

The 2.5.3-scoped version of this ticket treated `40927` as "already accepted"
because that release only reserved the id. On 2.6.3 the server replays the
first result, so the retry is safe without that workaround, and a `40927` is a
real client bug (same id, different body).

## Description

- Generate a stable prompt id per send (a UUID or ULID string is enough; the
  server accepts any string) and send it as `prompt_id`.
- Keep that id for every retry of the same send; a new send gets a new id.
- On a timeout followed by a retry, send the same body and id. A `code:0`
  replay returns the first `prompt_id` and `created_at`; treat it as the
  original acceptance and do not append a second echo.
- Treat `40927` as terminal: the id was reused with a different body. Surface
  the server message, do not retry, and do not append a second echo.
- Use the same id for a direct steer (`POST .../steer`) if the composer's steer
  path is reused (ticket 185).
- The local echo is keyed by the prompt id; the server echoes it as
  `prompt_id`/`user_message_id`, and the later `user_message` item carries it in
  `prompt_ids`.
- Keep the retry button behavior: it resends the same body and id.

## Acceptance criteria

- [ ] `ChatApi.postPrompt` always includes a non-blank `prompt_id`.
- [ ] A simulated timeout followed by a retry sends the same `prompt_id` on
      both requests (MockWebServer records the bodies).
- [ ] A second, different send uses a different `prompt_id`.
- [ ] A `40927` response surfaces the server message and is not retried.
- [ ] A replayed `code:0` response (same id and `created_at`) appends no second
      echo.
- [ ] Live against 2.6.3: two identical POSTs with one id return the same
      `prompt_id`/`created_at` and the session gains exactly one turn.

## Out of scope

- An offline queue or durable outbox.
- Attachments changing between retries (a changed body correctly answers
  `40927`).
- `/skills/{name}:activate` idempotency (unused by the app).

## Notes

- `docs/server-api.md` → Prompts → Send (idempotency).
- Ids are opaque; do not parse them.
