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
