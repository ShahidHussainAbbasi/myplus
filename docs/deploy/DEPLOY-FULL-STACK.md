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
`myplusdb_analytics`) · party-service (8096, `myplusdb_party` — **not in compose**, COMMON §9)

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

**RAM:** mysql 1.5 + monolith 1 + ~19 JVMs × 0.75 ≈ **~16.5 GB**. Size **≥ 16 GB**, 24 GB comfortable.
Below 16 GB the JVMs will thrash and services drop out of Eureka intermittently — which looks like a
networking fault and is not.

---

## 2. Build everything

```powershell
# from repo root — one reactor, slow the first time
mvn -q -DskipTests clean package -f microservices/pom.xml
mvn -q -DskipTests clean package        # monolith UI → target/myplus.jar
```

## 3. Run everything

```powershell
cd microservices
docker compose up -d --build          # omitting the service list = every service in the file
```

`party-service` is not in the compose file — see COMMON §9 to run it alongside.

### Verify

```powershell
docker compose ps                                  # every service healthy
curl http://localhost:8761                         # Eureka — count the registrations
curl http://localhost:8765/actuator/health
curl -I http://localhost:8080/login
```

Eureka is the real check here: with this many services, one silently failing to register is the common
failure, and it presents later as a confusing 500 from whichever module needed it.

---

## 4. Per-module smoke tests

Run each module's §"Smoke test" section — they are independent and can be done in any order:

[Education](DEPLOY-EDUCATION.md) · [Pharmacy](DEPLOY-PHARMACY.md) ·
[Marketplace](DEPLOY-MARKETPLACE.md) · [Welfare](DEPLOY-WELFARE.md) ·
[Agriculture](DEPLOY-AGRICULTURE.md) · [Appointments](DEPLOY-APPOINTMENT.md) ·
[POS/Retail](../../DEPLOY-POS-RETAIL.md)

A user's `userType` decides which dashboard they land on, so you need one account per vertical to test
them all. With `APP_SEED_DEMO=true` **locally**, the seeded ladder gives you those accounts.

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

**party-service** (8096) — shared contact/CRM master behind Contact 360. All five module bridges are
best-effort, so nothing breaks without it. **Not in compose** — COMMON §9.

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
