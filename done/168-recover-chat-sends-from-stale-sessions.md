# Recover chat sends from stale sessions

- **ID:** 168
- **Created:** 2026-10-22
- **Depends on:** none

## Context

Found by the nightly server drift run from 2.2.0 to 2.3.0 on 2026-10-22.
Upstream commit `df0ff3c5492e` adds cross-process occupancy errors
`40931 SESSION_OCCUPIED` and `40932 SESSION_STALE` to session writes,
including prompt submission. Commit `18c6d6427295` adds
`POST /api/v1/sessions/{id}:reload`, which reloads a session after another
client changes it. These envelopes and routes were verified from the 2.3.0
source; the drift run did not induce a conflict on the live service.

The Android error boundary preserves both codes as `DataError.Api`, so decoding
does not break. Chat keeps the composer text and offers Retry after a failed
send. It has no stale-session recovery, though, and the raw server error is the
only guidance. This is a real phone case when Louis has the same session open
in the CLI or web client.

Retrying a prompt POST automatically is unsafe because it is not idempotent.
The recovery must reload and resynchronize first, then let the user explicitly
retry. An occupied session should explain the conflict rather than attempting
a takeover.

## Description

Add the session reload call using the existing session DTO and repository
conventions. In the chat send failure path, handle `40932` by calling
`:reload`, then rebuilding chat state from a fresh snapshot. Preserve the
composer text and attachments throughout, and tell the user that the session
was reloaded and they can retry the send. If reload or snapshot recovery
fails, keep the draft and surface that failure; do not resubmit the prompt.

Handle `40931` with an actionable occupied-session message that asks the user
to close the other client before retrying. Do not call `:reload` for this code.
Other send errors keep their existing behavior.

## Acceptance criteria

- [ ] A send failing with `40932` triggers one reload and one fresh snapshot
      resync, leaves the draft intact, and does not issue another prompt POST.
- [ ] Successful recovery tells the user to retry; failed reload or resync
      leaves the draft intact and reports the failure.
- [ ] A send failing with `40931` shows the occupied-session guidance without
      calling reload or retrying the prompt.
- [ ] Existing successful sends, steers, and unrelated error paths are
      unchanged.
- [ ] Unit tests cover both new codes and the reload failure path, and
      `:app:testDebugUnitTest` passes.

## Out of scope

- Automatic retries of prompt submission or steering.
- Occupancy recovery for abort, compact, archive, delete, and session-profile
  writes.
- Force-unlocking, taking over another process, or displaying lock-holder
  tokens and other holder metadata.
- External-change reminder content and rating APIs.

## Notes

Relevant code: `data/network/Envelope.kt`, `DataError.kt`,
`data/sessions/SessionsRepository.kt`, `data/chat/ChatRepository.kt`, and
`ui/chat/ChatViewModel.kt`. The current send failure already preserves the
composer and emits `Effect.SendFailed`; build on that path rather than adding
retries to `ApiClient`.

## Implementation notes

**2026-10-28 —** Envelope `details` → `DataError.Api`; `SessionsRepository.reloadSession`;
the send-failure ladder posts `:reload`, applies one fresh snapshot resync and
asks the user to retry, while 40931 explains the holder. The draft is preserved
and nothing resubmits automatically. Tests: `ChatSendRecoveryTest`,
`EnvelopeTest`, `SessionsRepositoryTest`.
