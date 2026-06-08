# Design documentation standard

**Applies to every implementation** — monolith features and microservice slices alike. Write the design
doc (with diagrams) **before** code, and pause at the design gate for non-trivial work. Docs live in
top-level `docs/` (monolith) or `microservices/docs/slices/` (service slices).

Diagrams use **Mermaid** (renders on GitHub and most Markdown viewers) so they live in version control
next to the code and stay reviewable in a PR.

## Required sections (in order)

1. **Document** — what is being built and *why* (the problem, the user value). Status line at top.
2. **Design** — data model (entities/tables, org-scoping), endpoint contract (method, path, request/
   response, auth/CSRF), service responsibilities, UI contract, security/anti-abuse.
3. **Architecture & UML** — the three diagrams below.
4. **Implement** — a `- [ ]` checklist mirroring the design, ticked as built.
5. **Test** — concrete cases (happy path, validation/error, edge) + the Cypress spec to run headed.

## The three diagrams (always)

- **Architecture (`flowchart`)** — components, data stores, and external systems with the data-flow
  edges between them (browser → controller → service → repo → DB; side effects like SMTP/queues).
- **Class diagram (`classDiagram`)** — the new/changed types (controller, service iface+impl, DTO,
  entity, repository) with fields, key methods, and relationships (`-->`, `<|..`, `..>`, `--|>`).
- **Sequence diagram (`sequenceDiagram`)** — the primary flow end to end, including `alt` branches for
  validation failure, auth/permission, and error/fallback paths.

For larger work add as needed: **ER diagram** (`erDiagram`) for multi-table schemas, **state diagram**
(`stateDiagram-v2`) for lifecycle/status fields, **component/deployment** views for cross-service flows.

## Worked example
See [`feature-book-a-demo.md`](feature-book-a-demo.md) for the full shape (Document → Design →
Architecture & UML → Implement → Test).

## Conventions
- Keep diagrams in sync with code — update the doc in the same change that alters the design.
- Prefer one focused diagram per concern over one sprawling diagram.
- Names in diagrams must match real class/table/endpoint names so the doc is greppable.
