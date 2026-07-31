# Wire the data layer through Hilt instead of hand-assembly

- **ID:** 067
- **Created:** 2026-07-31
- **Depends on:** none

## Context

Found in the 2026-07-31 tech-debt audit (flagged independently by the
data-layer and UI audits). `ChatRepository` (`ChatRepository.kt:27-32`) news
up six collaborators — `ApiClient`, `ChatMessageMapper`, `ChatApi`,
`ModelsRepository`, `SessionPrefsStore`, `SessionSocket` — because they're
`internal` and can't be injected. Consequences:

- The "thin facade" is also the object-graph root; collaborators can never
  be faked from outside.
- `ApiClient` — documented in `AGENTS.md` as *"the shared HTTP scaffold"* —
  is instantiated three times (also `SessionsRepository.kt:20` and
  `ConnectionChecker.kt:17`). It's cheap and near-stateless today, but any
  future state would silently triplicate.
- `TodoParser` is `@Singleton @Inject` yet both consumers bypass Hilt with
  default arguments that re-create `Json { ignoreUnknownKeys = true }`
  (`ChatViewModel.kt:55`, `ChatTimeline.kt:34`) — a third copy of the config
  next to `AppModule.kt:58`. `Todo.kt:20` even claims "Uses the shared DI
  Json"; it doesn't. If the DI `Json` ever gains settings, the todo path
  silently diverges.

## Description

- Make `ChatApi`, `ModelsRepository`, `SessionSocket`, `SessionPrefsStore`,
  `ChatMessageMapper` injectable (`@Singleton @Inject constructor`, drop
  `internal` where it blocks injection) and provide a single `ApiClient`
  from `AppModule`; `SessionsRepository` and `ConnectionChecker` take it
  from the graph too.
- Inject `TodoParser` at both call sites; delete the duplicated
  `Json { ignoreUnknownKeys = true }` configurations and fix the stale
  `Todo.kt:20` comment.
- Keep every constructor `@Inject` with the same parameter shapes so tests
  keep building objects directly with fakes (`ChatRepositoryTest` must need
  no harness rewrite — constructor wiring changes are fine, fake-strategy
  rewrites are not).

## Acceptance criteria

- [x] One `ApiClient` instance in the graph (grep: `ApiClient(` appears only
  in `AppModule`).
  *(`grep -rn "ApiClient(" app/src/main` → only the class declaration
  (`ApiClient.kt:26`) and the single construction (`AppModule.kt:64`);
  unit tests hand-build the graph — the pre-existing real-collaborator
  strategy — via `testutil/TestGraph.kt`.)*
- [x] No `Json { ... }` configuration outside `AppModule`.
  *(In `app/src/main`: only `AppModule.kt:59`; the two other hits are
  comments. `ChatMessageMapper`'s `Json(json) { prettyPrint = true }`
  derives from the injected instance — not a duplicated base config.)*
- [x] `./gradlew :app:testDebugUnitTest` green.
  *(292 tests, 18 classes, 0 failures.)*

## Implementation notes (2026-07-31)

`ChatApi`, `ModelsRepository`, `SessionSocket`, `SessionPrefsStore` and
`ChatMessageMapper` are now public `@Singleton @Inject constructor`s with
unchanged parameter shapes; `AppModule` provides the single `ApiClient`.
`ChatRepository` is a pure facade over its four injected collaborators,
`SessionsRepository`/`ConnectionChecker` take `ApiClient` + `Json` from the
graph (the `ConnectionChecker` hand-rolled `Request.Builder` stays, per the
ticket's gray zone). Both `TodoParser(Json { … })` defaults are gone:
`ChatViewModel` injects the parser and passes it to its `ChatTimeline`;
`SubagentStreamStore` injects it too and threads it through the private
`Stream` class (three construction sites). The stale `Todo.kt` claim is now
true and says so. Tests keep the real-collaborator strategy against
MockWebServer — the hand-assembly that used to live inside each repository
moved to `testutil/TestGraph.kt`, so each call site is a one-liner and the
graph shape is written down exactly once.

## Out of scope

- Splitting the single app module or introducing `@Binds` interface layers —
  the collaborators stay concrete classes; this ticket removes hand-
  assembly, nothing more.
- `ConnectionChecker`'s hand-rolled `Request.Builder` (`ConnectionChecker.kt:
  33-42`) — a documented gray zone (unsaved settings, invalid-token header
  case); leave it.

## Notes

- After this lands, the `ChatRepository` facade genuinely is what
  `AGENTS.md` already claims it is — no doc change needed if the structure
  finally matches the description.
