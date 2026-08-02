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
2b. **Standards** (§1b) — the named business/domain, SaaS, microservice and design-pattern rules the slice
   is built to. See "The standards table" below.
3. **Architecture & UML** — the three diagrams below.
4. **Implement** — a `- [ ]` checklist mirroring the design, ticked as built.
5. **Test** — concrete cases (happy path, validation/error, edge) + the Cypress spec to run headed.

## The standards table (always, in section 1b)

Immediately after **Document** and before **Design**, state which named standards the slice is built to —
so a reviewer checks the work against a rule rather than against taste, and so a later reader can tell a
deliberate choice from an accident. Cover only the rows that actually apply:

| Dimension | What to state |
|---|---|
| **Business / domain** | The real-world rule the software is serving (traceability, accounting treatment, fiscal document type). Name it; do not paraphrase it as a feature. |
| **SaaS multi-tenancy** | Org-scoping, anti-IDOR, and anything that could widen scope. |
| **Live-modules rule** | Why every default preserves today's behaviour, and whether a migration is needed at all. |
| **Microservice boundaries** | What stays in the owning service; whether a new service is justified (owns data + lifecycle + external integration) or a library is (rules shared, data local). |
| **Design patterns** | The NAMED patterns applied, and why that one. |
| **SOLID / DRY** | What is being shared once instead of duplicated per screen/service. |
| **Testing standard** | Pure-logic units on `mvn test`, one headed Cypress gate, and the regression assertion each gate makes. |

Worked example: `microservices/docs/slices/b2b-P3-documents-reports.md` §1b.

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
