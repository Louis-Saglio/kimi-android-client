# Make prompt sends retry-safe

- **ID:** 194
- **Created:** 2026-11-12
- **Depends on:** none

## Context

Server 2.5.3 (re-verified live by the nightly drift run on 2026-11-13; the
`prompt_id` field itself dates from 0.37.2) reserves a client-chosen
`prompt_id` in the agent's history. Resending the same `prompt_id` answers
`40927` (`prompt_id '…' is already in use`, with `stack`) — unlike 2.6.0, it
does **not** replay the first result. Live capture today: the first POST
returned `code:0` with the id echoed as `prompt_id`/`user_message_id`; an
identical resend one turn later answered `40927`.

The app generates no `prompt_id` (`ChatApi.postPrompt`), so a send that times
out on a mobile network and is retried can create a duplicate turn. The
existing send-recovery logic handles `40931`/`40932` but cannot recognise its
own earlier send.

The `40927` is still a usable idempotency signal on 2.5.3: because the app
would mint a fresh ULID for every distinct send, a conflict can only mean an
earlier retry of *this* send already landed. Treating it as "accepted" makes
the retry safe without any server-side replay.

The 2.6.0-era version of this ticket described server-side idempotent replay;
that behaviour is not on the running server and was withdrawn with the 2.6.x
release.

## Description

- Generate a stable prompt id per send (a UUID or ULID string is enough; the
  server accepts any string) and send it as `prompt_id`.
- Keep that id for every retry of the same send; a new send gets a new id.
- Treat `40927` as **already accepted**: do not surface an error and do not
  retry. Reconcile the local echo by matching the id in the prompt ACK, the
  `prompt.upsert` transcript entity, or `GET .../prompts`; if the prompt is no
  longer active/queued, close the bubble as ended.
- Use the same id for the direct steer route (`POST .../steer`) if the
  composer's steer path is reused (ticket 185).
- Keep the retry button behaviour: it resends the same body and id.
- The local echo is keyed by the prompt id; the server echoes it as
  `prompt_id`/`user_message_id`, and the transcript's user frame carries it in
  `promptIds`.

## Acceptance criteria

- [ ] `ChatApi.postPrompt` always includes a non-blank `prompt_id`.
- [ ] A simulated timeout followed by a retry sends the same `prompt_id` on
      both requests (MockWebServer records the bodies).
- [ ] A second, different send uses a different `prompt_id`.
- [ ] A `40927` response is treated as accepted: no error snackbar, no second
      bubble, and the send state converges to sent.
- [ ] Live against 2.5.3: two identical POSTs with one id answer `code:0` then
      `40927`, and the session gains exactly one turn.

## Out of scope

- An offline queue or durable outbox.
- Attachments changing between retries.
- Server-side replay semantics (not available on 2.5.3).

## Notes

- `docs/server-api.md` → Prompts → Send (`prompt_id`, `40927`).
- Ids are opaque; do not parse them.
