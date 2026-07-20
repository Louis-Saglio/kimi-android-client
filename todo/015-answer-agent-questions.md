# Answer agent questions from the chat screen

- **ID:** 015
- **Created:** 2026-07-20
- **Depends on:** 013

## Context

Product discussion 2026-07-20 (parity batch A). The agent can ask the user a
structured question mid-turn (the web client renders it from
`/sessions/{id}/questions/{qid}`). The Android app has no surface for this:
a session waiting on a question is dead until Louis reaches a laptop. This is
the highest-value gap in batch A alongside steer. Wire protocol (surfacing
event, fetch, answer, dismiss) is documented by ticket 013
(`docs/server-api.md`, Questions section).

## Description

- Parse the question-surfacing WS event (per 013) into a new `SessionEvent`;
  track the pending question in chat state (fetch full detail via
  `GET .../questions/{qid}` if the event payload is partial).
- Render the pending question above the composer: question text, its options
  if structured, free-text answer if supported (per 013's findings).
- Answer via `POST .../questions/{qid}` and dismiss via
  `POST .../questions/{qid}:dismiss`; on the resolved event, remove the
  question UI and let the turn continue.
- Handle multiple/concurrent questions only if 013 shows the server can
  produce them; otherwise a single pending question at a time.

## Acceptance criteria

- [ ] A question raised mid-turn renders in the chat within ~1 s (unit-tested
  at ViewModel level: socket event → question item in state).
- [ ] Answering posts the documented body to `questions/{qid}` and clears the
  pending question on the resolved event (unit-tested).
- [ ] Dismissing posts to `questions/{qid}:dismiss` and clears the pending
  question (unit-tested).
- [ ] The pending question survives a history refetch (re-derived from the
  documented source of truth, not lost on `turn.ended`).
- [ ] `./gradlew :app:assembleDebug` succeeds with all unit tests green.

## Out of scope

A questions entry in the session list (016 covers approvals only), goal-mode
questions, any question type the 013 spike does not observe live.

## Notes

- Mirror the approval flow (007) structurally — request surfaces via WS,
  decision via REST — but keep the UI distinct: questions are input, not a
  yes/no guard.
