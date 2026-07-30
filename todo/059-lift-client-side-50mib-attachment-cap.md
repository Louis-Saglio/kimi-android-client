# Lift the client-side 50 MiB attachment pick cap

- **ID:** 059
- **Created:** 2026-07-30
- **Depends on:** none

## Context

`AttachmentStore` refuses picks over `MAX_PICK_BYTES = 50 MiB`
(`AttachmentStore.kt:49-54`) with the snackbar "… exceeds the 50 MiB upload
limit" (`ChatViewModel.kt:378-379`). That limit mirrored the server's upload
cap. Server 0.31.0 **removed** the cap: uploads now stream to disk and a
55 MiB upload returns HTTP 200 with the exact `size` (`docs/server-api.md`,
Files section, verified 2026-07-30); the 413/41301 error no longer exists on
`/files`. The client-side cap is now purely self-imposed and stale — it blocks
files the server would happily accept (e.g. a screen recording or a large log
Louis wants the agent to read).

The cap is not useless in principle: an unbounded pick can still OOM the app
during cache copy or take forever on Tailscale. But 50 MiB is no longer
"the server's own limit", and the rationale (and the error message) must stop
claiming it is.

## Description

1. Raise `AttachmentStore.MAX_PICK_BYTES` to a new self-imposed client limit
   (suggestion: 256 MiB — comfortably above realistic phone picks, still a
   guard rail against copying multi-GB videos into the cache). Rename if the
   constant's name/comments reference the server limit.
2. Update the `Effect.AttachmentPickFailed` message to describe the limit as
   the app's own pick limit, not the server's ("… is too large to attach
   (limit 256 MiB)").
3. Update the stale references: `AGENTS.md` ("content resolver reads capped at
   50 MiB, the server's own limit") and `docs/server-api.md` if it cites the
   app cap as mirroring the server.
4. Keep the streaming `readCapped` guard and the 41301 error mapping in the
   data layer if any exists (harmless dead code for a code the server no
   longer emits — remove only if it is a one-liner; do not touch unrelated
   error paths).

## Acceptance criteria

- [ ] A file between 50 MiB and the new limit can be picked, cached, uploaded,
      and attached to a prompt (unit-test the store boundary with a fake
      content resolver over the old limit; the live upload path is covered by
      the server-side verification in `docs/server-api.md`).
- [ ] A file over the new limit still fails with a snackbar whose text matches
      the new wording; unit test updated.
- [ ] `grep -rn "50 MiB" app/src/main AGENTS.md` returns no stale
      server-limit claims.
- [ ] `./gradlew :app:testDebugUnitTest` passes.

## Out of scope

- Chunked/resumable uploads, upload progress UI, compression of large media.
- Changing the download/open-file paths.
- Removing the cap entirely (a client-side guard rail stays).

## Notes

- Server change: upstream commit "remove the 50 MiB upload size cap and stream
  uploads to disk" (`@moonshot-ai/kimi-code@0.31.0`); `POST /api/v1/files` has
  no size limit, `fileSize: Number.MAX_SAFE_INTEGER`.
- The doc's Files section (0.31.0) shows the verified 55 MiB upload envelope.
