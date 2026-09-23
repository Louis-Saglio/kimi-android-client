# Snapshot usage authoritative for the context ring

- **ID:** 161
- **Created:** 2026-09-23
- **Depends on:** none

## Context

Since 0.38.0 the snapshot's `session.usage` is real on every read path
(the all-zero placeholder era ended in 0.32.0), and the usage schema made
`total_cost_usd`, `context_limit`, `turn_count` optional — the snapshot
omits cost and turn count (docs/server-api.md:1021-1022, 2019). Ticket
093 built placeholder machinery for the zero-usage era (zero→null
mapping + a placeholder-gated conditional `/status` re-read), and 107
already had to un-gate the `/status` seed because of this server change.
Against the latest server the placeholder gate is dead code that makes
the cold-start context ring needlessly indirect: the snapshot can be the
authority.

## Description

**1. Delete the placeholder machinery.** In the snapshot mapping
(`ChatMessageMapper.toDomain(SnapshotData)` / `applySnapshot`): remove
the zero→null usage mapping and the placeholder-gated conditional
`/status` re-read; the snapshot's usage seeds the context ring directly
on cold start. Default the newly-optional fields (`total_cost_usd`/
`turn_count` absent → null; `context_limit` per 100's zero/unknown
rule).

**2. Keep the live path.** `/status` + `agent.status.updated` remain the
live fill during streaming — this ticket changes only the cold-start
seed. No behavior change for the user except a correct ring on first
paint without an extra request.

**3. Tests.** Update 093/100/107-era tests: snapshot with real usage
rings immediately; snapshot omitting optional fields renders unknown
(zero-limit rule intact); delete the tests that asserted the placeholder
gate.

## Acceptance criteria

- [ ] Updated unit tests pass; existing suite green.
- [ ] On device: cold-open a busy session — the context ring shows real
      usage from the snapshot alone (verify no `/status` call fires for
      the seed via Logcat).
- [ ] `flock /tmp/kimi-android-gradle.lock ./gradlew :app:testDebugUnitTest
      :app:assembleDebug --max-workers=1 --no-daemon` passes; release APK
      published to the file server per AGENTS.md.

## Out of scope

- Displaying `total_cost_usd`/`turn_count` (the server omits them from
  the snapshot; no surface today).
- Removing the `/status` live fill (still the streaming path).
- The list-endpoint usage fields (unchanged by this ticket).

## Notes

- Pure code-health: no new UI, no new DTO fields — deletions plus
  mapping simplification.
