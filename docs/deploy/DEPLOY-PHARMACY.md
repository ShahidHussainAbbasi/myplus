# Deploy Pharmacy

Platform setup, secrets, VPS build-out, TLS, firewall, ops and troubleshooting are in
**[`DEPLOY-COMMON.md`](DEPLOY-COMMON.md)** — read that first. This covers only what pharmacy adds.

**Dashboard:** `/businessDashboard` (shared commerce dashboard, white-labelled) · **User type:** `PHARMA`

> **Pharmacy is POS plus a clinical layer.** It reuses the entire trade backend — the same sale, stock,
> tax, tenders, receipts and ledger. So this is the **POS stack** ([`../../DEPLOY-POS-RETAIL.md`](../../DEPLOY-POS-RETAIL.md))
> **plus `pharma-service`**. If you have already read the POS runbook, only §1 and §5–7 here are new.

---

## 1. What pharmacy adds

Platform (COMMON §1) + the POS trade stack + pharma:

| Component | Compose service | Port | DB | Role |
|---|---|---|---|---|
| Catalog service | `catalog-service` | 8092 | `myplusdb_catalog` | **The medicine master.** A medicine IS a catalog Product — no separate table. Owns `rxRequired` / `controlledSubstance` |
| Inventory service | `inventory-service` | 8082 | `myplusdb_inventory` | Stock + FEFO batch/expiry picking |
| Business service | `business-service` | 8083 | `myplusdb` | **The sale.** Dispensing is an ordinary POS sale; also enforces the prescription-only rule |
| Pharma service | `pharma-service` | 8087 | `myplusdb_pharma` | Prescriptions, dispensing records, clinical flags, drug interactions, controlled register |
| Finance service | `finance-service` | 8094 | `myplusdb_finance` | Payments, receipts, ledger |
| Audit service | `audit-service` | 8095 | `myplusdb_audit` | Append-only money/stock trail (outbox-delivered) |
| Party service | `party-service` | 8096 | `myplusdb_party` | Patient ↔ customer identity |

Every one of these is in `docker-compose.yml` and in the default (POS) profile — except `pharma-service`,
which the `pharmacy` profile adds. Nothing here needs a hand-typed service list.

**RAM (measured 2026-08-05):** the POS subset is **10.75 GB** of `mem_limit`; `pharma-service` adds 0.75
→ **11.5 GB**. Size **≥ 12 GB**. Those are ceilings; idle RSS is roughly half, but the ceiling is what
matters once the box is under load. **8 GB is not enough** — see FULL-STACK §9.

**Not droppable:** `catalog-service`, `inventory-service`, `business-service` — without them you cannot
dispense, because dispensing *is* a sale.
**Droppable:** `party-service` (Contact 360 empty, patient↔customer stays `NULL` and re-links on the next
write), `audit-service` (events queue and deliver later). Both are in the default profile, so dropping one
means stopping it deliberately — not omitting it from a list.

---

## 2. Build

Every Dockerfile is runtime-only (`COPY target/*.jar`), so **the jars must exist before `docker compose
build`** — a container will otherwise start happily on last week's jar (see §3 pre-flight).

```powershell
cd microservices
mvn -q -DskipTests -pl pharma-service,business-service,catalog-service,inventory-service,finance-service,audit-service,party-service,auth-service,notification-service,api-gateway,config-server,eureka-server -am install

cd .. ; mvn -q -DskipTests clean package        # monolith UI
```

## 3. Run

```powershell
cd microservices
docker compose config --services                     # PRE-FLIGHT: 14 names, all resolvable
docker compose --profile pharmacy config --services  # the same 14 + pharma-service
docker compose --profile pharmacy up -d --build
```

The `pharmacy` profile **is** the POS subset plus `pharma-service` — catalog, inventory, business, finance,
audit and party are already in the default profile, so there is nothing extra to name. Run the pre-flight
first: a service named in a runbook but missing from `docker-compose.yml` fails in 2 seconds there instead
of mid-deploy (that is exactly how the 2026-08-04 `party-service` outage happened).

> Do **not** use `--profile full` for a pharmacy — it adds seven unrelated verticals and takes the stack
> from ~10.3 GB to ~16 GB.

---

## 4. Schema

`myplusdb_pharma` migrations are baseline · tenant scope · productId rebase · clinical flags · party
bridge · scoped indexes · drop dead medicine schema. Pharmacy also depends on the catalog, inventory and
business schemas being current.

Confirm they migrated — a container starts fine on a stale jar and reports nothing anywhere:

```bash
cd microservices && ./verify-schemas.sh pharma catalog inventory business
# -> a row each, and "All 4 schemas match this checkout."
```

`STALE JAR` on any row means that container predates its migrations — rebuild (§2) before going further.

> **This section used to name the expected numbers** ("pharma V6, catalog V8, business V36"). Business is
> at **V40** now, and that list went stale four times across the doc set — a stale expectation either
> waves through a genuinely stale deploy or sends you rebuilding jars that were fine.
> `verify-schemas.sh` reads what to expect from the migration files on disk, so it cannot drift.
>
> It also encodes the trap: **never `MAX(version)`** — that column is a VARCHAR, so `MAX()` sorts
> lexically and a schema at V40 reports `9`. Order by `installed_rank`.

> **Clinical-flag backfill.** `rxRequired` / `controlledSubstance` were moved from pharma-service onto the
> catalog product so the sell path reads them off the `ProductRef` it already fetches — no extra call at
> checkout, and the till never depends on pharma-service being up. **On an upgrade, existing flags need
> backfilling into catalog**, or prescription-only enforcement silently does nothing. `pharma-service` has
> a backfill service for this; run it once after deploying and verify a known Rx-only medicine is refused
> at the counter.

---

## 5. Smoke test

1. **Product** (labelled *Medicine*) → create one with stock
2. **Clinical & Safety** → tick *Prescription required*
3. **Sell** → try to sell it directly → **refused**: *"…is prescription-only…"*
4. **Prescriptions** → record a script for a patient with that medicine
5. **Dispense** → hands off to Sell with the banner → complete the sale
6. Prescription status → `PARTIALLY_DISPENSED` or `FULLY_DISPENSED`
7. **Clinical & Safety** → tick *Controlled substance*, dispense → row appears in **Alerts & Register**

Step 3 is the one that proves the deployment: it exercises catalog (the flag), business-service (the
guard) and the org config together.

---

## 6. Owner configuration

**Configuration → Pharmacy:**

| Setting | Default | Effect |
|---|---|---|
| `pharmacy.rx.requirePrescription` | **On** | A sale containing an Rx-only medicine is refused unless it declares a prescription |
| `pharmacy.interaction.blockSevere` | Off | A SEVERE drug interaction must be acknowledged before dispensing |

Both are inert for a non-pharmacy tenant — no product of theirs carries the flags.

The Rx rule is **server-side and privilege-independent**: it binds an owner exactly as it binds a cashier,
because it is a clinical/legal rule, not a permission.

---

## 7. Pharmacy-specific gotchas

**A medicine is a catalog Product.** There is no medicine table. Deploy catalog-service or you have no
medicines.

**SKU is optional** (catalog V5, still in force at V8). Blank is stored as `NULL`, never `''` — `''` collides
with every other uncoded product. If an upgrade shows *"Product SKU already exists: "* with an empty value,
V5 has not run.

**The controlled register is thin.** It records date, medicine, quantity, patient, invoice — but **not**
the prescriber, prescription id, or batch. Real controlled-drug regulations usually want all three. Do not
promise a customer it is audit-ready without checking their jurisdiction. Tracked as pharmacy review item
E; see `microservices/docs/pharmacy-prescriptions-use-case.md` §8.

**Interactions are per-dispense only.** No patient medication history — a clash with something dispensed
last week is not detected. There is also no UI to list, edit or delete an interaction once entered.

**Expiry is derived, not stored.** A prescription past `validUntil` reads as EXPIRED without any scheduled
job. Nothing to schedule; nothing to fail silently.

---

## 8. Backup

```bash
cd microservices && ./backup-db.sh
```

**Back up all 16 databases together, not just this module's.** This section used to dump one schema and
then tell you to "also include `myplusdb_auth`" — that instruction exists *because* the databases are
interlinked, and hand-listing the ones you happen to think of is exactly how you end up with a backup
that restores into a state that never existed. Accounts and tenants live in `myplusdb_auth`, anything
financial reaches `myplusdb_finance`, contacts reach `myplusdb_party`.

The old command also omitted `--single-transaction`, so it took a global read lock: on a live system
that is an outage for as long as the dump runs.

`backup-db.sh` takes all 16 in **one** consistent snapshot without locking, and captures `.env` and the
deployed git SHA alongside it — a restore needs all three. Day-one setup, verification, restore, and
rebuilding a lost host: [`DEPLOY-FULL-STACK.md` §8](DEPLOY-FULL-STACK.md#8-backup-and-restore--end-to-end).

Back up **all four together plus `myplusdb_auth`** — a prescription references catalog products and a
business-service invoice. Restoring one without the others yields dangling references.
