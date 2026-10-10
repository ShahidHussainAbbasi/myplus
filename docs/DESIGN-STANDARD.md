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
| **Data repair / clean-up** | Any existing row the slice must correct, back-fill or remove, and the automatic job that does it (see "Data repairs run themselves" below). "None" is a valid answer; "the operator runs this SQL" is not. |
| **Testing standard** | Pure-logic units on `mvn test`, one headed Cypress gate, and the regression assertion each gate makes. | Prepare cypress test cases and then turn each into a real step-by-step test with concrete actions, expected results, a cleanup step from implementation flow if screen is not
  available or run the cypress to record each step and update the pa


Worked example: `microservices/docs/slices/b2b-P3-documents-reports.md` §1b.

## Data repairs run themselves (never a manual step)

**A repair is an idempotent job that runs automatically on deploy.** Production has no one to run a script, so no
design may end with "run this SQL", "click this button once" or "ask the operator". If existing data is wrong, the
design names the job that fixes it, and the job runs on every deploy and changes nothing the second time.

| Situation | The automatic shape |
|---|---|
| A defect let bad rows in (e.g. a doctor attached to another organisation's venue) | Fix the write path **and** add a start-up repair in the owning service that finds the bad rows and moves them to a `*_quarantine` table. Example: appointment-service `ProviderIntegrityRepair` + `V6__provider_quarantine.sql`. |
| A new column must be filled for old rows (back-fill) | Flyway migration for what SQL alone can prove; a start-up job for what needs the service's rules or another service's data. Both re-runnable. |
| A derived value drifted (a balance, a count, a cached total) | A scheduled or start-up reconciliation that recomputes from the source and fixes only the rows that differ, logging each. Example: payables auto-reconciliation (FP-6a). |
| Duplicates or orphans exist | Merge or detach by the documented match rule; anything the rule cannot prove is quarantined and reported, never guessed. |
| Messages failed to deliver (outbox, audit) | The relay retries on a schedule; a dead letter is redriven by the job, not by hand. |
| Tests leave data behind | The Cypress gate's `after()` removes its own rows and sweeps any an interrupted run left (match the gate's naming pattern). A gate never asks a person to clean the database. |

**Every repair job:**
1. **Is idempotent:** running it twice changes nothing the second time, so it is safe on every deploy.
2. **Proves before it acts:** it touches only rows it can show are wrong. A row with dependants (bookings, sales,
   journals) is reported at WARN with its ids and left alone. Unprovable means reported, never guessed.
3. **Moves rather than deletes:** it copies to a `*_quarantine` table (created by Flyway) before removing, so
   nothing is lost and a row can be put back after review.
4. **Uses one transaction:** copy and remove together, so a failure half-way leaves nothing changed.
5. **Names its columns:** the column list is read from `information_schema`, never `SELECT *`, so a later column
   change cannot break it silently.
6. **Never stops the service:** a failure is logged at ERROR and the service starts anyway.
7. **Logs what it did:** counts and ids at WARN when it changed anything, one INFO line when all was well.
8. **Has a unit test:** clean data changes nothing; bad data is moved; data with dependants is only reported;
   a failure does not throw.

In the design doc, the repair appears in **Design** (what it fixes and why), in **Implement** (the job and its
migration) and in **Test** (its unit test and the gate case that proves the write path is closed).

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

### Below are to review e2e 100% with current implementation and documnets to create udpate document for review again.
## Artifact management 
Artifact management is the practice of storing, organizing, versioning, securing, and distributing the outputs produced by your software build process—such as JAR files, Docker images, npm packages, Helm charts, and documentation. It provides a central, controlled source of truth for everything your CI/CD pipeline builds and deploys.
## infrastructure validation
Infrastructure validation is the process of proving that your servers, cloud resources, networks, databases, security controls, and deployment configurations are correctly set up and capable of supporting an application reliably before or after deployment. It checks that the environment meets defined requirements for availability, performance, security, connectivity, and configuration.

## system performance
inspect p50, p95 and p99 latency, error rate, throughput and saturation,tracing to separate frontend, API gateway, application, database, messaging and external dependency time, slow queries, missing indexes, large result sets, N+1 queries, connection-pool exhaustion, cache misses, thread-pool saturation, unnecessary synchronous calls, message backlog, CPU or memory pressure, or external-service latency.

## manage a critical production incident
confirm severity, create a clear incident channel, assign roles, stabilize the service through rollback, failover, feature disabling or traffic control, and communicate accurate updates to stakeholders.

## Architecture principles
# Modular by bounded context
  Each business capability owns its data and logic
# API-first
  Capabilities are exposed through versioned, documented APIs
# Single source of truth
	Each core data entity has one owning service
# Secure by default
	Authentication, authorization, encryption, and audit are baseline requirements
# Design for operability
  Services must be observable, recoverable, and deployable independently
# Buy before build, where sensible
	Use managed services or vendors when differentiation is low
# Vendor independence
	External platforms must not dictate internal domain design

## Transition roadmap
# Business capability target:
  Every module is available as a tenant-scoped, independently deployable capability.
# Application target:
 Domain-aligned microservices own their data; modules communicate through APIs and events.

# Data target:
 Each core entity has one system of record; reporting uses replicated or event-derived data.

# Technology target:
 Standardized containerized deployment on a managed cloud platform, with infrastructure as code and centralized observability.

# Integration target:
 All third-party and legacy integrations pass through controlled APIs and anti-corruption layers.

# Security target:
 Central identity, least-privilege access, secrets management, audit logging, and tenant isolation.

 ## Practical governance mechanisms:

# Architecture Review Board (ARB):
 Reviews significant designs, new vendor integrations, cross-service contracts, and security-sensitive changes.

# Architecture decision records:
 Document why a decision was made and what alternatives were rejected.

# Standards and guardrails:
 API style guide, logging standard, tenant-isolation rules, cloud tagging, backup policy.

# Automated compliance:
 Policy-as-code checks for security groups, resource limits, required labels, and prohibited public exposure.

# Exception process:
 A time-bound, documented waiver when a team cannot comply immediately.

# Fitness functions:
 Automated checks that measure architectural qualities such as coupling, API versioning, test coverage, latency SLOs, and cost.

 8