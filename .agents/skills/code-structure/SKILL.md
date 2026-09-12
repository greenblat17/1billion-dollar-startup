---
name: code-structure
description: Use when multiple workflows duplicate the same operational logic, when deciding what belongs in actions vs shared services, or when refactoring repeated operational blocks across domain flows. Use when adding new features that share mechanics with existing ones.
---

# Service Layer Architecture

Use a two-layer separation: actions orchestrate domain rules (the "why/when"), while a service layer centralizes reusable operational mechanics (the "how").

This prevents duplicated code, inconsistent behavior, and bugs fixed in one path but not others.

## When to Use

- Multiple callers need the same low-level operation, such as sandbox creation, email sending, or payment processing.
- Operational logic is copied between action files.
- A bug fix in one workflow does not propagate to other workflows performing the same operation.
- A new feature shares mechanics with existing flows.

Do not introduce a service layer when logic is domain-specific and used by only one caller.

## Core Pattern

```text
Orchestration layer (actions)          Service layer (shared mechanics)
├── owns business rules                ├── owns reusable operations
├── owns state transitions             ├── owns provider/SDK interactions
├── owns auth/ownership checks         ├── owns command execution details
├── owns failure classification        ├── owns health checks/readiness
├── owns retries/user-facing errors    └── returns structured results
└── calls service functions
```

Rule of thumb:

- "What this product flow means" stays in actions.
- "How to do this operation reliably" moves to the service layer.

## Design Service Functions

Design composable capability blocks rather than monoliths:

```ts
createManagedSandbox(...)
prepareRepo(...)
detectPackageManager(...)
installDependencies(...)
runBuildCommand(...)
startSandboxRuntime(...)
```

Each function should:

- Accept required data as explicit parameters.
- Return structured outputs, such as `{ ready, previewUrl, proxyPort }`.
- Avoid reaching into database or domain state directly.
- Make failures explicit rather than swallowing errors.

This lets callers choose strict or relaxed behavior for their flow.

## Migration Workflow

1. Write or map the flow in action code so its behavior is clear.
2. Identify repeated operational chunks across callers.
3. Extract only repeated, non-domain chunks into a service.
4. Replace one caller and verify it before migrating the remaining callers.
5. Keep domain policy in actions, including authorization, state transitions, and error classification.
6. Run relevant type checks, linting, and behavioral tests for all migrated flows.

## Avoid

- A god service whose single large method hides the entire control flow.
- A leaky service that mutates domain database state directly.
- Inconsistent arguments, return shapes, or error semantics across service functions.
- Extracting an abstraction that has only one caller and no demonstrated reuse.

Architecture principle: actions orchestrate domain rules, while the service layer centralizes reusable operational mechanics behind composable functions with explicit inputs and structured outputs.
