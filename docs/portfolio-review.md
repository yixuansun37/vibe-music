# Portfolio review and verification evidence

## Scope

Reviewed the complete generator implementation in commit
`ea63e4f72962b609dba7bace4d3b97414033a70c` (19 changed files relative to
`0dcc3ad`), together with the preceding authenticated save flow. No new product
features were added in this review. The verifier was corrected to save its House
20-minute plan rather than the first Hip-hop plan, closing a test-evidence gap.

## Exact AI boundaries

| Location | Responsibility | Actual verification |
| --- | --- | --- |
| `AgentController` | Inherited `ChatClient` with read-only `MusicAgentTools`, streaming chat and in-memory conversation history | Source reviewed; no successful live provider response verified |
| `MusicAgentTools.practiceCandidates` | Java/MyBatis query of existing style-matched songs and artist enrichment; no `@Tool` annotation on this method | Real MySQL generation checks passed |
| `DancePracticeAiRanker.rank` | Optional existing `ChatModel` via `ChatClient.create(...).prompt()...call().content()`; requests only candidate ID order | Mock-model tests passed; live LLM call unverified |
| `DancePracticePlanner` | Validate durations, deduplicate, select whole songs, enforce total budget/50-song limit and create three preview sections | Pure algorithm tests and real-service checks passed |
| Existing save API/dialog | Verify real song IDs, require existing user session and save playlist/bindings transactionally | Mock-based regression tests plus real API/browser/SQL checks passed |

The AI result must contain every candidate ID once, as integers from the original
whitelist. AI cannot add metadata, BPM, audio URLs or duration estimates. Invalid
responses, disabled AI, missing credentials and provider failures use explicit
`DETERMINISTIC` mode. The generator is not a newly implemented autonomous agent.

## Automated tests

The latest `clean verify` completed 130 cases with zero failures, errors or skips:

| Class | Cases | Test boundary |
| --- | ---: | --- |
| `DancePracticePlannerTest` | 34 | Pure deterministic algorithm |
| `DancePracticeServiceTest` | 10 | Mock catalog/ranker, planner exercised where appropriate |
| `DancePracticeControllerTest` | 21 | Real validation/JWT/interceptor/role rules; mock Redis/service |
| `DancePracticeAiRankerTest` | 25 | Mock `ChatModel`/provider; not a remote LLM |
| `DancePlaylistSaveControllerTest` | 30 | MVC/authentication with mock dependencies |
| `DancePlaylistSaveServiceTest` | 10 | Spring transaction proxy and mock transaction/persistence collaborators |

The transaction tests verify rollback requests, not physical MySQL rollback after
an injected mid-transaction failure. Real-service checks separately verify
successful persistence and invalid selections rejected without partial writes.

Frontend production build passed. `pnpm type-check` still reports the same four
pre-existing diagnostics: unused `viteEnv` in `build/plugins.ts:8`, unused `rule`
in `ResetPasswordForm.vue:47`, and `void | number[]` assignment errors in
`themeTools.ts:48,65`. These were disclosed rather than fixed outside this scope.

## Real-service and browser evidence

- Real MySQL 8 and Redis 7, generated local account, actual JWT/Redis login session.
- Actual catalog count: 499 songs. No songs or audio resources were fabricated.
- Three styles generated; metadata and integer millisecond totals checked against
  database-backed song details, with no duplicate IDs or total-budget overrun.
- House 20-minute plan: six songs, 1,199,660 ms actual duration, 340 ms shortfall.
- Hip-hop 90-minute plan: 14 songs, 3,017,560 ms actual duration; insufficient
  candidate duration is reported rather than invented songs being added.
- Corrected Python verifier saves House 20 and checks MySQL and HTTP details.
- A real browser selects House/20 through the UI, generates, saves through the
  existing dialog, and retrieves the saved playlist. MySQL is also checked directly.
- No API mocks, artificial AI replies or playback-success claims are used in
  those real-service checks. Screenshots are under `docs/screenshots/`.

Private verification reports and runtime credentials remain in ignored
`dev/demo/.local/`. Screenshot and report IDs are examples from individual runs,
not fixed IDs required for reproduction.

## Remaining limitations and reproduction requirements

No blocking defect was found for the documented local, non-AI generation/save
demo. A reviewer still needs a full JDK, Node/pnpm, Python, working Docker Compose,
dependency/image download access and free default ports. Start the backend and
wait for readiness before verifying. See the root README and `dev/demo/README.md`.

The existing frontend backend URL is `localhost:8080`: a browser must reach both
frontend and backend, not just a forwarded frontend port. The admin app, real
DashScope response, inherited AI chat and audio playback have not been validated.
MinIO and SMTP are not started by this demo.

Music-style mappings are heuristics; there is no BPM/energy/dance annotation.
Section proportions are approximate and not independent hard budgets. Greedy
selection is not optimal. The schema stores no owner/privacy, section or ordering
information. A coincidentally matching read order proves no ordering guarantee.

Inherited security debt remains: legacy password hashing in `UserServiceImpl`, a
hard-coded signing key in `JwtUtil`, and public AI routes in the existing
interceptor/path configuration. Values are intentionally not reproduced here.
These are reasons to keep the portfolio demo local, not claims of production
readiness. Original license/notices/attribution were preserved.
