# Context usage ring in chat top bar

- **ID:** 041
- **Created:** 2026-07-26
- **Depends on:** none

## Context

The web UI shows how much of a session's context window is used; the Android
app does not, so Louis cannot tell on his phone when a session is approaching
compaction. The server already exposes everything needed: `GET
/api/v1/sessions/{id}/status` returns `context_tokens`, `max_context_tokens`
and `context_usage`, and the volatile WebSocket event `agent.status.updated`
(model/usage variant) carries `contextTokens` / `maxContextTokens` live during
a turn (docs/server-api.md). The app today parses only `busy` from `/status`
and ignores `agent.status.updated` frames entirely.

The web UI renders this as a 16px circular progress ring in the composer
toolbar, with used/max/pct in a hover tooltip and a `/compact` chip at ≥80%
(`apps/kimi-web/src/components/chat/Composer.vue` + `ui/ContextRing.vue`).

Decisions from the UX discussion (2026-07-26):

- **Top app bar, not the composer.** The user picked screen-level chrome over
  web-parity composer placement. The ring sits in the actions row, to the
  *left* of the working spinner/stop, so ordering stays stable as the spinner
  appears and disappears. A session-list caption was considered and deferred.
- **Ring only, tap for details.** Exact web-parity on the indicator itself —
  no text in the bar. Mobile has no hover, so the numbers move behind a tap
  (dialog) instead of a tooltip. A "ring + % label" variant was rejected in
  favor of the cleaner bar.
- **≥80% shifts to error color only.** The web's `/compact` affordance is
  deliberately excluded — triggering compaction from the app is its own
  feature.
- Single end-to-end ticket: an earlier data-layer/UI split was rejected
  because the data-only ticket would have had no user-visible change.

## Description

1. **Data layer** (`app/.../data/chat/`):
   - Add `context_tokens` (`Long`) and `max_context_tokens` (`Long`) to
     `SessionStatusData` in `ChatDtos.kt`; surface them from the existing
     `/status` fetch in `ChatRepository` alongside `busy`.
   - Add a DTO for the `agent.status.updated` model/usage WS variant
     (`contextTokens`, `maxContextTokens`; both optional — the same event type
     also carries phase updates that must not clobber the stored values) and
     parse it in the repository's frame handling, exposing updates on the
     session flow.
2. **ViewModel** (`ChatViewModel.kt`): expose an immutable
   `contextUsage: ContextUsage?` on `UiState` (used tokens, max tokens;
   `null` until the first value arrives). Seed it from `/status` on load,
   update it from the live WS events.
3. **UI** (`ChatScreen.kt` or a new file under `ui/chat/`):
   - A small ring in the `TopAppBar` actions row, before the spinner/stop:
     ~20dp arc inside a 48dp touch target, drawn with `Canvas` — `primary`
     arc, `outlineVariant` track. Fill percentage follows web parity:
     `ceil(used / max × 100)`, clamped to 0–100, so sub-0.5% usage still
     shows a sliver and momentary over-estimates don't break the arc.
   - Hidden entirely while `contextUsage` is `null` (no fake empty ring).
   - Tap opens a dialog with the exact numbers: used / max tokens and
     percentage (e.g. `25.5k / 1M tokens · 2%`), reusing the existing
     token-formatting style of `formatContextSize`.
   - At ≥80% the arc (and the dialog percentage) shifts to
     `MaterialTheme.colorScheme.error`.
   - Accessibility: the ring's `contentDescription` carries the same numbers
     as the dialog (e.g. "Context usage: 25.5k of 1M tokens, 2%").
4. **Tests**: repository parsing of the `/status` fields and the
   `agent.status.updated` model/usage variant (including that a phase-variant
   frame leaves stored values untouched), ViewModel state seeding/updates,
   and the fill-percentage clamp/ceil logic.

## Acceptance criteria

- [ ] Opening a chat shows the ring in the top bar actions row (left of the
      spinner/stop) once `/status` has returned, filled proportionally to
      `context_tokens / max_context_tokens`.
- [ ] Sending a prompt and letting a turn run visibly grows the ring as
      `agent.status.updated` events arrive, without a screen reload.
- [ ] Tapping the ring opens a dialog showing exact used / max tokens and the
      percentage; dismissing it returns to the chat.
- [ ] With usage ≥80% the arc renders in the error color (verify by
      preview/test or a session with a small context model).
- [ ] The ring is absent before the first status data arrives.
- [ ] TalkBack announces the ring with the used/max/percentage values.
- [ ] New unit tests pass: `./gradlew :app:testDebugUnitTest`.
- [ ] Release APK built and copied to the file server per AGENTS.md.

## Out of scope

- Any compaction trigger (the web's `/compact` chip) — a separate feature
  with its own UX questions.
- Context usage caption on session-list rows (the list payload already
  carries `usage.context_tokens` / `context_limit`; deferred as a possible
  follow-up ticket).
- A persistent percent label next to the ring (rejected in discussion).

## Notes

- Protocol reference: docs/server-api.md — "Live status" section for the REST
  fields, "Agent status" for the volatile WS variants. Keep the doc accurate
  if parsing reveals drift (AGENTS.md).
- Web reference implementation: `apps/kimi-web/src/components/chat/Composer.vue`
  (search `ContextRing`, `pct`, `ctxTooltip`) in MoonshotAI/kimi-code.
- The 500-line diff budget is explicitly relaxed for this ticket (user
  decision, 2026-07-26); still keep the change scoped to this feature.
