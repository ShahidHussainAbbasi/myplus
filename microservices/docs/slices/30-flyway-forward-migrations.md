# Slice 30 — Flyway forward migrations (tech-debt #13)

Status: **business-service V2 DONE + VERIFIED** ✅ (2026-06-14) — Flyway applied V2 on restart
(`flyway_schema_history`: `2 | slice21 28 org money invoice | SQL | success=1`), idempotent no-op on the
already-migrated dev DB. Follow-up: V2 for education/welfare/agriculture, then flip to `validate`.

## Document — what & why
Each service already has a `V1__baseline.sql` and dev DBs are **baselined at V1**
(`baseline-on-migrate`), but there were **no forward migrations** for the schema changes made in slices
21–28 (org_id, invoice columns, money→DECIMAL). Those reached dev via `ddl-auto: update` + manual
`migrations.md` only — so a **fresh or prod (`validate`) deploy would be missing them** and fail. Close
that by authoring Flyway forward migrations, starting with business-service as the exemplar.

## Design
- `V2__slice21_28_org_money_invoice.sql` (business-service): adds `organization_id` to all 10 domain
  tables, `invoice_seq`/`invoice_no` to `customer_history`, and converts money columns to `DECIMAL(19,2)`.
- **Idempotent**: `ADD COLUMN`s guarded via `information_schema` (no-op if already present, e.g. dev where
  ddl-auto added them); money `MODIFY` is naturally idempotent. → safe on fresh, dev, and prod.
- Runs after the V1 baseline. `ddl-auto: update` stays in dev as a backstop (Flyway runs first at
  bean-init, then Hibernate reconciles); prod (`validate`) relies on Flyway being complete.
- Unique-constraint changes (item_type/item_unit per-tenant unique; `unique(organization_id,
  invoice_seq)`) stay in `migrations.md`: index names vary per env and Hibernate `validate` doesn't check
  them, so they're applied operationally.

## Policy going forward
1. **All structural schema changes are Flyway scripts** (`db/migration/V{n}__*.sql`), not ad-hoc DDL.
2. Dev may keep `ddl-auto: update` for iteration speed; the canonical schema is Flyway. Target end-state:
   `ddl-auto: validate` (or `none`) everywhere once each service's forward migrations are complete.
3. `migrations.md` entries get folded into versioned scripts.

## Implement (checklist)
- [x] business-service `V2__slice21_28_org_money_invoice.sql` (idempotent)
- [ ] build/restart business-service → Flyway applies V2 (no-op on dev) — **awaiting build**
- [x] welfare V2 (donation, donator) + agriculture V2 (income, expense, land) — idempotent org_id adds
- [x] education V2 (org_id across its 14 domain tables) — idempotent, mirrors business V2
- [ ] (follow-up) flip services to `ddl-auto: validate` once each V2 is confirmed applied in every env

## Test / rollout note
- On the dev DB (baselined V1, columns already present), V2 applies as a no-op and records a V2 row.
- On a fresh DB: V1 baseline runs, then V2 adds columns / sets DECIMAL.
- **If V2 errors** (e.g. an unexpected table name): Flyway marks V2 failed and the service won't start —
  run `flyway repair` (or delete the failed row) after fixing, then restart. Send me the log and I'll fix.
