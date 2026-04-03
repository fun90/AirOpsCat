## Context

AirOpsCat is an existing Quarkus 3.24.4 application with broad operational scope: console rendering through Qute, REST controllers for admin and public APIs, MySQL persistence through Panache and Hibernate ORM, SSH-driven remote operations, protocol-specific deployment builders, and programmatic scheduled tasks. The current behavior is spread across controllers, services, configuration, and templates, which makes future changes expensive to reason about because there is no stable requirement baseline in `openspec/specs/`.

This change does not introduce new runtime behavior. Instead, it converts the current code structure into a specification baseline organized around business capabilities that map to how the repository is already divided: security and console access, account and subscription flows, server and node deployment, domain and DNS management, and platform operations.

## Goals / Non-Goals

**Goals:**
- Establish capability boundaries that match the current repository structure and production behavior.
- Capture user-visible and operator-visible requirements for the major workflows already implemented.
- Make future OpenSpec deltas safer by giving subsequent changes clear spec targets instead of relying on source spelunking.
- Cover cross-cutting concerns that repeatedly appear in the codebase, including roles, background jobs, deployment orchestration, and external integrations.

**Non-Goals:**
- Refactor Java packages, controller routes, or service layering.
- Exhaustively document every DTO field, persistence column, or helper class.
- Change authentication, scheduling, deployment, backup, or notification behavior.
- Replace README or code comments with OpenSpec; this is a behavioral contract, not line-by-line documentation.

## Decisions

### Decision: Organize baseline specs by stable capability domains instead of package-by-package mirrors

The change groups behavior into five capability domains that align with how operators experience the system and how future changes are likely to be proposed. This is more durable than mirroring package names because package layouts can shift while business capabilities remain stable.

Alternatives considered:
- Mirror the Java package tree directly. Rejected because it would create low-value specs tied to implementation details such as `service/traffic` or `service/ssh`.
- Create one large platform spec. Rejected because later deltas would become hard to scope and review.

### Decision: Treat the current implementation as the source of truth for baseline requirements

The repository already contains the authoritative behavior in routes, services, scheduled task definitions, and templates. The baseline specs will summarize what the system already does instead of proposing aspirational capabilities that are not present in code.

Alternatives considered:
- Infer intended product behavior from README and other docs. Rejected because repository documentation can lag the implementation.
- Write abstract domain specs independent of current endpoints. Rejected because the user explicitly asked to analyze the current project structure.

### Decision: Focus each requirement on externally observable behavior

Requirements are written so they can guide future tests and deltas: route accessibility, lifecycle actions, synchronization flows, deployment operations, and scheduled or manual administrative tasks. Internal helper methods and library-specific details are intentionally omitted unless they materially affect behavior.

Alternatives considered:
- Document internal implementation classes as requirements. Rejected because OpenSpec should constrain behavior, not lock incidental implementation.

### Decision: Preserve room for future deltas by keeping baseline requirements broad but testable

Each capability spec covers the major workflows already in the codebase without duplicating every endpoint signature. This keeps the baseline maintainable while still providing normative scenarios for review and change planning.

Alternatives considered:
- Enumerate every endpoint as its own requirement. Rejected because it would produce noisy specs that are hard to evolve.

## Risks / Trade-offs

- [Baseline is too broad] → Keep requirements anchored to observable workflows and use scenarios to pin down representative behaviors.
- [Baseline misses niche endpoints] → Cover the major business areas now and let later deltas extend specs when those endpoints matter.
- [Specs drift from implementation over time] → Future changes should update the relevant capability spec as part of implementation work.
- [Operators assume the spec is exhaustive] → State clearly in proposal and tasks that this establishes the initial behavioral baseline, not complete API reference material.

## Migration Plan

No deployment or rollback work is required because this change only adds OpenSpec artifacts under `openspec/changes/`. After review, the change can be applied and archived to seed `openspec/specs/` as the repository baseline.

## Open Questions

- Whether the team wants a second pass later to split especially large domains, such as deployment versus install, into narrower capabilities.
- Whether future baseline work should add supporting diagrams or a capability map for onboarding alongside the normative specs.
