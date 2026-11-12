# Send `turn_number` to the file-history routes

- **ID:** 193
- **Created:** 2026-11-12
- **Depends on:** 189

## Context

Server 2.6.0 (nightly drift run, 2026-11-12; commits `961b90f9` and
`13590cde`) renamed the file-history turn parameter from `turn_id` to
`turn_number` and dropped the alias. The app's `FileHistoryApi` still sends
`?turn_id=N`; the server answers `40001` (`"turn_number: Invalid input"`), so
the turn-changes screen and the before/after file viewer show an error for
every turn.

`turn_number` is a field on the conversation turn header (the engine's turn
number), not the 0-based ordinal the app used to pass.

## Description

- Add `turn_number` to the conversation turn model and to any route/argument
  that identifies a turn for file history.
- `FileHistoryApi.getChanges` and `getContent` send `turn_number=<n>`.
- `TurnChangesViewModel` and `TurnFileContentViewModel` read the number from
  the turn they were opened for (navigation argument or the loaded turn), not
  from the old ordinal.
- Update the fixtures in `FileHistoryApiTest`, `TurnChangesViewModelTest`,
  `TurnFileContentViewModelTest` (and the fakes) to the new query name.
- Keep the response DTOs (`changes[]`, `recorded`, `content`) unchanged; they
  are still correct.

## Acceptance criteria

- [ ] MockWebServer asserts the paths
      `.../file-history/changes?turn_number=N` and
      `.../file-history/content?turn_number=N&path=…&phase=…`.
- [ ] No source or test sends `turn_id` to a file-history route (`grep`
      clean).
- [ ] Opening a turn's changes from a completed turn shows its files (or the
      recorded:false empty state) with no 40001 snackbar.
- [ ] Live against 2.6.0: `?turn_number=N` returns `code:0` for a turn with
      recorded changes, and the before/after viewer loads.

## Out of scope

- The `turn_number` semantics for forked/undone turns beyond reading the
  header.
- Any new diff UI.

## Notes

- `docs/server-api.md` → Files → Turn file history.
- The server returns `recorded:false` when the turn's snapshot was not kept;
  that is an empty state, not an error.
