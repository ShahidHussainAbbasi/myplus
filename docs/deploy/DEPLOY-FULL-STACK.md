# Deploy the full stack (every module)

Platform setup, secrets, VPS build-out, TLS, firewall, ops and troubleshooting are in
**[`DEPLOY-COMMON.md`](DEPLOY-COMMON.md)** — read that first.

Use this when one host serves **all verticals** — a demo environment, or a SaaS host where tenants of
different types sign up on the same instance. If you are deploying one vertical for one customer, use that
module's runbook instead: it is a third of the RAM.

---

## 1. Everything

**Platform (8)** — mysql · redis · eureka-server · config-server · api-gateway · auth-service ·
notification-service · monolith

**Commerce core (5)** — catalog-service · inventory-service · business-service · finance-service ·
audit-service

**Verticals (6)** — education-service · pharma-service · welfare-service · agriculture-service ·
appointment-service · marketplace-service

**Cross-cutting (3)** — campaign-service (8089, `myplusdb_campaign`) · analytics-service (8090,
`myplusdb_analytics`) · party-service (8096, `myplusdb_party`)

That is **22 compose services**. Confirm it before you build, not after:

```powershell
cd microservices
docker compose --profile full config --services | Measure-Object -Line    # -> 22
```

| Port | Service | | Port | Service |
|---|---|---|---|---|
| 8080 | monolith (UI) | | 8088 | marketplace-service |
| 8081 | auth-service | | 8089 | campaign-service |
| 8082 | inventory-service | | 8090 | analytics-service |
| 8083 | business-service | | 8091 | appointment-service |
| 8084 | education-service | | 8092 | catalog-service |
| 8085 | welfare-service | | 8093 | notification-service |
| 8086 | agriculture-service | | 8094 | finance-service |
| 8087 | pharma-service | | 8095 | audit-service |
| 8761 | eureka-server | | 8096 | party-service |
| 8765 | api-gateway | | 8888 | config-server |

**RAM:** the sum of every `mem_limit` in the `full` profile is **16.75 GB** (mysql 1.5 + monolith 1 +
redis 0.25 + notification 0.5 + 18 JVMs × 0.75). Size **≥ 16 GB**, 24 GB comfortable.

Those are ceilings, not reservations — with `MaxRAMPercentage=60` real steady-state RSS is nearer 11 GB.
But headroom is the point: below 16 GB the JVMs thrash and services drop out of Eureka intermittently,
which looks like a networking fault and is not. **On an 8 GB host the full stack will not run** — see §9.

---

## 2. Build everything

Every Dockerfile is runtime-only (`COPY target/*.jar app.jar`, `eclipse-temurin:21-jre-alpine`), so
**`docker compose build` does not compile anything**. The jars must exist first, or a container starts
happily on a stale one and nothing anywhere reports an error.

```powershell
cd microservices
mvn -q -DskipTests clean install        # one reactor, all 22 modules — slow the first time
cd .. ; mvn -q -DskipTests clean package        # monolith UI → target/myplus.jar
```

Build only from a branch that compiles. `master` currently carries a Dependabot bump to Spring Boot
4.1.0 that **does not compile**; 3.5.0 is the deployable line.

## 3. Run everything

```powershell
cd microservices
docker compose --profile full up -d --build
```

> ### ⚠️ `--profile full` is required — a bare `up` gives you POS only
>
> This changed when the service list moved into profiles. `docker compose up -d --build` with no profile
> now starts the **14-service POS subset**, not everything: the eight verticals (education, welfare,
> agriculture, pharma, marketplace, campaign, analytics, appointment) carry `profiles: ["full"]` and stay
> down. Nothing errors — you simply get a stack that is missing every vertical, and the first symptom is a
> 500 from a dashboard whose service was never started.
>
> Also: **never pair a bare `up` with `--remove-orphans` on this host.** Containers belonging to a
> disabled profile are left running by a plain `up`, but `--remove-orphans` deletes them — that is a
> one-command way to tear down all eight verticals by accident.

### Verify

```powershell
docker compose ps                                  # 22 services, all healthy
curl http://localhost:8761                         # Eureka — count the registrations
curl http://localhost:8765/actuator/health
curl -I http://localhost:8080/login
```

Eureka is the real check here: with this many services, one silently failing to register is the common
failure, and it presents later as a confusing 500 from whichever module needed it. Expect **19** app
registrations (everything except mysql, redis and config-server).

**Confirm the schemas migrated.** Each service owns its schema via Flyway and applies it at startup, so a
failed migration is the difference between "container up" and "app actually works":

```bash
for db in myplusdb:36 myplusdb_catalog:8 myplusdb_pharma:6 myplusdb_inventory:5 \
          myplusdb_auth:5 myplusdb_finance:4 myplusdb_party:3; do
  docker compose exec -T mysql mysql -uroot -p"$DB_PASSWORD" -N -e \
    "SELECT '${db%%:*}', MAX(version) FROM ${db%%:*}.flyway_schema_history WHERE success=1;"
done
```

Expected: business `36` · catalog `8` · pharma `6` · inventory `5` · auth `5` · finance `4` · party `3`.
A lower number means that service is running a stale jar — rebuild (§2) before going further.

---

## 4. Per-module smoke tests

Run each module's §"Smoke test" section — they are independent and can be done in any order:

[Education](DEPLOY-EDUCATION.md) · [Pharmacy](DEPLOY-PHARMACY.md) ·
[Marketplace](DEPLOY-MARKETPLACE.md) · [Welfare](DEPLOY-WELFARE.md) ·
[Agriculture](DEPLOY-AGRICULTURE.md) · [Appointments](DEPLOY-APPOINTMENT.md) ·
[POS/Retail](../../DEPLOY-POS-RETAIL.md)

A user's `userType` decides which dashboard they land on, so you need one account per vertical to test
them all. With `APP_SEED_DEMO=true` **locally**, the seeded ladder gives you those accounts. Compose
defaults the seed flags to **false**, so a deploy seeds nothing unless you opt in — keep it that way in
production.

> #### ⚠️ Pharmacy: run the clinical-flag backfill once, right after first start
>
> `rxRequired` / `controlledSubstance` moved from pharma-service onto the **catalog product**, and the two
> live in different databases, so no Flyway script can copy them across. **Until the backfill runs, a
> prescription-only medicine sells like any other product.** It is part of the deploy, not a follow-up —
> full procedure and how to read the result in
> [`../../DEPLOY-POS-RETAIL.md`](../../DEPLOY-POS-RETAIL.md) §9.

---

## 5. Start-up order

Compose handles dependencies, but if you start services by hand the order matters:

```
mysql → config-server → eureka-server → auth-service → <everything else> → monolith
```

A service that starts before config-server gets no configuration and will not register. Symptom: it is
missing from Eureka and its log shows defaults instead of your `.env` values. Fix:

```powershell
docker compose up -d --force-recreate <service>
```

This is common enough after a host reboot that it is worth checking Eureka's count before assuming a
deeper fault.

---

## 6. Cross-cutting services

**campaign-service** (8089) — marketing campaigns + the public *Book a Demo* lead capture on the landing
page. Needs notification-service for the e-mail.

**analytics-service** (8090) — cross-module analytics. Both are org-scoped (a fix — both previously had
**no** `org_id` at all, so any authenticated user could reach any tenant's data). If you restore an old
backup of either, confirm the `org_id` migration ran.

**party-service** (8096) — shared contact/CRM master behind Contact 360. In compose since 2026-08-04 (the
old `java -jar` workaround is gone — COMMON §9). All five module bridges are best-effort, so nothing
breaks without it: records save with `party_id = NULL` and re-link on the next write.

---

## 7. Multi-tenant notes

Every service is org-scoped: `organization_id` + `findScoped` reads + stamped writes + anti-IDOR by-id
lookups. The gateway derives the active org from the JWT's `activeOrgId` claim and stamps `X-Org-Id`.

**Signup provisions a tenant automatically** — plan, trial, entry cap. For manual provisioning:

```
POST /api/auth/admin/provision-tenant
```

**Per-tenant configuration** is on each module's Configuration screen, backed by `org_setting`. Defaults
are code-defined; only overrides are stored, so a fresh tenant needs no setup rows.

---

## 8. Backup — all databases

```bash
docker compose exec mysql sh -c \
  'exec mysqldump -uroot -p"$MYSQL_ROOT_PASSWORD" --all-databases' > full-$(date +%F).sql
```

With this many interlinked databases, back them up **together**. A per-database backup taken at different
moments restores into a state that never existed — an order referencing a catalog product that the
catalog dump predates.

---

## 9. When the host is smaller than the stack

The full stack wants ≥ 16 GB. Below that, **choose a smaller profile rather than squeezing this one** —
an under-provisioned full stack does not fail cleanly, it degrades into intermittent Eureka dropouts and
timeouts that read like network faults and cost hours to diagnose.

| Host RAM | Run | Command |
|---|---|---|
| ≥ 16 GB | Everything (22) | `docker compose --profile full up -d --build` |
| 12–16 GB | POS + one vertical | bare `up`, then name the vertical: `docker compose up -d --build education-service` |
| ~10 GB | POS + pharmacy (15) | `docker compose --profile pharmacy up -d --build` |
| 8 GB | POS subset (14), ~9.5 GB | `docker compose up -d --build` + 4 GB swap (COMMON) |
| < 8 GB | Nothing here fits | Upgrade, or move MySQL to a managed DB to free ~1.5 GB |

Naming a profiled service explicitly activates its profile, so the 12–16 GB row needs no `--profile`.

**Two things to do on any host under 16 GB:**

1. **Add 4 GB swap** — it does not buy you RAM, but it stops the OOM killer turning a slow start into a
   dead container.
2. **Do not build on the box.** The Maven reactor wants 2 GB+ on its own. Build locally or in CI, push
   images to a registry, and have the VPS pull them. On a small host this is the difference between a
   deploy that works and one that OOMs halfway through `docker compose build`.

**Disk, not just RAM:** 22 images at ~200 MB plus layers is ~15 GB before any data. Container logs are
capped at 3 × 10 MB each by the `x-logging` anchor (~0.7 GB total) — if `docker inspect` shows no
`max-size` on a container, you are on an old compose file and logs will fill the disk, which presents as
MySQL write errors and JVM failures rather than as a disk problem.
