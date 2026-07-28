# Manual Migrations Log

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

## Pending #5 — pharma: dead tables kept on purpose (review finding D2)

`myplusdb_pharma` carries four tables with **no entity and no repository** anywhere in pharma-service:

| Table | Origin |
|-------|--------|
| `medicines` | the pre-catalog medicine master, replaced by the catalog Product (slice 100 / M5) |
| `pharmacy_stock` | pharmacy's own stock, replaced by inventory-service `StockEntry` |
| `drug_categories` | fed the old `DrugCategory` entity, deleted in the pharmacy review (D2) |
| `medicine_profile` | created by `V2__pharma_rebase.sql` for a model that was never built |

They are **deliberately not dropped**. The Java side is gone (the orphan `DrugCategory` entity was deleted), so
nothing reads or writes them and `ddl-auto: validate` ignores unmapped tables — they cost nothing but disk. A
drop is irreversible, and whether any deployment still holds rows in them has not been established.

Before dropping, per environment:

```sql
SELECT 'medicines' t, COUNT(*) n FROM medicines
UNION ALL SELECT 'pharmacy_stock', COUNT(*) FROM pharmacy_stock
UNION ALL SELECT 'drug_categories', COUNT(*) FROM drug_categories
UNION ALL SELECT 'medicine_profile', COUNT(*) FROM medicine_profile;
```

All zero everywhere → ship a `V6__drop_dead_pharma_tables.sql` (drop `medicines` last: `pharmacy_stock`,
`drug_categories` and the legacy `prescription_items.medicine_id` / `dispensing.medicine_id` FKs reference it).
Any non-zero → export first; those rows are the only copy of the pre-catalog pharmacy data.

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

**Unmapped tables left in place** (no entity, but NOT dropped — a drop is irreversible and these have not been
confirmed empty across every environment): `myplusdb_inventory.categories`, `myplusdb_inventory.products`
(superseded by catalog-service's product master), plus the four pharma tables in "Pending #5" above.
Verified row counts on dev at the time of the sweep: all zero except `myplusdb_pharma.medicine_profile`,
which holds **1 row of Cypress debris** (`CyBrand` / `CyMed_1782237989720`, org 20).
