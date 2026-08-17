# Deploy Education / School Management

Platform setup, secrets, VPS build-out, TLS, firewall, ops and troubleshooting are in
**[`DEPLOY-COMMON.md`](DEPLOY-COMMON.md)** — read that first. This covers only what education adds.

**Dashboard:** `/educationDashboard` · **User type:** `EDUCATION`

---

## 1. What education adds

On top of the eight platform components in COMMON §1:

| Component | Compose service | Port | DB | Role |
|---|---|---|---|---|
| Education service | `education-service` | 8084 | `myplusdb_education` | Students, guardians, staff, classes, subjects, vehicles, attendance, fees, discounts, alerts |
| Finance service | `finance-service` | 8094 | `myplusdb_finance` | Fee revenue → GL. **Best-effort**: a fee still saves if finance is down (transactional outbox retries) |
| Party service | `party-service` | 8096 | `myplusdb_party` | Shared contact master — links a student to the same person elsewhere |

**RAM:** platform ~7 GB + education 0.75 + finance 0.75 ≈ **~8.5 GB**. An 8 GB VPS is tight but workable;
12 GB comfortable.

**Droppable:** `party-service` (Contact 360 goes empty, `party_id` stays NULL — nothing else changes).
`finance-service` is droppable only if you don't need fee revenue in the ledger; the outbox will queue
events and deliver when it returns.

---

## 2. Build

```powershell
# from repo root
mvn -q -pl microservices/education-service,microservices/finance-service `
    -am -DskipTests clean package -f microservices/pom.xml

mvn -q -DskipTests clean package        # monolith UI → target/myplus.jar
```

## 3. Run

```powershell
cd microservices
docker compose up -d --build `
  mysql redis eureka-server config-server api-gateway auth-service notification-service `
  education-service finance-service monolith
```

Verify per COMMON §4, then:

```powershell
curl -I http://localhost:8080/educationDashboard     # 302 to login when signed out
```

---

## 4. Schema

Flyway **V1–V7** on `myplusdb_education`:

| | |
|---|---|
| V1 | baseline |
| V2 | org scoping (`organization_id` everywhere) |
| V3 | whole-number fees |
| V4 | fee branch-scope flag |
| V5 | `org_setting` (owner Configuration screen) |
| V6 | party bridge (`party_id`) |
| V7 | scoped indexes |

On first start, confirm in the log:

```
Migrating schema `myplusdb_education` to version "7 - scoped indexes"
```

If you don't see migrations run, the service is on a stale jar — see COMMON §8.

---

## 5. Smoke test

Sign in as an `EDUCATION` user, then:

1. **Campus** → create a branch
2. **Classes** → create a class in that branch
3. **Students** → enrol a student (branch + class)
4. **Guardians** → add a guardian, link to the student
5. **Fee Collection** → record a payment → it appears in the Fee Report
6. **Attendance** → load the class roster, mark and save
7. **Configuration** → the *Branch policy* group renders with its toggles

---

## 6. Owner configuration

**Configuration → Branch policy** (multi-campus groups only; all default **OFF** = org-wide):

| Setting | Effect when ON |
|---|---|
| `edu.guardian.branchScoped` | A branch sees only guardians with a student at that branch |
| `edu.discount.branchScoped` | A branch sees only discounts applied to a student at that branch |
| `edu.staff.branchScoped` | A branch sees only staff assigned to a class at that branch |
| `edu.subject.branchScoped` | A branch sees only subjects attached to a class at that branch |

Two behaviours to explain to an owner before they switch these on, because both look like bugs otherwise:

- **A record attached to nothing stays visible everywhere.** A teacher assigned to no class, a subject on
  no class. Hiding them would make rows vanish the moment a toggle flips.
- **Owners, supers, and anyone with no branch grants keep seeing everything.** Otherwise the first admin
  to enable it meets an empty screen.

**Fee-collection branch scoping lives elsewhere** — `FeeSetting.feeCollectionBranchScoped` on the **Fee
Settings** screen, not Configuration. Same idea, different screen; a known inconsistency, tracked in
`microservices/docs/slices/edu-branch-scope-settings.md` §6.

Other education settings: **Fee Settings** (fee mode, due day, session dates, branch scoping).

---

## 7. Education-specific gotchas

**Fees are whole-number `Integer` by design.** Not a rounding bug and not to be "fixed" — the whole fee
system is integer end to end.

**`myplusdb_education` is separate from `myplusdb`.** The monolith owns no database; business/POS data
lives in `myplusdb` (business-service). An education deployment does not need `myplusdb`.

**Multi-branch grants only reach a service on a fresh token.** Assign branches to a teacher *before* they
log in — an existing session carries the old claims until re-login.

**Attendance is the table that grows.** One row per student per day: ~400k rows/year at 2,000 students.
Size the MySQL volume for it, and expect the analytics dashboard to slow as it fills (a known open item —
`AnalyticsController` loads whole tables per render; see `microservices/docs/education-review-audit.md`
finding D).

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

Include `myplusdb_auth` (accounts/tenants) and `myplusdb_finance` if you run it — an education backup
without auth cannot be restored into a working system.
