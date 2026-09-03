# MyPlus Commerce SaaS — Build Standards & North Star

**Status:** GOVERNING STANDARD - living. The doc a new session should read FIRST; §3 carries current delivery state. If this contradicts a slice doc, this is what a reader believes, so fix it first.

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
   **The `mvn` step is not optional, and `-DskipTests` does not satisfy it.** Cypress cannot see a unit test
   that never compiled. _Incident 2026-08-03: `SagaSaleWriter.writePending` gained a parameter in B2B Phase 0–2
   and `SagaSellServiceTest` kept stubbing the old arity, so **business-service's unit suite failed to compile
   for two whole phases** while those phases were reported green on Cypress alone. Run the owning service's
   `mvn test` in the gate, not just the spec._
7. **Industry-grade by default — benchmark, name the pattern, state performance and security.** Adopted
   2026-08-27, at the owner's direction, because **this codebase is heading for many domains on one core** and
   a shortcut that is invisible in one becomes structural when the next five sit on top of it. Every design
   document from here carries four things, by name:

   * **7a · The benchmark, BEFORE the decision.** Name the established systems that already solved this
     problem — SAP, Odoo, NetSuite, Dynamics, Square/Shopify for commerce; the leaders of whatever domain
     otherwise — say what was taken from each, **and where we deliberately differ and why**. Neither
     re-invent nor cargo-cult. _The ordering is load-bearing: in U2 the benchmark **changed the answer**._
     _→ `slices/u2-loose-sale-arithmetic.md` §10 is the reference shape._
   * **7b · The pattern, named, with its SOLID/DRY consequence.** Not "we used a service" — *"Value Object +
     Strategy at the pricing seam; Open/Closed, because the next caller adds no branch here."* A pattern you
     cannot name is a shape you cannot reuse in the next domain.
   * **7c · Performance, on the HOT path specifically.** New remote calls (ideally none — ride the fetch that
     already happens), new queries, per-request vs per-line reads, indexes added **or deliberately not**, and
     the net effect on a tenant who never switches the feature on. _→ perf-priority feedback._
   * **7d · Security.** Where the control is **enforced server-side** (a UI that does not offer it has never
     been a control), what the client is trusted for and what it is not, tenancy, and what the refusal path
     does to the surrounding transaction.

   **And prefer the current ecosystem** — supported library and platform features over hand-rolled
   equivalents; say why when choosing otherwise.
8. **Every response is parsed and displayed the same way.** Adopted 2026-08-27, at the owner's direction,
   after users reported that server messages — the free-trial notice among them — never reached the screen.

   **The platform answers in two envelopes** and will for some time:

   ```
   ApiResponse       { success: true,     message, data,   statusCode }     65 files
   GenericResponse   { status: "SUCCESS", message, object, collection }     71 files
   ```

   * **8a · Server side — a refusal is an ANSWER, not a failure.** A proxy must never reduce a downstream
     response to a bare status. `com.web.util.ProxyErrors` relays the downstream `message` and lets
     user-facing exceptions (`DemoLimitException`) reach their `@ControllerAdvice`. **Before this, 108 proxy
     methods returned `{"status":"ERROR"}` with the reason discarded** — the service had explained itself and
     the monolith threw the sentence away.
   * **8b · Both envelopes answer both questions.** `GenericResponse` exposes a derived `success`
     (`status == SUCCESS`), so `body.success` means the same thing on every endpoint. Derived, never stored —
     one source of truth, two ways to read it.
   * **8c · Client side — never read the envelope by hand.** `/js/common/api-response.js` is the only
     reader: `apiOk(resp)`, `apiMessage(resp, fallback)`, `apiFailMessage(jqXHR, fallback)`,
     `apiData(resp)`, `apiList(resp)`, `apiHandle(resp, {...})`. Loaded on every dashboard.
   * **8d · The server's sentence wins.** A hard-coded failure message is a FALLBACK for when the server sent
     none — never the default. `apiFailMessage` exists because jQuery routes non-2xx to `error`/`fail`, where
     the body sits unread in `responseJSON`: handlers were telling users *"Network error, check your
     connection"* when the connection was fine and the response said their trial had ended.

   ⚠ **The failure mode that makes this a standard rather than a preference: reading the wrong field never
   throws.** It silently reports "failed" for a call that worked, or "fine" for one that failed. In tests it
   is worse still — `expect(body.success).to.not.eq(true)` against an envelope with no `success` field
   **passes for every possible outcome**, and three refusal tests reported green while asserting nothing at
   all. _→ `slices/u2-loose-sale-arithmetic.md` §13.4b._

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

**D2a. A SKIPPED Testcontainers test is indistinguishable from a passing one — read the Skipped count.**
`@Testcontainers(disabledWithoutDocker = true)` turns an unreachable Docker daemon into a green no-op, so
`mvn test` reports BUILD SUCCESS having executed none of them. Never accept the summary line as evidence a
container test ran; check `target/surefire-reports/*.txt` for `Skipped: 0`.

_Incident (2026-08-20): `mvn -pl business-service test` gave `Tests run: 134, Failures: 0, Skipped: 13` — and
**all 13 were the module's Testcontainers tests**, covering the 41 migrations, invoice money arithmetic, the
sale write's atomicity and multi-tenant scoping. The four riskiest areas in the service were the exact ones
silently not running. The same was true of the Flyway tests in marketplace-service and pharma-service, so
**three services satisfied D2 on paper while none executed locally.** CI was unaffected — GitHub's
`ubuntu-latest` runners ship Docker — which is why it went unnoticed for months._

**Cause and fix (measured, 2026-08-20).** It is an **API-version** rejection, not a pipe or context problem:

```
DOCKER_API_VERSION=1.32  docker version   ->  FAILS
DOCKER_API_VERSION=1.40  docker version   ->  OK
```

Docker Engine 29.7.2 advertises *"API version: 1.55 (minimum version 1.40)"*, while docker-java as bundled in
Testcontainers 1.21.0 falls back to **1.32** when it cannot negotiate. The daemon answers `400` with a stub
`/info` body and Testcontainers reports the whole environment as unavailable. The `docker` CLI is unaffected
because it negotiates properly — which is why `docker ps` working proves nothing about Testcontainers.

```properties
# ~/.testcontainers.properties
api.version=1.41
```

⚠ **A first attempt blamed a named-pipe / context mismatch. That was wrong**, and is recorded so nobody
re-derives it: `//./pipe/docker_engine`, `//./pipe/dockerDesktopLinuxEngine` and `//./pipe/docker_engine_linux`
all serve a working engine (verified by pointing `DOCKER_HOST` at each in turn); only `//./pipe/docker_cli`
does not. **Bumping the engine can silently disable every container test on a machine** — the failure looks
like "Docker is not available" when Docker is plainly running.

Separately: do not pin `docker.client.strategy`. A pinned strategy is used exclusively and hardcodes its own
pipe, so a `docker.host` set beside it is ignored.

Do **not** "fix" any of this by removing `disabledWithoutDocker` — that reddens the build on any machine
without Docker, which is worse than skipping. The guard is right; reading the count is the discipline.

**D3. Index every scoped column.** `organization_id` carries the platform's most common predicate; unindexed, every
scoped read is a full scan. Composite `(organization_id, user_id)` where the NULL-fallback scope applies,
`(organization_id)` alone where rows are org-only. Add it with the column, not after a customer complains.
_Incident: 36 tables across 8 databases had none._

**D3b. Scoping the read is not the same as indexing the QUERY — and "we already indexed that table" hides it.**
A `(organization_id, user_id)` index serves `WHERE org = ?`. It does **not** serve `WHERE org = ? AND name = ?`
or a `GROUP BY att_date` — those need `(organization_id, <filter column>)`. Index the predicate the query
actually runs, not the one the table was first indexed for. _Incident: education finding D. Every education
table was already indexed by V7's scoped-index pass, and the dashboard still loaded five whole tables while
twelve save endpoints each scanned an entire table to answer "does this name exist" — V16 added a second,
complementary set. See `slices/edu-D-analytics-perf.md`._

**D3c. If a case-insensitive comparison moves from Java into SQL, it starts depending on the COLLATION — write
that down where it is relied on.** `equalsIgnoreCase()` in a stream becomes `=` in SQL, which is
case-insensitive only because the column collation is (`utf8mb4_0900_ai_ci`). The explicit-looking alternative,
`lower(col) = lower(?)`, is honest and **defeats the index** — a query that looks careful and still scans.
Choose the collation-dependent form, and record the dependency in the migration that adds the index, so a
future collation change is understood to change twelve duplicate checks at once.

**D3d. A proxy that discards the downstream error makes every failure behind it undiagnosable — log the
status and body before rethrowing.** The monolith's `GatewayClient` handled 401 and 403 and let every other
downstream status propagate to the catch-all `@ExceptionHandler`, which replaced it with a generic
`"Error Occurred"` 500. The class had **no logger at all**, so the service's real status, message and body
were recorded nowhere — in any module. _Incident: education 2.1's gate failure took hours and was still never
root-caused, because the one thing that knew the answer threw it away. Fixed 2026-08-02: `Downstream
FAILED/UNREACHABLE <method> <url> -> <status>; body=…`, rethrowing unchanged._

**D3e. Every outbound HTTP client needs connect and read timeouts.** `new RestTemplate()` has none, so a
downstream that accepts a connection and never answers pins the calling thread until the OS gives up — one
slow service can exhaust the monolith's thread pool and take the whole UI down. The gateway's per-route
Resilience4j timeouts (§1.5) protect the gateway, **not** the monolith's own calls. _Open at time of writing:
`GatewayClient` still has no timeouts; raised in `slices/edu-2.1-timetable.md` §6._

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

**D9a. If the column name changes, it ships as a NEW migration — never an edit to the applied one.** The
tempting move on a rename is to fix the `CREATE TABLE` that introduced the column, because the end state
looks identical on a fresh deploy. It is not identical: Flyway checksums every applied script, so editing
one makes the service **refuse to start** in every environment that already ran it — which is all of them,
including production. Add `Vn+1` with `ALTER TABLE … CHANGE COLUMN`; a fresh deploy replays the original
and then the rename and lands in the same place. `parent_informed` → `guardian_informed` (education V21 →
**V23**, 2026-08-04) is the worked example: V21 was reverted after a sweep touched it.

Prefer `CHANGE COLUMN` over `RENAME COLUMN` — the latter needs MySQL 8.0, and restating the type keeps the
new definition readable beside the original.

**D9b. A word-level rename has homonyms, and a blanket replace corrupts them.** Renaming a *concept*
across a codebase (`parent` → `guardian`) is not the same as renaming a field, because the same string
carries unrelated senses: `re-parent` (anti-IDOR), `parent exam`, `parent year`, `service-parent` (Maven),
`categories.parent_id`, DOM `parentNode` / `.parents()` in vendored libraries, and the word
*parenthetical*. Sweeping globally turns working code into `service-guardian` and silently rewrites
third-party files.

Protect the homonyms **before** substituting, then restore them:

```python
for i, phrase in enumerate(PROTECT):  t = t.replace(phrase, f"\x00{i}\x00")
for a, b in SUBS:                     t = t.replace(a, b)
for i, phrase in enumerate(PROTECT):  t = t.replace(f"\x00{i}\x00", phrase)
```

And list the target files explicitly rather than walking the tree, so `node_modules` and vendored
libraries are never candidates. Renames also reach places D9's seven rows do not: **i18n keys AND their
values in every bundle** (the key rename is mechanical, the *translation* is not — `hi` and `ar` already
said guardian, `en`/`fr`/`es`/`ur` needed rewording), and **prose in shipped slice docs**.

**D10. A persisted field that no read returns is invisible — check the READ path, not just the write.** An entity
column with no matching DTO field is written correctly, compiles cleanly, passes every service test, and then
fails in the browser as `undefined`/`NaN`. Nothing between the column and the screen complains.

This bit **three times in one programme**, always the same shape: `dueBalance` / `vehicleFee` / `checkNo`
(collected by the fee form, on the entity, absent from `FeeCollectionDTO` — silently dropped on every save since
the screen was written), then `Student.creditBalance` (persisted by the credit ledger, never returned, so the UI
could not show a guardian money the school was holding).

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

**D13. A Lombok module can fail with ~90 errors that all have ONE cause — always fix the FIRST error and
rebuild before reading the rest.** A structural error (a duplicate method, a syntax error) aborts javac's
annotation processing, so every `@Data`/`@Getter`/`@Builder`-generated method in the module vanishes at once.
The output then blames files nobody touched:

```
[ERROR] FeeCollectionController.java:[336,20] method glMethod(String) is already defined   ← the ONLY real error
[ERROR] AlertChannel.java … cannot find symbol: method getC()                              ← collateral
[ERROR] OrgSetting.java  … cannot find symbol: method builder()                            ← collateral
[ERROR] Term.java        … cannot find symbol: method getStartDate()                       ← collateral
```

Symptom to recognise: mass `cannot find symbol` on *getters, setters and `builder()`* across unrelated entities.
That is never a real refactor gap — it is annotation processing having been switched off. Do not start
"fixing" the collateral; it does not exist.

**D12. education has TWO `FeeCollectionDTO` classes — check which one a controller binds before writing against
it.** `com.myplus.education.dto.FeeCollectionDTO` is the flat/legacy shape `addFc` binds;
`com.myplus.education.dto.EducationDTOs.FeeCollectionDTO` is the nested REST shape. They carry the same field
names, so an IDE import completes happily and the mistake only surfaces at compile time — and then as
`cannot find symbol: variable FeeValidator` at the *call site*, because the helper class itself failed to
compile. The error names the wrong file.

The same split exists for `SchoolDTO` (its own javadoc says "Separate from the REST `EducationDTOs.SchoolDTO`").
Before writing a validator, mapper or test against a DTO, grep the controller's imports for which one it uses:

```bash
grep -n "^import .*\.dto\." <Controller>.java
```

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

## 3. Where we are (status, 2026-08-01)

**In flight:** **B2B/B2C rollout** — Phases 0, 0.5 and 1 ✅ green (credit limit shipped 2026-08-02, customer + supplier); Phase 2 (B2B pricing) ✅ backend green 2026-08-02; Phase 3 (documents & reports) ✅ COMPLETE — all 7 sub-slices green (traceability IN/OUT; CRN-/DBN- documents; CSV statements; shared report filter rail + grouping) (`feature/b2b-b2c`); plan of record
[`b2b-b2c-rollout-plan.md`](b2b-b2c-rollout-plan.md). Also open: `feature/education-review` (finding B),
`feature/pharmacy-review` (step 6), OMS O1 (design awaiting approval).

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
- **finance-service** = the money spine (AR ✅ → AP ✅ (F1) → **GL ✅**). Party-agnostic ledger (references parties by `partyType+partyId`, never owns them). **The GL shipped** — `Account`/`JournalEntry`/`JournalLine`, `PostingService` with auto-post, trial balance, P&L and balance sheet, then outbox reliability, idempotency, an immutable audit trail (`audit-service`), the tax register, and period close/lock (`PeriodLockService` gates 10 operations). It is the seed of the **ERP** offering, and education fees now post into it too (edu slices 0.1/0.2).
- **party/contact master service** = the shared CRM/identity spine. ~~**Decision: defer**~~ — **BUILT** (`party-service`, 8096). A thin party master + `partyId` bridge exactly as planned (like Item→Product), *not* a customer god-service. **All five module bridges are live**: business Customer/Vender, education Student, welfare Donator, pharmacy prescription-patient, marketplace shopper — each a best-effort `PartyBridgeService` upsert after commit, so the bridge can never fail a save. The shared `SubledgerService`/`OpenDoc` was the enabler, as predicted.

**Standing constraint — performance is a priority** in every design/impl choice: set-based over per-row queries (watch N+1), index scoped/FIFO columns via Flyway, keep inter-service calls off hot paths (best-effort like the finance ledger call), reuse shared components. Raise perf/architecture concerns **early**, before a design ships.

---

## Gating standard — see `GATE-RUNBOOK.md`

**A slice is not done until it is gated as the tenant that needs it, across the privilege level that uses it.**

The full procedure lives in [`GATE-RUNBOOK.md`](GATE-RUNBOOK.md). The five rules in short:

1. **Log in as the feature's own tenant** — `owner.mobile@` for a mobile shop, `owner.pesticide@` for
   agri-chem, `owner.pharma@` for pharmacy, `owner.marketplace@` for distribution, `owner.business@` for POS.
   Never whichever account the previous spec happened to use.
2. **Set the tenant up as its owner would** — shape first, then the capabilities the preset does not grant.
3. **Add the cross-tenant case** — a different tenant on the *same shape* must not see the feature.
4. **Walk the ladder** — `owner.` / `admin.` / `user.`, asserting both that **data populates** and that
   **UI privileges differ**.
5. **Restore tenant state in `after()`**, especially on `owner.business@`.
6. **Add the slice's manual cases to the Test Book** — one page for the whole product, always the same page:
   <https://claude.ai/code/artifact/84fdaeff-84bb-4427-9e37-5f1c3ba845a3>. A green Cypress run does not close a
   slice on its own; a person must be able to walk it by hand. Correct any existing wording the slice
   invalidates, because a page that quietly contradicts the product is worse than no page — it is trusted.

Why rule 6 is not paperwork: the automated gates have been green through a credit note that printed no
lines, the same note printing **no customer name** for every tenant since #15, a scan box appearing for shops
that had switched it off, quote settings nobody could set (leaving the approval step unreachable), and nine
features that shipped where nobody could click them. Every one was found by a person looking at the screen.
An automated case asserts what someone thought to assert; the manual walk finds what nobody thought of.

Why this is a standard and not a preference: gating serial/IMEI as the POS owner passed cleanly while
concealing that the `retail` preset does not grant `serialTracking` — a real mobile shop had its headline
feature switched off and no test could tell. A convenient account proves the mechanism and nothing about the
business.

**Keep this document, `GATE-RUNBOOK.md` and the relevant design doc updated as part of the slice** — not
afterwards. A standard that lags the code is a standard nobody can trust, and the next person reads the doc
before they read the diff.

---

## i18n: a label read by JavaScript must live under `ui.js.*`

`LocaleInterceptor` ships **only the `ui.js.` prefix** into `window.__MSG` — server-only copy (validation
text, emails) deliberately never crosses to the browser. And `t()` in `i18n.js` **returns the key itself**
when it is missing: no exception, no console warning.

The two facts together make a silent failure. `t('ui.completeSale')` printed the literal string
`ui.completeSale` onto the checkout confirm dialog in every language — while the key existed, was correctly
translated in all six locales, and rendered perfectly through `th:text` elsewhere on the same page.

| Context | Correct namespace |
|---|---|
| `th:text="#{...}"` in a template | any (`ui.*`, `message.*`, …) |
| `t('...')` in JavaScript | **`ui.js.*` only** |

**Detector** — must return nothing:

```bash
grep -rhoE "t\('ui\.[a-zA-Z0-9_]+'\)" src/main/resources/static/js/ | grep -v "t('ui.js."
```

**Gate:** `cypress/e2e/ui/i18n-js-prefix.cy.js`.

---

## A slice is not done until something CALLS it

Seven capabilities have shipped in this codebase working, tested at the API level, and **unreachable** —
no screen, menu entry or caller anywhere:

| Capability | How it surfaced |
|---|---|
| C1 `CapabilityService` | `@Service` on an `@Import`-wired module registered nothing |
| C3 `CapabilityCatalog` | unregistered; the read path failed OPEN so nothing complained |
| C6 per-product policy | shipped with no control on any screen |
| PERF-4 | spec written with the slice and never executed |
| `getSaleReturns` | endpoint + proxy since SF-11, no UI ever called it |
| `downloadInvoicePdf` / `downloadChallan` | in `document-pdf.js`, zero callers |
| `stock/summary` `totalInventoryValue` | computed (in Java, over every row) and displayed nowhere |
|  (P3) | the box existed but sat inside , which  sets to  — nine server cases passed while a cashier could not give free goods |

**This is the default failure mode here, not an anomaly.** API tests pass in every one of these cases,
because the endpoint genuinely works — what is missing is the path a person takes to it.

**Rule:** every slice ships a caller, and its gate asserts the *screen-level* route, not only the endpoint.
`cy.request` reaches an endpoint whether or not a UI exists.


---

## Every slice adds its manual cases to the Test Book

**The Test Book is the single manual reference for walking the product end to end.** A slice with a green
Cypress run and nothing written there is **not finished**.

Not a new page, not an appendix, not a section in the slice doc — *that* page, so there is one place a person
can walk the whole product without knowing which slices exist.

**What a slice adds:**

* **What a person should SEE**, in their words rather than the system's. "The dialog buttons read as words" —
  not "`ui.completeSale` resolves".
* **Every case marked ⚠ that records something actually found broken.** Those are the ones worth repeating
  after any nearby change, and they are the reason the page is more than a feature list.
* **Any defect the slice could not close**, into *Not yet verified* rather than left unsaid.

**And correct what the slice made wrong.** A slice that changes behaviour must fix the existing wording it
invalidates. ONB-1 is the worked example: it changed what a tenant with no shape sees, which contradicted a
note in the Settings section that had been true for months. **A page that quietly contradicts the product is
worse than no page, because it is trusted.**

### Why a manual page at all, when there are automated gates

The gates have been green through a blank credit note, a credit note with no customer on it, a scan box that
ignored its own setting, and **nine features that shipped where nobody could click them**. Every one was found
by a person looking at a screen.

An automated case asserts what someone thought to assert. The manual walk is what finds what nobody thought of
— which is why `cy.request` reaching an endpoint has never been evidence that a feature is reachable.

---

## The gate is written BEFORE the implementation

**Analyze docs + standards → share the analysis for review → Document → Design → write the Cypress cases →
Implement → Test until green → write MANUAL test cases and share them → then ask for commit.**

A gate written *after* the code lets the implementation decide what is asserted: the spec describes what was
built rather than what was required, and it passes because it was shaped to fit. Written first, the cases are
the requirement, and the implementation is what turns them green.

It also forces the expensive questions early — which tenant owns the feature, which rung of the privilege
ladder, what the refusal ENVELOPE looks like, what regression each case guards — while they are still cheap
to answer.

The standards analysis is shared for review **before** documenting or designing, not alongside it.

And a green gate is not the end. **Manual test cases are written after green and before the commit request** —
automated green proves the code does what the spec says; the manual pass is how the person who asked for the
feature sees it working, on their own screens, in their own language. Each step carries the exact figure to
expect, so a deviation is obvious rather than a judgement call.

### ⚠ `.pos-more` has now hidden TWO fields on the sale screen

`pos-rowentry.css` contains:

```css
.pos-rowentry #Sell .pos-more { display: none; }
```

It waits on a "More" popover that was never built, so **anything placed in that class on the sale screen is
invisible to the cashier**. It has now swallowed two controls:

| Field | Consequence |
|---|---|
| `#sellSerial` (SER-3) | all 15 server cases passed while the field was unreachable |
| `#sellBonus` (#17 P3) | nine server cases passed while a cashier could not give free goods at all |

Both were found by a person opening the screen, not by a suite.

**Before adding a control to the sale row, check whether it is reachable.** And assert
`should('be.visible')` plus a real `.type()` — never merely that the element exists in the DOM. An element
can be present, correct, bound to working code, and still hidden.

---

## Every slice needs at least one case that drives the REAL UI end to end

An API-only gate answers "does the server do the right thing". It does **not** answer "can a person do this",
and the two have diverged repeatedly in this codebase.

### The case that proved it

#17 P3 shipped with nine passing cases. Every one posted JSON to `/addSell` through `cy.request`. They found
four genuine defects — the phantom-stock reservation, a write-then-read-back COGS bug, a missing D11 fallback,
and a skipped batch record — so they were far from worthless.

But the Bonus box on the till was **invisible** (inside `.pos-more`, which is `display:none`), and all nine
still passed. A person opening the screen found it in seconds.

The sharper failure is in how the helper worked:

```js
if (bonus) line.bonusQuantity = bonus     // the test BUILDS the payload a working UI would send
```

A test that constructs the request itself cannot detect a broken UI, because it has replaced the UI.

### The rule

**Every slice's gate includes at least one case that drives the screen a person actually uses**: click the
control, type the value, submit the form, and assert the outcome. No hand-built request bodies on that path.

API cases stay — they are faster, they isolate server behaviour, and they caught four real defects here. The
end-to-end case is what makes them trustworthy as a whole.

### What "end to end" means concretely

| Not end to end | End to end |
|---|---|
| `cy.request('POST', '/addSell', {…})` | select the product, type the quantity, click Add to Cart, click Complete Sale |
| `expect(el).to.exist` | `should('be.visible')` **and** `.type()` — an element can exist, be correctly bound, and still be hidden |
| asserting the response body | asserting what the shop sees afterwards: stock, the invoice, the ledger |

### Where to put it

**First case in the file, not last.** It is the one that answers "does this feature exist for a user"; the
rest answer "is it correct". Ordering them that way makes a red run tell you which question failed.
