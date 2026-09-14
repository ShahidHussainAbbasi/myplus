# Manual Migrations Log

**Status:** REFERENCE - append-only log of manual migrations. Superseded as a mechanism by per-service Flyway (every service now owns its schema); kept because these rows already ran in real environments.

`ddl-auto: update` handles additive changes (new columns, new constraints) but will **not** drop or
alter existing columns/indexes. Those go here — run once per environment (DB: `myplusdb_education`).

| # | Slice | When | SQL | Why |
|---|-------|------|-----|-----|
| 1 | 02-owner | after education-service boots once with the new entity | `ALTER TABLE owner DROP INDEX UKjryjfrl809i37qthat5rwpnq5;` | Remove the old global `UNIQUE(name)`. Replaced by per-tenant `uq_owner_org_name(organization_id, name)` which Hibernate adds automatically. Global uniqueness would let one tenant's owner name block another tenant. |
| 2 | 21-business-org-scoping | DONE 2026-06-13 (DB: `myplusdb`) | `ALTER TABLE item_type DROP INDEX UK1pvqr9hc1t8cgbbhc5434lfff;` | Removed the old global `UNIQUE(name)` on `item_type`. Replaced by per-tenant `UNIQUE(organization_id, name)` (`UKlvh4a93g1497hvt6apwvexweo`) which Hibernate added automatically. NOTE: `item_unit` had **no** old global `name` unique in this env (nothing to drop), and Hibernate did **not** add the composite to it (108 existing rows) — so `item_unit` currently has no DB-level name uniqueness; the app-level scoped dup-check covers within-tenant. Optional follow-up: `ALTER TABLE item_unit ADD CONSTRAINT uq_item_unit_org_name UNIQUE (organization_id, name);` (safe — existing rows are org-NULL → distinct). |

| 3 | 23-bigdecimal-money | after business-service boots once on the new entities (DB: `myplusdb`) | see block below | Money fields changed `FLOAT`→`BigDecimal`; `ddl-auto` will **not** alter existing column types. MySQL converts existing values in place (back up first). Required before prod (`validate`). |

```sql
-- slice 23: money columns FLOAT -> DECIMAL(19,2) (DB myplusdb)
ALTER TABLE sell             MODIFY sell_rate DECIMAL(19,2), MODIFY discount DECIMAL(19,2),
                             MODIFY total_amount DECIMAL(19,2), MODIFY net_amount DECIMAL(19,2),
                             MODIFY sell_return_profit DECIMAL(19,2);
ALTER TABLE purchase         MODIFY total_amount DECIMAL(19,2), MODIFY net_amount DECIMAL(19,2);
ALTER TABLE customer_history MODIFY paid_amount DECIMAL(19,2), MODIFY due_amount DECIMAL(19,2);
ALTER TABLE customer         MODIFY due_amount DECIMAL(19,2);
ALTER TABLE stock            MODIFY batch_purchase_rate DECIMAL(19,2), MODIFY batch_sale_rate DECIMAL(19,2),
                             MODIFY batch_purchaseDiscount DECIMAL(19,2), MODIFY batch_saleDiscount DECIMAL(19,2);
```

> Find the old index name in your environment with (swap TABLE_NAME / TABLE_SCHEMA per row):
> `SELECT INDEX_NAME, GROUP_CONCAT(COLUMN_NAME) FROM information_schema.STATISTICS WHERE TABLE_SCHEMA='myplusdb_education' AND TABLE_NAME='owner' GROUP BY INDEX_NAME;`
> For slice 21 use `TABLE_SCHEMA='myplusdb'` and `TABLE_NAME IN ('item_type','item_unit')` — the old single-column `name` index is the one to drop (keep the new `(organization_id, name)` one).

---

## Migration #4 — slice 59 (P12): add INSURANCE to the payment method enum

Hibernate 6 maps `@Enumerated(STRING)` to a native MySQL `enum(...)` column. Adding a new `PaymentMethod` value
(`INSURANCE`) is NOT applied by `ddl-auto: update` (it never alters enum columns), so inserting an INSURANCE tender
fails with "Data truncated for column 'method'". Run once on the business DB (`myplusdb`):

```sql
ALTER TABLE payment
  MODIFY method enum('BANK_TRANSFER','CARD','CASH','CREDIT','INSURANCE','REFUND','WALLET') DEFAULT NULL;
```

Applied 2026-06-26 on dev `myplusdb`. (General rule: any new enum value on an `@Enumerated(STRING)` field needs an
ALTER … MODIFY of its MySQL enum column — ddl-auto won't add it.)

---

## #5 — pharma: dead medicine schema DROPPED (review finding D2) ✅

Shipped as `pharma-service/.../V6__drop_dead_medicine_schema.sql`. It removes the pre-catalog medicine world —
`medicines`, `drug_categories`, `pharmacy_stock`, `medicine_profile` — none of which had an entity, repository or
query left.

**The important half was not the dead tables.** Three *live* tables still carried dead FK columns into
`medicines`: `prescription_items.medicine_id`, `dispensing.medicine_id`, `drug_interactions.medicine1_id` and
`.medicine2_id`. Hibernate stopped mapping them at the rebase so every row since inserted NULL, but MySQL was
still enforcing the constraints. V6 drops the FKs, then the columns, then the tables (children first).

Verified before writing it: **0 non-NULL values** in all four columns across 74 `prescription_items`, 18
`dispensing` and 5 `drug_interactions` rows; and the four tables held 0 rows except `medicine_profile`, whose
single row was Cypress debris (`CyBrand` / `CyMed_1782237989720`). Dropping is safe because **pharmacy is
pre-production** — the same basis as the note in `V3__pharma_itemid_to_productid.sql`.

### ⛔ BLOCKED — `myplusdb`'s ~74 monolith-era tables must NOT be dropped yet

`myplusdb` is business-service's database (not orphaned — it has 27 migrations). It also still holds every
pre-split monolith table: education (`student`, `grade`, `guardian`, `school`…), welfare, agriculture,
appointment, auth (`role`, `privilege`, `user_account`), campaign. An entity-vs-table diff flags 74 of them.
**Dropping them was investigated and rejected**, for two independent reasons:

**1. Six of the 74 are live.** business-service maps ids with
`@SequenceGenerator(sequenceName = "cust_seq")` and friends — on MySQL a sequence generator is a *table*. So
`cust_seq`, `item_type_seq`, `item_unit_seq`, `purch_seq`, `sell_seq`, `vender_seq` are load-bearing despite
having no `@Table`. Same trap as the `@JoinTable` collection tables above.

**2. `myplusdb.company` holds 336 rows that were never migrated.** The live business table is `companies`
(different schema: `id`/`created_at`/`organization_id`), the legacy one is `company`
(`company_id`/`dated`/`faceBook`/`wattsApp`, no org column). Of 339 legacy rows, **336 have a name that does
not appear in `companies`** — all owned by `user_id` 37, dated May 2026, with real-looking addresses, emails and
phone numbers. The monolith→business migration evidently did not carry them.

Verify before doing anything with these:

```sql
SELECT COUNT(*) FROM myplusdb.company o
 WHERE NOT EXISTS (SELECT 1 FROM myplusdb.companies c WHERE c.name = o.name);   -- 336 at time of audit
```

Other legacy tables holding rows: `roles_privileges` (508), `privilege` (30), `users_roles` (17),
`user_account` (17), `demo_request` (13), `role` (10), `agriculture_expense` (1), `land` (1). The auth ones were
migrated by `migrate_monolith_users.sql` (auth now has 61 users vs the legacy 17), but that is inference from
counts, not proof per row. 31 of the 74 are genuinely empty.

**Required before any drop:** decide what happens to the 336 `company` rows (migrate into `companies` with an
`organization_id`, export, or consciously discard), then drop only the verified-empty tables, explicitly
excluding the six `*_seq` generators.

### Still pending — inventory's orphan `products` / `categories`

`myplusdb_inventory.products` and `.categories` survive from before catalog-service took over the product master
(inventory's own `V3__drop_stale_product_fks.sql` already dropped the FKs that pointed at them). Both are 0 rows
on dev, and nothing maps them.

**Deliberately NOT auto-dropped**, unlike pharma's: inventory *is* deployed to production (see
`DEPLOY-POS-RETAIL.md`), so "empty on dev" says nothing about what a live VPS holds. Confirm per environment
first — the tables cost only disk until then:

```sql
SELECT 'products' t, COUNT(*) n FROM products
UNION ALL SELECT 'categories', COUNT(*) FROM categories;
```

Zero in **every** environment → ship an inventory `V6__drop_orphan_product_tables.sql` (V5 is now the scoped
indexes — see #6 below)
(`DROP TABLE IF EXISTS products;` then `categories` — `products.category_id` references it).
Any non-zero → export first and reconcile against catalog before dropping.

---

## Dead-entity sweep (2026-07-28) — Java removed, tables kept

An audit compared every `@Table`/`@JoinTable` in all 14 services against the 204 real tables in `myplusdb*`,
then checked each entity class for references. Findings and what was done:

**Deleted (unreferenced Java, no schema change):**

| Class | Service | Evidence |
|-------|---------|----------|
| `entity/DrugCategory.java` | pharma | no repository, no service, no query; table `drug_categories` is empty |
| `entity/Segment.java` + `repository/SegmentRepository.java` | campaign | a dead pair — the entity was referenced ONLY by its own repository, and that repository was never injected anywhere. Table `campaign_segments` remains |
| `com/persistence/model/BaseEntity.java` | monolith | `@MappedSuperclass`, abstract, zero subclasses |
| `com/web/dto/education/DiscountDTO.java` | monolith | stray `@Entity` on a DTO, referenced nowhere |

**NOT dead — deliberately kept** (an entity-vs-table diff flags these, so don't "clean" them later):
`roles_privileges`, `users_roles` (auth), `schools_owners`, `staff_grades` (education) are `@JoinTable`
collection tables. They have no `@Table` of their own and no entity class **by design** — dropping them
would break `@ManyToMany` mapping. `staff_grades` in particular is easy to miss: its `name = "staff_grades"`
sits on a different line from the `@JoinTable(` token, so a single-line grep does not find it.

**Unmapped tables:** the four pharma ones were subsequently dropped by V6 (see #5 above — pharmacy is
pre-production). `myplusdb_inventory.categories` / `.products` are still in place on purpose, because inventory
runs in production and "empty on dev" is not evidence about a live VPS. Verified row counts on dev at the time
of the sweep: all zero except `myplusdb_pharma.medicine_profile`, which held **1 row of Cypress debris**
(`CyBrand` / `CyMed_1782237989720`, org 20).

---

## #6 — Tenant-scope indexes across every service (2026-07-28)

An audit of all `myplusdb*` schemas found **36 tables carrying `organization_id` with no index on it**, so every
org-scoped read — the platform's most common query shape — was a full table scan. This is the pharma C3 finding
generalised. Shipped as one `*__scoped_indexes.sql` per service:

| Service | Migration | Tables indexed |
|---------|-----------|----------------|
| catalog | `V6` | `products` (the hottest table on the platform — a ProductRef per sale line), `categories` |
| inventory | `V5` | `stock_entries` (FEFO), `suppliers`, `warehouses` |
| business | `V28` | `sell`, `customer`, `companies`, `vender`, `payment`, `item_unit`, `cash_movement` |
| education | `V7` | 12 tables (`student`, `fee_collection`, `attendance`, `alert`, …) |
| auth | `V5` | `memberships` |
| welfare | `V7` | `donation`, `donator` |
| agriculture | `V5` | `agriculture_expense`, `agriculture_income`, `land` |

All are composite `(organization_id, user_id)`, because the scope predicate is the NULL-fallback
`(organization_id = :orgId OR (organization_id IS NULL AND user_id = :userId))` — one index covers both legs.
Every statement is `information_schema`-guarded, so re-running is a no-op.

**Follow-up — education `V16` (2026-08-01, finding D).** A second, *complementary* index set on education:
V7 above covers the **scope predicate** `(organization_id, user_id)`; V16 covers **scope + the filter column**
— `(organization_id, name)` for the nine duplicate checks that became `EXISTS` queries, and
`(organization_id, att_date)` / `(organization_id, payment_date)` / `(organization_id, grade_id)` for the
dashboard aggregates that replaced five whole-table loads. Neither set makes the other redundant: one serves
`WHERE org = ?`, the other `WHERE org = ? AND name = ?`. Detail: `slices/edu-D-analytics-perf.md`.

> **Open check for whoever next touches education indexes:** row 1 of this doc says Hibernate once added
> `uq_owner_org_name(organization_id, name)` automatically. No education entity carries a name-based
> `@UniqueConstraint` today, so if that unique index still exists in a live database, V16's
> `idx_owner_org_name` duplicates it and should be dropped — and `owner` already has the UNIQUE that finding
> D deliberately did *not* add elsewhere. Verify per environment before acting (standard D5).

**Deliberately excluded — `gl_outbox` and `audit_outbox`.** They already carry `(status, id)`, which is exactly
what the relay drains (`findTop100ByStatusOrderByIdAsc`); the org-scoped read on them is a debug path only.
A second index on a high-write outbox would cost insert throughput for no gain on the hot path.

**Also excluded — appointment-service.** Its four tables (`appointment`, `doctor`, `hospital`, `patient`) have
`organization_id` but **no `user_id`**, and the service has no `db/migration` directory at all (still on
`ddl-auto`). Bringing it onto Flyway is a prerequisite; the index would then be `(organization_id)` alone.

Scale note: these tables are small today (largest is `products` at ~1k rows), so the immediate win is modest.
This is preventive — the scans grow linearly with tenant count, and an index added now costs nothing.

---

## #7 — appointment-service brought onto Flyway (2026-07-28)

It was the last service still running `flyway.enabled=false` + `ddl-auto: update`, i.e. the only one whose schema
existed purely as a side effect of Hibernate — not reproducible on a fresh deploy, and impossible to index or move
to `validate`. Now:

- `V1__baseline.sql` — generated from the live `myplusdb_appointment` (`mysqldump --no-data`, `AUTO_INCREMENT`
  stripped, FK checks off): `appointment`, `doctor`, `hospital`, `patient`.
- `V2__scoped_indexes.sql` — `organization_id` on all four. **Single-column, not the usual
  `(organization_id, user_id)`**: these tables have `organization_id NOT NULL` and no `user_id` at all, because
  appointment was org-scoped from the start rather than retrofitted onto per-user rows — so there is no
  NULL-fallback leg to cover.
- `application.yml` — `baseline-on-migrate: true` + `baseline-version: 1`, so an existing database is stamped V1
  rather than rebuilt, while a fresh one is created by the baseline. `ddl-auto` stays `update`, matching the other
  11 services (Flyway runs first); the platform-wide flip to `validate` remains a separate decision.

Every service now owns its schema through migrations — standard **D1** in `SAAS-BUILD-STANDARDS.md` §1b.

---

## #8 — document-number counters reconciled on every deploy (2026-09-13)

**No manual step. That is the point of the entry.**

`org_document_seq.next_val` holds the last number issued for an `(organization_id, doc_type)` series. If it
falls behind the documents themselves, the next allocation re-issues an existing number, the series' UNIQUE
index refuses the insert, and — because `DocumentNumberService.next()` is `Propagation.MANDATORY` — the bump
rolls back with it. The counter never advances, so the tenant **permanently** loses the ability to issue that
document. Found in the wild as `Duplicate entry '13-958' for key 'customer_history.uq_ch_org_invoice_seq'`,
surfacing to the operator as "An unexpected error occurred. Please contact support."

The obvious repair is a one-line `UPDATE` against the org that drifted. That is precisely the manual
production step this log exists to stop accumulating: it fixes one row in one environment and leaves staging,
every restored backup, every customer database, and the next org to drift to be discovered by a cashier.

`V63__reconcile_org_document_seq.sql` (business-service) instead reconciles **every** series on **every**
deploy:

| series | where the number really lives |
|---|---|
| `INVOICE` | `customer_history.invoice_seq` |
| `CREDIT_NOTE` | `sale_return.credit_note_seq` (sale returns and repossession forfeits) |
| `DEBIT_NOTE` | `purchase_return.debit_note_seq` |
| `QUOTE` | `sales_quote.quote_seq` |
| `PLAN` | `installment_plan.plan_seq` |
| `OPENING` | `customer_history.invoice_no` — parsed from `OB-000042`; **no numeric column exists** |

Properties worth knowing before changing it:

- **Raises, never lowers.** `GREATEST()` is load-bearing. A counter *ahead* of its documents is legitimate — a
  rolled-back allocation leaves a gap, which is normal and auditable. Lowering one would re-issue numbers that
  are already on paper in a customer's hands.
- **Creates missing counter rows.** An org whose documents arrive before its counter (a restore, an import)
  would otherwise start at zero and collide on its first allocation.
- **Idempotent and re-runnable** per **D7**: the `WHERE c.next_val < s.max_seq` makes a second run match
  nothing. Pure DML — no DDL to guard on `information_schema`.
- **Safe on a fresh database**: the source tables are empty, so nothing is selected and nothing is written.
- **`organization_id IS NULL` rows are excluded** throughout — they predate org scoping and a per-tenant
  counter is not a question they can answer.

**Relationship to `V46__seed_invoice_and_plan_counters.sql`.** V46 was a one-time `INSERT IGNORE` seed for two
series (`INVOICE`, `PLAN`) — it creates a missing counter but cannot correct one that already exists and has
since fallen behind, which is the failure here. V63 supersedes it in scope (six series) and in kind (raises an
existing counter, not just seeds a missing one). V46 stays where it is; the two do not conflict, because V46's
`INSERT IGNORE` is a no-op once the rows exist.

Verified before shipping, both without modifying data:

- **Dry run** as a read-only report — 28 `(org, doc_type)` series across 9 orgs, **exactly one** required
  action (org 13 `INVOICE`, 957 → 958). The `OPENING` parse was checked independently (org 46, 55).
- **Executed** inside `START TRANSACTION … ROLLBACK` against the live schema: the counter moved to 958 and
  returned to 957 on rollback. This proves the statements parse and do what they claim on real data, which a
  dry-run SELECT alone does not.

**⚠ This repairs drift; it does not prevent it.** The cause — an invoice header committing in a different
transaction from its counter bump — is open, and a counter that drifts the morning after a deploy still wedges
that tenant until the next one. See §7 of `slices/doc-numbers-serialised-allocator.md` for the trace so far and
for the fix NOT to reach for.

---

## #9 — finance-service `V6__payment_optimistic_lock.sql` (2026-09-14, BLK-0c)

**No manual step.** Adds `version BIGINT NOT NULL DEFAULT 0` to `payments` and `payment_allocations`, backing
`@Version` on `Payment` and `PaymentAllocation`.

- **`NOT NULL DEFAULT 0` is load-bearing**, not a convenience: a NULL version makes Hibernate treat an existing
  row as TRANSIENT and INSERT it on its next save — on this table, duplicated money. Same lesson as V62 on
  Customer.
- **Additive and defaulted**, so a rollback of the code leaves a harmless unused column, not a broken table.
- **BIGINT** because the field is `Long`. finance runs `ddl-auto: update` today (Flyway first), but the column
  already matches what `validate` would demand, so the platform-wide flip will not trip on it.
- **What it protects is FORWARD.** `PaymentService.record` is the only writer and it only inserts, so no race
  exists yet; the first feature that edits a payment inherits the lock.

⚠ **Its header comment overstates two things** — it describes a present re-allocation-vs-edit race, and says
finance runs `validate`. **It is left unedited on purpose:** Flyway checksums a migration's comments too, and
editing one that has run anywhere makes the service refuse to start with a checksum mismatch. The correction
lives in `blocking-ui-and-backend-guards-design.md` §8.5.3. **Never edit an applied migration — not even a
comment.**

---

## #10 — business `V64__document_integrity_indexes.sql` + finance `V7__receipt_number_series.sql` (2026-09-14, DOC-INT)

Design: `slices/doc-int-numbers-and-bills.md`. **No manual step on a clean database; one conditional operator
action (V64 §1), and one deploy-order rule (V7).**

### business V64

- **§1 creates `uq_ch_org_invoice_seq` UNIQUE (organization_id, invoice_seq)** — unless a UNIQUE index on exactly
  those columns already exists, under any name. Every database that ever booted with `ddl-auto: update` already
  has it (Hibernate made it from `@Table`), so there it is a no-op. It exists for the fresh install and for the
  flip to `validate`, where nothing else would create it.
- ⚠ **It fails loudly if two invoices share a number** — `Duplicate entry '<org>-<seq>' for key
  'uq_ch_org_invoice_seq'` — and business-service will not start. That is deliberate: a silent skip would leave
  the arbiter missing in exactly the database that has already been hurt. To resolve:
  ```sql
  SELECT organization_id, invoice_seq, COUNT(*) FROM customer_history
   WHERE invoice_seq IS NOT NULL GROUP BY organization_id, invoice_seq HAVING COUNT(*) > 1;
  ```
  **Do not renumber by script** — those invoices are printed. Resolve each pair with the shop owner (void one and
  re-issue it), then restart. Local `myplusdb`, 2026-09-14: **0** duplicate groups.
- **§2 creates `idx_purchase_bill_line` (organization_id, vender_id, purchase_invoice_no(64))**, non-unique, for
  the duplicate-bill-line guard. The `(64)` prefix is load-bearing on MyISAM (full column = 1020 bytes > 1000).

### finance V7

- New `org_document_seq` (finance's own — database per service), `payments.receipt_seq BIGINT NULL`, and
  `uq_pay_org_dir_seq` UNIQUE (organization_id, direction, receipt_seq).
- Counters are seeded from the numbers already issued: RECEIPT from the highest `RCPT-######` over **every**
  direction (legacy disbursements carry that prefix), DISBURSEMENT from the highest `PV-######`. Raise, never lower.
- **Existing duplicate receipt numbers stay exactly as they are** (local: 2 groups / 4 rows), with
  `receipt_seq` NULL. Intended — they are printed. The UNIQUE binds from the first new receipt on.
- ⚠ **Deploy order: stop the old finance instance before starting the new one.** An old instance still numbering
  `COUNT + 1` beside a new one can print a number the counter also hands out.
- ⚠ **Do not roll back only the finance jar after V7 has run.** The old code's `COUNT + 1` is now BELOW the
  counters, so it would re-issue numbers already printed — and its rows carry no `receipt_seq`, so the UNIQUE cannot
  catch them. Roll forward instead.
