# Review standards for this repository

Shared by `bmad-build` and `bmad-code-review`. This file says who decides what, then what a
reviewer applies. It does not restate the project skills: it points at them.

## Who answers what

| Question | Who answers | Where it lives |
| --- | --- | --- |
| What the change is for and its acceptance criteria | the plan or ticket for this run | `_bmad-output/` |
| Dated product and architecture decisions | `ai_docs/` | `plans/`, `integrations/`, `product/`, `researches/` |
| How a specific area must be written | project skills | `.agents/skills/` |
| What is true in the code right now | the code | `cmp/server`, `cmp/app`, `cmp/core`, `ai-service`, `infra` |

Reading order for a change: `AGENTS.md` → `ai_docs/README.md` → the dated document for the
area → the project skill for the area → the plan. If the plan contradicts a project skill, the
skill wins and the plan item is rewritten, not the skill. If the change contradicts a dated
decision in `ai_docs/`, say so and propose updating the document; do not overwrite it in silence.

Which skill governs which area:

- Screen, ViewModel, `UiState`, navigation → `cmp-mvvm`, then `cmp-koin` for DI.
- HTTP client from the CMP app → `cmp-ktor-client`.
- Tests → `cmp-test`: `kotlin.test`, run from `cmp/`.
- Logging in shared code → `cmp-kermit`; `:server` keeps SLF4J/Logback.
- Theme, fonts, strings, icons → `cmp-theme`, `cmp-strings`, `cmp-icons`.
- Operational logic repeated across flows → `code-structure` (actions orchestrate, the service
  layer owns the reusable operation).
- Readability pass on the diff → `code-simplifier`.
- Strict maintainability of the diff → `thermo-nuclear-code-quality-review`.
- Live UI verification → `compose-hot-reload` MCP with `[Recomposition]` logs, plus
  `compose-stability-analyzer` when traces are missing.

Two things the project does not have and a reviewer must not assume: no OpenSpec spec layer on
this branch, and no deploy on push or PR.

## Section A — skill conformance

Report only what the project skills decide and a general review cannot see:

- A diff written against a different convention than the skill governing its area, naming that
  skill for each finding.
- `implementation` and `api` used from `:app:shared` where `AGENTS.md` requires the host to own
  the dependency.
- Tests that are not `kotlin.test` in `commonTest`, or a build command run from the repository
  root instead of `cmp/`.
- Logging, string, icon, font, theme, or dependency choices that differ from `cmp-*` skills.
- Shared operational blocks that belong in the service layer by `code-structure`.
- A UI claim verified only by a screenshot, with no recomposition log.
- A product change in `cmp/app` for a task that is not about the prototype.
- CI or deploy work mixed into a list of code tasks.

## Section B — structural strictness

Apply `thermo-nuclear-code-quality-review` in full, on the diff only:

- Look for the move that deletes complexity, not one that rearranges it.
- Do not accept a file crossing 1000 lines in this diff without a stated structural reason.
- Do not accept new spaghetti growth in existing files, including conditionals that encode
  business rules in the wrong layer.
- Keep behavior identical; a structural finding is a proposal, not a license to change output.
