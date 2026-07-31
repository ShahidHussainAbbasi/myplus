# MyPlus Commerce SaaS — Build Standards & North Star

One place that ties together the standards we build the **3 commerce verticals** by — **Retail/POS, Pharmacy,
E-commerce** — as a single **multi-tenant SaaS** on a **shared commerce core**. Companion detail docs are linked.
(Consolidated 2026-06-23 after a regroup.)

---

## 0. The one-line strategy
**One multi-tenant SaaS platform; one shared commerce core (catalog + inventory + trade saga); each vertical is the
same core white-labelled by user type + its own thin differentiating layer. Reuse the whole codebase; do not
reinvent.**

---

## 1. Governing standards (the rules every slice follows)

1. **Multi-tenancy (SaaS).** Every read/write org-scoped: `organization_id` + `findScoped` NULL-fallback; identity
   flows JWT → gateway `X-Org-Id`/`X-User-*` → service `CurrentUser`. Per-org plan/trial/quota; signup provisions a
   tenant. _→ `ARCHITECTURE-MULTITENANCY.md`._
2. **Microservices decomposition — compose, don't duplicate.** Bounded contexts: catalog (product master),
   inventory (stock/FEFO), trade (sale saga/invoice/returns), pharma (clinical), marketplace (storefront) + platform
   services (auth, gateway, config, eureka, notification…). A vertical **composes** the core via clients; it never
   re-stores products/stock. _→ `slices/33-platform-decomposition.md`._
3. **Reuse-first.** Reuse the existing POS/business screens, the sell↔stock **saga**, tax (G3), payments (G5),
   receipts (G6), day-close, returns. New code only for genuinely net-new capability. _Current bridge:_ verticals
   reference **`itemId`** (the saga already maps itemId→catalog `Product`); the full Item→Product convergence is
   **deferred tech-debt** (`slices/42-item-product-convergence.md`).
4. **One dashboard, vertical-aware.** A single `businessDashboard.html` on `/businessDashboard`, white-labelled by
   the logged-in user's type (BUSINESS=POS, PHARMA=Pharmacy, ECOMMERCE=Store) via `module-theme.js`
   (labels + `data-vertical-only` features + theme). No per-vertical templates/routes. _→ `slices/36-…`._
5. **Engineering standards / design patterns.** `BigDecimal(19,2)` money; DTOs at the boundary (never entities);
   **saga** for cross-service atomic writes + recovery relay + idempotency; gateway per-route Resilience4j circuit
   breakers + timeouts; Bean Validation; common-web `ApiResponse` envelope (business-service keeps its monolith-
   facing `GenericResponse`); Flyway forward migrations; Hikari pools + `open-in-view:false`; FEFO + never-expired.
6. **Process cadence (per slice).** **Document → Design (Mermaid UML, `DESIGN-STANDARD.md`) → Implement (UI/UX →
   service/API → DB) → Test (`mvn`, Testcontainers) → headed Cypress GREEN → next.** Mark each step against the
   codebase. **A slice is not done until its headed Cypress passes.** _→ slice-cadence + Cypress-gate._

---

## 1b. Database standards (every service, no exceptions)

Added 2026-07-28 after a platform-wide schema audit. Each rule exists because its absence caused a real defect —
the incident is named so the rule is arguable, not folklore.

**D1. Every service owns its schema through Flyway.** No service may rely on `ddl-auto` to create tables. A
service whose schema exists only as a side effect of Hibernate is not reproducible on a fresh deploy. Adopt an
existing database with a `V1__baseline.sql` generated from it (`mysqldump --no-data`, `AUTO_INCREMENT` stripped)
plus `baseline-on-migrate: true`. _Incident: appointment-service ran on `flyway.enabled=false` + `ddl-auto:update`
and was the one service that could not be brought onto `validate` or indexed._

**D2. Migrations must execute in `mvn test`, on an empty database.** At least one test per service must boot with
`spring.flyway.enabled=true` and `ddl-auto=validate` against a throwaway container. Testing against a
Hibernate-invented schema proves the code and nothing about the deploy. _Incident: all three pharma tests disabled
Flyway, so V2's rebase and V3's state-aware `item_id → product_id` rename — the most intricate SQL in the service
— had never run in CI. Pattern: `pharma-service` · `FlywayMigrationTest`._

**D3. Index every scoped column.** `organization_id` carries the platform's most common predicate; unindexed, every
scoped read is a full scan. Composite `(organization_id, user_id)` where the NULL-fallback scope applies,
`(organization_id)` alone where rows are org-only. Add it with the column, not after a customer complains.
_Incident: 36 tables across 8 databases had none._

**D4. An entity-vs-table diff is a starting point, NEVER evidence of a dead table.** Schema can be load-bearing
with no `@Table` pointing at it. Confirmed cases: `@JoinTable` collection tables (`roles_privileges`,
`users_roles`, `schools_owners`, `staff_grades`) and `@SequenceGenerator` tables (`cust_seq`, `sell_seq`,
`purch_seq`, `vender_seq`, `item_type_seq`, `item_unit_seq` — on MySQL a sequence generator *is* a table). Before
calling anything dead, grep `@JoinTable` and `sequenceName` **multi-line** — `staff_grades` was nearly declared
dead because its `name =` sits on a different line from the `@JoinTable(` token.

**D5. Never drop a table on inference. Count it, in every environment.** "Unmapped" and "empty on dev" are not the
same claim, and neither survives contact with production. _Incident: `myplusdb.company` looked like a dead
monolith leftover and holds **336 rows that were never migrated** to `companies`._ Where a drop is justified, say
which environments were counted and why the risk is acceptable — pharma's dead schema was dropped because that
vertical is **pre-production**; inventory's identical-looking orphans were not, because it ships to customers.

**D6. Dead FK columns on LIVE tables outrank dead tables.** The visible mess is the orphan table; the real one is
the constraint MySQL still enforces on a table you use daily. Drop the FK, then the column, then the table.
_Incident: `prescription_items`, `dispensing` and `drug_interactions` each carried an enforced FK into the dead
`medicines` table long after Hibernate stopped mapping the column._

**D7. Migrations are idempotent and re-runnable.** Guard every DDL statement on `information_schema`
(`CREATE TABLE IF NOT EXISTS`, guarded `ADD COLUMN`, `DROP … IF EXISTS`, constraint names resolved at runtime
because they are auto-generated and differ per environment).

**D9. Renaming an entity field touches SEVEN places, and only four of them fail at compile time.** Miss one of
the rest and the service starts (or compiles) and breaks in use. Checklist, in the order they bite:

| # | Form | Fails at | Example that caught us |
|---|---|---|---|
| 1 | field declaration + getters/setters | compile | — |
| 2 | **method references** — no `()`, so a `getFoo()` pattern misses them | compile | `FeeCollection::getPd` |
| 3 | callers **on a different entity that shares the short name** | compile | `Student.vf` vs `FeeCollection.vehicleFee` — one file used both |
| 3b | **Lombok `@Builder` methods** — named from the field, so `.en(x)` becomes `.enrollNo(x)` in every builder chain | compile, **in the caller** | `FeeCollectionDTO.builder().en(…)` |
| 4 | **derived query method names** — `findByOrganizationIdAndEn…` resolves `en` as a property | **context startup** | `No property 'en' found for type 'FeeCollection'` |
| 5 | **JPQL / `Sort.by("…")` / `@OrderBy` strings** | **runtime, per query** | — |
| 6 | **JSON contract** — a controller returning the entity means the field name IS the API; form `name=` must match the bound DTO | **runtime, in the browser** | `obj.fp` → `obj.feePaid`; `name="ri"` → `name="receivedIn"` |
| 7 | **selectors built by CONCATENATION** — `$("#" + table + "GradeDD")` contains no literal `aGradeDD`, so every grep for the old name comes back clean | **runtime, SILENTLY** | renaming `aGradeDD` → `attendanceGrade` left the roster dropdown permanently empty |

Row 7 is the worst of the seven because **jQuery on an empty set is a no-op**: no exception, no console error,
no failed request — the element simply never fills. Before declaring any id/field sweep clean, grep for the
*construction*, not just the name:

```bash
grep -on '"#"[[:space:]]*+[^;)]*' path/to/*.js     # every dynamically-built selector
```

Then check whether any of them can resolve to a name you just changed. The same applies to `name=` attributes
assembled in JS and to `getElementById(prefix + suffix)`.

Two force-multipliers: check `@Column(name=…)` **first** — if the DB columns are already readable, the rename
is Java-only with **no migration** (that was true for `FeeCollection`); and scope every sweep per-form or
per-region, never globally, because short names collide across entities and across HTML forms.

**D10. A persisted field that no read returns is invisible — check the READ path, not just the write.** An entity
column with no matching DTO field is written correctly, compiles cleanly, passes every service test, and then
fails in the browser as `undefined`/`NaN`. Nothing between the column and the screen complains.

This bit **three times in one programme**, always the same shape: `dueBalance` / `vehicleFee` / `checkNo`
(collected by the fee form, on the entity, absent from `FeeCollectionDTO` — silently dropped on every save since
the screen was written), then `Student.creditBalance` (persisted by the credit ledger, never returned, so the UI
could not show a parent money the school was holding).

When adding or relying on a field, walk the whole path in both directions:

```
form field name  →  DTO field  →  entity column          (write)
entity column    →  DTO field  →  JSON key  →  UI read   (read)
```

Two cheap checks that would have caught all three: diff the entity's fields against the DTO's, and grep the JSON
key the UI actually reads. Related: **D9** (a rename must move every one of those hops together).

**D8. Deleting an entity is a code change, not a schema change.** Remove the Java, leave the table, record it.
Git restores a class; nothing restores rows. Verify zero references first — including the entity-only-referenced-
by-its-own-repository "dead pair" shape (`Segment` + `SegmentRepository`).

**D11. A duplicate DOM id is a silent wrong-element bug — and you must ignore commented-out markup when hunting
for them.** `getElementById` returns whichever element comes first, so a page with two `#foo` reads or writes the
wrong one with no error anywhere. This has bitten twice: the alerts table emitted `<div id=acdd>` per row,
colliding with the form's `<select id="acdd">` (`cy.select()` got a `<div>`), and `#fvidiv` appeared on both the
live voucher form and a hidden legacy form.

**Never audit this with a naive grep.** `grep -oP 'id="\K[^"]+' t.html | sort | uniq -d` reported **12**
duplicates in `educationDashboard.html`; **11 were inside HTML comments** and one was real. Comment-aware check:

```python
live = re.sub(r'<!--.*?-->', '', html, flags=re.S)     # strip comments FIRST
dupes = [k for k, v in Counter(re.findall(r'id="([^"]+)"', live)).items() if v > 1]
```

Expect two more false positives: ids built by JS template literals (`id="${s.id}"`) are unique at runtime, and
table renderers that emit ids per row are duplicates in the DOM but appear once in the source — those need the
runtime check (`document.querySelectorAll('[id="foo"]').length`), not a source scan. Table cells are display-only:
give them **no id at all** rather than one that collides with a form field.

## 1c. Configuration standards

**C1. A toggle that changes nothing is worse than no toggle.** Declaring a `SettingEntry` is half the work; the
flag must be read on the path it governs. _This failed twice: `pos.sale.negativeStockAllowed` (removed by design)
and `pharmacy.interaction.blockSevere` (declared, rendered as a working checkbox, read nowhere)._

**C2. Gate-test both halves.** Assert the key is in the catalog with the intended default **and** that the
consumer honours it. A catalog-only assertion passes for a setting nothing reads.

**C3. Safety flags default ON, and fail ON.** For a flag guarding a safety or compliance step, the do-nothing
state must be the safe one, and an absent key or failed config read must resolve to the safe one too.

**C4. Verify the OFF path actually works.** A "require X" toggle switched off must be supported end to end —
schema included. _Incident: `welfare.donation.requireDonor` OFF still failed, because `donation.donator_id` was
`NOT NULL`._

---

## 2. Per-vertical activity lifecycle (UI → API → DB)
The full industry-standard lifecycle for each vertical, graded ✅/🟡/⬜ against the codebase, lives in
**`commerce-verticals-blueprint.md`** (the master map) and **`commerce-backend-audit.md`** (backend gaps G1–G6).
Summary: all three share the core lifecycle (catalog → stock-in → counter/checkout sale → tax → payment → receipt →
returns → day-close → reporting); pharmacy adds Rx/dispense/safety; e-commerce adds storefront/cart/checkout/
fulfillment.

---

## 3. Where we are (status, 2026-06-27)

**Phase 1 — POS / shared core: DONE, all headed-Cypress green.**
G1 expired-stock block · G2 returns→inventory · G3 tax engine · G5 payments/tender · G6 receipts · day-close
(shift/cash-drawer/X-Z) · park/hold · single vertical-aware dashboard.

**Phase 2 — Pharmacy: largely DONE on the itemId bridge (reuse-first), headed-Cypress green.**
- pharma-service is a full mesh participant; medicine registration = the existing Item screen (relabeled).
- P5 prescription intake · P6 dispense (reuses Sell screen) · P7 safety (rxRequired/controlled + drug-interaction)
  · P8 alerts · P10 FEFO batch/expiry on dispense · P11 quarantine returns + register · P12 insurance/co-pay split.
- Remaining: deeper clinical/reporting; convergence cleanup (below).

**Phase 3 — E-commerce: IN PROGRESS (reuse catalog/inventory/trade).**
- Done (slices 46–61): storefront browse + **name search**, public orders, **stock-reserve saga** (same inventory
  saga as POS) + recovery relay, order management/fulfilment, order tracking + status timeline + notification seam,
  sandbox online payment (PSP swap deferred), **self-contained customer accounts**.
- Remaining, in priority order: **cart (E3), checkout (E5), real PSP + refunds (E6), shipping (E9), returns/RMA
  (E10), coupons (E13), variants/media (E2)**.

**Cross-cutting — prod-readiness: DONE this cycle.**
- **P0 Flyway series (slices 64–67):** all 12 microservices now own their schema via versioned migrations and are
  `ddl-auto: validate`-ready; dev flipped `update → ${DDL_AUTO:validate}`. See [[project_flyway_validate_ready]].

**Convergence status (Item↔Product, slice 42):** M1 ✅ Product master screen · M3.1 ✅ Stock list reads inventory
on-hand · M3.2 ✅ purchases auto-map + reach inventory · slice 53 ✅ product-master sync (Product→Item). M3.3/M3.4
(retire local `Stock`/`Item`) and full M2/M4 rewire **parked** — master-sync covers "one product master" for now.

**Deferred tech-debt:** M3.3/M3.4 local-`Stock`/`Item` retirement; business-service `ApiResponse`/`GenericResponse`
dedup + OpenAPI; storefront auth hardening (token expiry/rotation/rate-limit/email verify); quantities
`Float→BigDecimal`; real PSP + money-side refunds; landing this 34-slice branch (master SB 4.1.0 doesn't compile).

---

## 4. Step-by-step path from here (regrouped)

**Consolidation (in progress):** P0 Flyway done + dev `validate` flip; planning docs refreshed; branch housekeeping.
Then decide the **branch-landing path** — `feature/commerce-gaps` is ~34 slices ahead of a master that doesn't
compile (SB 4.1.0); fix master to the 3.5.0 line (or cut a release) and merge before piling on more.

**Finish Phase 3 — E-commerce** (reuse catalog/inventory/trade/tax/saga), in order:
1. **E3 Cart** — add/update/remove, persisted for guest + account (reuses customer accounts from slice 61).
2. **E5 Checkout** — address + shipping method + tax (reuse C3/G3) + totals → order placement via the saga.
3. **E6 Real PSP + refunds** — swap the sandbox gateway for Stripe; webhooks; money-side refund (extends G5/G2).
4. **E9 Shipping**, **E10 Returns/RMA** (reuse G2 inverse saga), **E13 coupons**, **E2 variants/media**.

**Cross-cutting / tech-debt** (fold in deliberately): `ApiResponse`/`GenericResponse` dedup + OpenAPI; storefront
auth hardening; M3.3/M3.4 local-`Stock`/`Item` retirement; quantities `Float→BigDecimal`; audit log; reporting suite.

> Every numbered step ships UI/UX + service/API + DB together, with a passing headed Cypress, before the next.

---

## 5. Product roadmap & cross-cutting shared services

**The landing page `svcMarquee` (`SVCS` array, `maxtheservice_dashboard.html`) is the product catalog — 11 services, each a domain on the SAME shared core (reuse-first, plug-and-play):**
School Management (education ✅) · Inventory (inventory ✅) · **ERP (⬜ planned)** · POS (business ✅) · **HRM (⬜ planned)** · Campaign (campaign ✅) · Analytics (analytics 🟡) · Appointment (appointment ✅) · Pharmacy POS (pharma ✅) · **Pharmaceutical / distribution (⬜ planned)** · Online Marketplace (marketplace ✅).

**Cross-cutting shared services — build deliberately, never per-domain:**
- **finance-service** = the money spine (AR ✅ → AP ✅ (F1) → **GL ⬜**). The GL is the seed of the **ERP** offering. Party-agnostic ledger (references parties by `partyType+partyId`, never owns them).
- **party/contact master service** = the future shared CRM/identity. **Decision: defer** — not a "customer god-service"; converge on a thin party master + `partyId` bridge (like Item→Product), as its own initiative **after** the finance stack. Rationale in `finance-ap-vendor-payments-design.md` companion + memory. The shared `SubledgerService`/`OpenDoc` (business-service) is already party-agnostic and is the enabler.

**Standing constraint — performance is a priority** in every design/impl choice: set-based over per-row queries (watch N+1), index scoped/FIFO columns via Flyway, keep inter-service calls off hot paths (best-effort like the finance ledger call), reuse shared components. Raise perf/architecture concerns **early**, before a design ships.
