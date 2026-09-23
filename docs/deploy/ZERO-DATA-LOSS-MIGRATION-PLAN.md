# Zero-data-loss migration plan

**For:** deploying the current `feature/pack-loose-selling` line to production.
**Companion file:** [`preflight-data-loss-check.sql`](preflight-data-loss-check.sql) — run it; do not skip it.

---

## The one question that decides everything

**Does production already hold data, or is this its first deployment?**

| | What this release does |
|---|---|
| **Fresh database** (first deploy) | Flyway creates everything from V1. The destructive migrations find empty tables and no-op. **Risk of data loss: none.** Do steps 1, 6 and 7 only. |
| **Existing database** | 190 migrations apply in sequence, 4 of them irreversible. **Every step below is required, in order.** |

Everything that follows assumes the second case, because that is the case that can lose data.

---

## What this release will do to existing data

This branch adds **190 migration files** (206 in the tree). The overwhelming majority are additive — new tables,
guarded `ADD COLUMN`, new indexes — and cannot lose anything. **Seven** can.

### Irreversible — 4 files, 7 tables and 14 columns dropped

| Migration | Destroys | Justified by |
|---|---|---|
| business `V7__drop_local_stock` | `stock` table; `stock_id` on sell, purchase, item | V5/V6 backfilled product_id + snapshots first |
| business `V8__drop_item_tables` | `item`, `item_catalog_map`; `item_id` on purchase, sell | the Item→Product convergence is complete |
| pharma `V3__pharma_itemid_to_productid` | `item_id` on 4 tables (when `product_id` also exists) | "pharmacy is pre-production" |
| pharma `V6__drop_dead_medicine_schema` | `medicines`, `pharmacy_stock`, `drug_categories`, `medicine_profile`; 4 `medicine_id` columns | "Dev row counts … medicines 0, pharmacy_stock 0" |

### Changes stored values in place — 3 more

| Migration | Changes | Consequence |
|---|---|---|
| business `V2` / `V3` | money `DOUBLE` → `DECIMAL(19,2)` | every stored amount rounds to 2dp |
| education `V3__fee_whole_number` | `grade.fee`, `student.fee` `FLOAT` → `INT` | **fees round to whole units — what a parent owes changes** |
| business `V10`, catalog `V16` | `idempotency_key` → `VARCHAR(191)` | keys over 191 chars truncate |

### Why the annotations are not enough

Each of those migrations says it is safe, and each says so on the strength of a measurement **taken on the dev
database** and recorded **in a comment**:

> `V6:` *"Dev row counts at the time of writing: medicines 0, pharmacy_stock 0 … Pharmacy is pre-production"*
> `V5:` *"Covers items already in item_catalog_map … Items NOT yet mapped need a catalog import first"*

A comment cannot inspect production. `preflight-data-loss-check.sql` asks the same questions of the database you
are about to deploy to, and answers PASS / STOP per check.

### ⚠ The one that cannot be repaired afterwards

`V5` rebuilds `sell.product_id` / `purchase.product_id` **only for items present in `item_catalog_map`.** `V7`
then drops `stock_id`, and `V8` drops `item_catalog_map` itself — so a row V5 could not map loses both its old
link *and* the mapping that could have rebuilt it. Permanently.

**In practice this is probably already handled — but "probably" is the reason to check.** The build production
is running now (anything before `ed34a435`) carries `CatalogMigrationStartupRunner`, an `ApplicationRunner`
enabled by default (`convergence.auto-migrate-catalog`, `matchIfMissing = true`) that maps every not-yet-mapped
legacy item to a catalog Product on each boot and re-backfills the sells that belong to them. If it has ever
completed against a reachable catalog-service, B1/B2 are already 0.

It is **best-effort by design**, and that is the gap: it runs on a daemon thread, retries 12 times at 10-second
intervals, and if catalog-service is unreachable for those two minutes it **gives up silently and the service
starts normally**. Nothing downstream notices. So the mapping may be complete — or it may have quietly failed on
the one boot that mattered.

**Both repair routes live only in the OLD build.** `ed34a435` (the same commit that adds V8) deletes
`CatalogMigrationController`, `CatalogMigrationService` and the startup runner together. So once this release is
deployed there is no tool left, and V7/V8 have already removed the evidence it needed. Whatever is unmapped at
that moment stays unmapped for ever.

**The window is: production, as it runs right now, before this release.** That is step 3, and it is the reason
this plan has a step 3.

---

## The plan

### Step 0 — record where production actually is

```bash
for db in myplusdb myplusdb_auth myplusdb_catalog myplusdb_inventory myplusdb_pharma myplusdb_education; do
  echo "== $db"
  mysql -h "$PROD_HOST" -u "$PROD_USER" -p -N -e \
    "SELECT MAX(version) FROM $db.flyway_schema_history WHERE success=1;" 2>/dev/null || echo "  (no flyway table — fresh)"
done
```

Write the numbers down. They decide which migrations actually run, and they are what you compare against
afterwards. A schema with no `flyway_schema_history` is fresh, and nothing below can hurt it.

### Step 1 — back up all 16 schemas, and prove the backup restores

```bash
STAMP=$(date +%Y%m%d-%H%M)
mysqldump -h "$PROD_HOST" -u "$PROD_USER" -p \
  --single-transaction --routines --triggers --events \
  --databases myplusdb myplusdb_agriculture myplusdb_analytics myplusdb_appointment \
              myplusdb_audit myplusdb_auth myplusdb_campaign myplusdb_catalog \
              myplusdb_education myplusdb_finance myplusdb_inventory myplusdb_marketplace \
              myplusdb_notification myplusdb_party myplusdb_pharma myplusdb_welfare \
  > "myplus-prod-$STAMP.sql"
gzip "myplus-prod-$STAMP.sql"
```

⚠ **`--single-transaction` only protects InnoDB.** `purchase` and `customer_history` are **MyISAM**
(see `project_doc_int`), so they are *not* in that consistent snapshot. Either take the dump during a
maintenance window with writes stopped, or add `--lock-all-tables`.

**A backup you have not restored is not a backup.** Restore the gzip into a scratch database and run
`preflight-data-loss-check.sql` against *that* before going near production.

### Step 2 — run the pre-flight

```bash
mysql -h "$PROD_HOST" -u "$PROD_USER" -p --table < docs/deploy/preflight-data-loss-check.sql
```

Read every row. The last line gives the verdict. **Any `STOP` means do not deploy** — act on the note in that
row, then run it again. Keep the output with the release notes: it is the evidence the deploy was lossless.

### Step 3 — close the gaps the pre-flight found, on the CURRENT version

Production is still running the old build here, which is exactly what makes this possible.

- **B1 / B2 > 0** — the auto-migrate runner has not finished. Two options, both only available on the build
  production is running now:
  1. **Restart business-service with catalog-service healthy** and watch for its log line
     `Catalog auto-migrate: all items already mapped — nothing to do.` — that sentence is the completion signal.
     Anything else means it is still working or gave up.
  2. **Call it by hand** (`CatalogMigrationController` is mapped at `/admin`, reached through the gateway with
     `StripPrefix=2` — commit `244aeb77`):
     ```bash
     curl -X POST "$PROD_URL/api/business/admin/migrate-catalog" -H "Authorization: Bearer $OWNER_TOKEN"
     ```
  Re-run the pre-flight until B1 and B2 both read 0. ⚠ Verify the endpoint responds before relying on it — it
  exists only in pre-`ed34a435` builds.
- **P1–P5 > 0** — a pharmacy tenant is live, and the "pre-production" justification no longer holds. Export the
  affected rows and decide what the catalog equivalent is **before** deploying. Do not let V6 run against them.
- **E1 / E2 > 0** — every listed row is a fee amount that will change. Export `student_id, fee` first; confirm
  with the school whether rounding is acceptable.
- **X1 / X2 > 0** — see "The runtime risk" below. Fix before deploying, not after.

### Step 4 — deploy with `validate`, never `update`

`application-prod.yml` already sets `ddl-auto: ${DDL_AUTO:validate}`. **Confirm `DDL_AUTO` is unset or
`validate` in the production environment.** Under `update`, Hibernate alters columns behind Flyway's back
(catalog `V16` warns about exactly this); under `validate`, a mismatch stops the service — loud, and it changes
nothing.

Order: `config-server` → `eureka` → everything else. A service whose Flyway step fails will not start; that is
the design working. **Read the startup log for `Current version of schema` rather than assuming.**

### Step 5 — verify against step 0

```sql
-- row counts that must not have moved
SELECT 'sell' t, COUNT(*) n FROM myplusdb.sell
UNION ALL SELECT 'customer_history', COUNT(*) FROM myplusdb.customer_history
UNION ALL SELECT 'customer',         COUNT(*) FROM myplusdb.customer
UNION ALL SELECT 'purchase',         COUNT(*) FROM myplusdb.purchase;

-- and the figure that matters most: nothing lost its product identity
SELECT COUNT(*) AS orphaned_sales FROM myplusdb.sell WHERE product_id IS NULL;
```

Compare with the same queries captured in step 0. Reconcile the trial balance and total receivables against the
pre-deploy figures — the money columns were rounded, so small differences are expected and must be *explained*,
not assumed.

### Step 6 — rollback

| | Reversible? |
|---|---|
| Additive migrations (new tables/columns) | Yes — roll back the image; the extra columns sit unused |
| Money `DOUBLE`→`DECIMAL`, fee `FLOAT`→`INT` | **No.** The old precision is gone |
| The 4 irreversible migrations | **No.** Restore from step 1 |

So the rollback plan for anything past the destructive migrations is **restore the dump and replay writes**.
That is why step 1 is not optional and why the maintenance window has to be long enough to restore inside it.

### Step 7 — after a fresh-database deploy

Confirm `SEED_DEMO=false` (it is the default in `application-prod.yml`). With it true, the stack seeds demo
tenants *and* `owner.business@myplus.com` with a known password and the reset privilege.

---

## The runtime risk — a live endpoint that purges a tenant

Separate from migrations, and worth fixing in the same release.

`POST /demo/reset` → `DemoPurgeController` deletes **every row carrying the caller's `organizationId`, in all 13
JPA services**, then reports success. No backup, no undo, no audit record. It is reachable by any account that is
`demo=true` **or** holds `DEMO_RESET_PRIVILEGE`.

**As configured, production is safe:** `User.demo` defaults to `false`, the single `setDemo(true)` call site sits
inside the `app.seed-demo` gate, `DEMO_RESET_PRIVILEGE` is granted only by `DEMO_ROLE`/`DEMO_RESET_ROLE`, and
`application-prod.yml` sets `seed-demo: ${SEED_DEMO:false}`. Checks **X1** and **X2** confirm it on the actual
database rather than on that reasoning.

**But the safety is configuration-deep, not structural.** Anyone setting `SEED_DEMO=true` in production — to show
the product to a customer — makes the purge reachable *and* creates a known-credential account that holds it.

This is not hypothetical. On **2026-09-22** a full-suite Cypress run reached `demo/demo-reset.cy.js`, which signs
in as `owner.business@` and calls `/demo/reset`. It purged that org in dev. The spec passed — clearing the org is
the feature — and the damage surfaced only as unrelated-looking failures in four specs that ran afterwards. The
file carried a prose warning; a test runner cannot read prose. It is now gated behind
`Cypress.env('destructive')` (`npm run test:e2e:demo-reset`).

**Shipped in this release:**

1. ✅ **The purge is refused under the `prod` profile** unless `app.demo.purge-enabled=true`
   (`DEMO_PURGE_ENABLED`, listed explicitly in `application-prod.yml` and defaulting to false). The check runs
   *before* the privilege check, so it does not depend on who is asking — which matters, because the failure
   mode it exists for is an entitled account existing in production by accident.
   Gate: `DemoPurgeControllerProdGuardTest`, 6/6.
2. ✅ **The purge now says what it destroyed** — a WARN line naming user, org and service before deleting, and a
   per-entity breakdown (also returned in the response) after. Previously it reported only a grand total, which
   left nobody able to answer "what did we lose?".

⚠ **`common-service` is a shared library, so the guard only takes effect in services that are REBUILT against
it.** `mvn install` the library first, then rebuild every service — a service still running an older jar keeps
the old, unguarded controller. See `project_maven_install_libs_not_package`.

**Still open (not in this release):**

3. **The purge is not atomic across services.** It loops them one at a time with no compensation, so a failure
   halfway leaves a tenant with part of its data and broken cross-service references. Worth an outbox/saga if
   the purge is ever to be offered in production.

---

## Summary

| | |
|---|---|
| Migrations added on this branch | **190** (206 in tree) |
| Irreversible (drop data) | **4 files** — 7 tables, 14 columns |
| Change stored values in place | **3** — money 2dp, fees whole, key truncation |
| Additive / safe | the remaining ~183 |
| Provable before deploying? | **Yes** — `preflight-data-loss-check.sql`, every check read-only |
| Zero loss achievable? | **Yes, if B1/B2 are cleared on the current version first** (step 3) — that window closes the moment V7 runs |
| Runtime purge in prod | **Blocked structurally** as of this release (`app.demo.purge-enabled`, default false) |
