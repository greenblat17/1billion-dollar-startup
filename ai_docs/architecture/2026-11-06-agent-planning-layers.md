# 2026-11-06. Planning layers and skill authority

Decision: the repository has two layers, each with its own area of responsibility. They do not
duplicate each other, and the order of precedence is fixed.

| Question | Who answers it | Where it lives |
| --- | --- | --- |
| What this change is for and its acceptance criteria | the plan or ticket for the current run | `_bmad-output/` |
| Dated product and architecture decisions | `ai_docs/` | `plans/`, `integrations/`, `product/`, `researches/` |
| How a specific area must be written | project skills | `.agents/skills/cmp-*`, `code-structure`, `code-simplifier`, `thermo-nuclear-code-quality-review` |

Reading order for any change: `AGENTS.md` → `ai_docs/README.md` → the dated document for the area →
the project skill for the area → the plan. The plan says what to build, the skill prescribes the way
to write it, `ai_docs` records why. If a plan item contradicts a project skill, the skill wins and
the plan item is rewritten, not the skill. If a change contradicts a dated decision, say so and
propose updating the document rather than rewriting the history in silence.

Planning artifacts (plans, tickets, review files) stay in `_bmad-output/`. Decisions that outlive the
session are written to `ai_docs/` as a dated file.

Language: everything an agent writes is in English, including documents in `ai_docs/`. Files already
written in Russian are left as they are.

## What this means for review

BMAD lenses look at intent, acceptance criteria, missing content and verification gaps. The project
skills decide how code is written. The split is recorded in `_bmad/custom/review-standards.md`, and
both lenses read that file:

- `skill-conformance` — only what the project skills decide, with the governing skill named for each
  finding (`implementation` vs `api`, `kotlin.test`, Kermit versus SLF4J in `:server`, resources under
  `composeResources`, the service layer by `code-structure`).
- `structural-quality` — strict structural check by `thermo-nuclear-code-quality-review`, on the diff
  only, without changing behavior.

The other half of the comparison is the contract layer on the `openspec` branch: there the spec
describes the current behavior of the system, and a delta merges into `openspec/specs/`. That layer is
not replaced by `_bmad-output/` and is not carried into this branch.
