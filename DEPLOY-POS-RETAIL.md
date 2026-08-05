# Deploy POS / Retail (Docker) — Local → Hostinger VPS

End-to-end runbook to build and run the **POS / Retail** slice of MyPlus with Docker, first
locally, then on the Hostinger VPS `187.127.125.91`.

POS/Retail does **not** need every microservice. This runbook's **primary path runs only the
subset** the retail POS depends on; the other domain services (education, welfare, agriculture,
pharma, marketplace, analytics, appointment) are left out of the `up` command. Every module is
Docker-packaged, though, so you can bring up the **entire platform** with a single `docker compose
--profile full up -d --build` — see §9 *Deploy the full stack*.

> **Running a pharmacy?** A pharmacy is this POS stack plus one service. Use `docker compose --profile
> pharmacy up -d --build` (11.5 GB ceiling) — **not** `--profile full`, which adds seven unrelated verticals and
> ~16 GB. Everything in this runbook applies unchanged; the clinical layer is in
> [`docs/deploy/DEPLOY-PHARMACY.md`](docs/deploy/DEPLOY-PHARMACY.md), including the mandatory
> clinical-flag backfill (§9 here).

> **Signup e-mail:** account verification and password-reset e-mails are sent by
> **notification-service**. It is included in the POS subset below — without it, new users never
> receive the verification link and therefore can never log in.

---

## 1. What gets deployed

| Component | Container | Host port | Role |
|-----------|-----------|-----------|------|
| MySQL 8 | `myplus-mysql` | 3306 | Per-service DBs (`myplusdb` = business-service, `myplusdb_auth`, `myplusdb_catalog`, `myplusdb_inventory`, `myplusdb_finance`, `myplusdb_audit`, `myplusdb_party`). The monolith owns **no** database (P5). |
| Redis | `myplus-redis` | – (internal) | Demo-quota / rate-limit counter |
| Eureka | `myplus-eureka` | 8761 | Service discovery |
| Config server | `myplus-config` | 8888 | Centralised config |
| API gateway | `myplus-gateway` | 8765 | Edge — JWT auth, header stamping, routing |
| Auth service | `myplus-auth` | – (internal) | Login / JWT / tenants / signup |
| Notification service | `myplus-notification` | – (internal) | Sends verification + password-reset e-mail |
| Catalog service | `myplus-catalog` | – (internal) | Product master |
| Inventory service | `myplus-inventory` | – (internal) | Stock levels + reserve/confirm saga |
| Business service | `myplus-business` | – (internal) | POS: customers, sales, purchases, reports |
| Finance service | `myplus-finance` | – (internal) | Customer/vendor **payments + receipts + ledger** |
| Monolith (UI) | `myplus-monolith` | **8080** | Thymeleaf POS dashboard — the app you open |

Only edge components publish host ports; the rest are reachable only inside the private
`myplus-net` Docker network (via the gateway). **The app is served on port 8080.**

> **finance-service** owns the customer-payment side of POS: when a customer pays against their
> outstanding dues, business-service settles the invoices locally **and** records the receipt in
> finance-service (which issues the receipt number and the shared ledger entry). The call is
> best-effort/null-guarded, so *selling* still works if finance is down — but *customer payments
> won't get a receipt number or ledger record*. It is therefore **part of the POS stack** and is
> included below.

> **audit-service** (port 8095, DB `myplusdb_audit`) is the standalone append-only audit trail.
> business-service emits money/stock events to it via a transactional outbox (delivery is
> retried, so *selling still works if audit is down* — events just queue and deliver later). It
> also backs the dashboard **Audit Log** view, so it is included in the POS subset.

> **party-service** (port 8096, DB `myplusdb_party`) is the shared contact/CRM master. business-service
> best-effort links each customer/vendor to a shared `partyId` on write (off the domain transaction,
> short timeout + circuit breaker), so *selling and registering still work if party is down* — the
> record just saves with a null `party_id` and re-links on the next write. It backs the owner
> **Contact 360** view. Optional for bare POS, but included in the subset since the bridge is wired.

> **Tax register** (dashboard → *Tax Register*) is a report served by **finance-service** from the
> GL TAX account — output tax (sales) − input tax (purchases) = net payable. Per-org configurable
> on the Tax Settings screen; no extra service.

> **Period close / lock** (dashboard → Finance → *Period Close*, owner/admin) freezes the books
> through a date: back-dated sales, purchases, payments, edits and voids are rejected until reopened.
> **finance-service is the single source of truth** (`period_lock`, Flyway **V4**); business-service
> reads it (short cache) to gate its ops and the GL refuses to post into a locked date. No extra
> service; the lock read tolerates finance being briefly down (fails open, GL is the backstop).

**Approx. RAM for the POS subset:** mysql 1.5 GB + monolith 1 GB + 10 JVMs × 0.75 GB +
notification 0.5 GB ≈ **~11 GB**. Size the VPS accordingly (≥ 8 GB, 12 GB comfortable). To trim,
party-service is the one droppable JVM (best-effort — POS runs without it, `party_id` stays null).
The **full stack** (all 20 services) needs ~16 GB — see §9.

```
Browser ──▶ :8080 monolith (UI) ──▶ :8765 gateway ──▶ auth / catalog / inventory / business
                                        │
              eureka :8761  config :8888 │  all services ──▶ MySQL (per-service DB)
```

---

## 2. Prerequisites (local machine)

- **Docker Desktop** (Compose v2) — `docker compose version`
- **JDK 21** and **Maven 3.9+** — `java -version`, `mvn -version`
  (the Dockerfiles are runtime-only and copy a pre-built `target/*.jar`, so you build the jars with Maven first)
- **Git**

> ### ⚠️ Deploy from a branch that compiles
>
> The Dockerfiles copy a **pre-built jar**. If Maven fails, Docker will happily build an image around a
> **stale** jar and the container starts on last week's code — with no error anywhere to tell you.
>
> **`master` currently carries a Dependabot bump to Spring Boot 4.1.0 that does NOT compile.** The
> deployable line is **Spring Boot 3.5.0**. Check before you build:
>
> ```bash
> git rev-parse --abbrev-ref HEAD
> grep -m1 -A2 "spring-boot-starter-parent" microservices/pom.xml   # expect 3.5.0
> ```
>
> And treat a non-zero `mvn` exit as a hard stop — never proceed to `docker compose build` after a failed
> build. §3.4 verifies the Flyway version at runtime as the backstop for exactly this mistake.

---

## 3. Part 1 — Run locally

All commands are run from the repo root unless noted:
`C:/Users/HP/Shahid/software/myplus`

### 3.1 Set the secrets

Docker Compose auto-loads `microservices/.env`. Make sure these are set (edit `microservices/.env`):

```dotenv
# Strong values — do NOT reuse the examples in production
DB_PASSWORD=<a-strong-db-password>
JWT_SECRET=<output of: openssl rand -base64 48>     # auth-service and gateway MUST share this
INTERNAL_SECRET=                                    # leave empty locally (header-forgery check off)
# --- signup e-mail (notification-service) ---
MAIL_USER=<you@gmail.com>                            # full Gmail address
MAIL_PASSWORD=<gmail app password>                   # empty = no e-mail sent (signup link won't arrive)
# APP_BASE_URL / RESET_PASSWORD_URL: leave unset locally — links default to http://localhost:8765
# (the gateway) / http://localhost:8080, which work on the same machine.
# --- bootstrap seeding: LOCAL ONLY ---
APP_SEED_DEMO=true                                   # seeds the demo/test logins below. NEVER set this on the VPS.
```

> ### ⚠️ Seeded accounts: local only, never in production
>
> With `APP_SEED_DEMO=true`, auth-service creates a set of accounts that **all share one password**
> (`APP_DEMO_PASSWORD`, default `Demo@2025!` — a value committed to this repo):
>
> | Account | Why it matters |
> |---|---|
> | `owner.business@myplus.com` | **ROLE_OWNER + SUPER_PRIVILEGE, no write cap** — full control of its tenant |
> | `owner.education@myplus.com` | Same, for the education vertical |
> | `admin.store@`, `cashier.a@`, `cashier.b@` | Multi-location test team (store admin + two cashiers) |
> | `teacher.a@`, `teacher.b@` | Multi-branch test team |
> | `demo.*@myplus.com` (×10) | Per-module demo logins (50-entry write cap) |
> | `admin@myplus.com` | Seeded by `APP_SEED_ADMIN` |
>
> The compose file now defaults **both flags to `false`**, so a deploy is safe unless you opt in. Do
> **not** put `APP_SEED_DEMO` / `APP_SEED_ADMIN` in the VPS `.env` (§4.3). Real customers are only ever
> created through signup (`/api/auth/register`) — never seeded.

> `DB_PASSWORD` is used for **both** the MySQL `root` account and the app user `shahid` (which the
> mysql container auto-creates on first boot; `init-db.sql` then grants it every per-service DB).
> Services connect as `shahid`; leave `DB_USER=shahid` in `.env`. It must be set **before** the
> `mysql-data` volume is first created (see Troubleshooting if you change it later).
> For signup to work end-to-end, `MAIL_USER` + `MAIL_PASSWORD` must be a working Gmail app-password pair.

### 3.2 Build the jars

The microservices are one Maven reactor. Build the common libraries + the POS services:

```bash
# from repo root
cd microservices
# Fast: only the POS services + their upstream common libs (-am pulls in commerce-contracts, common-*)
mvn -q -DskipTests -pl eureka-server,config-server,api-gateway,auth-service,notification-service,catalog-service,inventory-service,business-service,finance-service,audit-service,party-service -am install
cd ..

# Build the monolith (UI) jar -> target/myplus.jar
mvn -q -DskipTests clean package
```

> First build is slow (downloads dependencies). To build **everything** instead, just run
> `cd microservices && mvn -DskipTests install` — simpler, just slower.

### 3.3 Bring up the POS subset

The POS subset is now a **compose profile**, not a service list you type out:

```bash
cd microservices
docker compose up -d --build
```

That starts exactly 14 services — mysql, redis, eureka-server, config-server, api-gateway, auth-service,
notification-service, catalog-service, inventory-service, business-service, finance-service, audit-service,
party-service, monolith. Compose orders them by dependency (mysql/redis → config/eureka → gateway →
services → monolith). First boot creates the databases automatically (`createDatabaseIfNotExist=true`).

Confirm the set before a deploy if you want to be sure:

```bash
docker compose config --services | sort        # the 14 above
```

> **Why a profile (2026-08-05).** This step used to be a hand-typed list of 13 service names, and it
> drifted: on 2026-08-04 `party-service` was named here and in §4.6 but had **no block in
> `docker-compose.yml`**, so `docker compose up` died with *"no such service: party-service"* — on both the
> local test and the VPS, half-way through a deploy. The eight vertical services now carry
> `profiles: ["full"]` instead, so the subset IS the compose file and cannot disagree with it.

> To run **every** module instead: `docker compose --profile full up -d --build` (see §9).

### 3.4 Verify

```bash
docker compose ps                       # all should be "running"/"healthy"
docker compose logs -f monolith         # watch the UI come up (Ctrl-C to stop tailing)
```

- Eureka dashboard: <http://localhost:8761> — should list AUTH, NOTIFICATION, CATALOG, INVENTORY, BUSINESS, FINANCE, AUDIT, PARTY, GATEWAY

**Confirm the schema migrated.** Every service owns its schema via Flyway and applies it at startup, so a
failed migration is the difference between "container up" and "app actually works". Check the newest
migration landed rather than assuming:

```bash
docker compose logs business-service | grep -i "migrat\|flyway" | tail -5
# expect a line reporting the schema is now at version 36 (V36 = customer credit account)
docker compose exec mysql mysql -uroot -p"$DB_PASSWORD" -N -e \
  "SELECT MAX(version) FROM myplusdb.flyway_schema_history WHERE success=1;"   # -> 36
```

If it reports a lower version, the jar is stale — rebuild it (§3.2) before going further. A container that
starts on an old jar is the single most common way a deploy "succeeds" while behaving like last week's build.
- Gateway health: <http://localhost:8765/actuator/health> → `{"status":"UP"}`
- **POS app: <http://localhost:8080>** — log in with your seeded/demo retail account
- **Signup test:** register a new user → check the inbox for the verification e-mail → click the link
  → then log in. (Locally the link is `http://localhost:8765/api/auth/verify-email?...` and works on
  the same machine; on the VPS it's `https://<your-domain>/...` via nginx — see §4.8.)

Give the JVMs ~60–90 s on first start. If the monolith 502s briefly, the gateway/services are
still registering — wait and refresh.

### 3.5 Smoke-test the POS flow

1. Log in → Business dashboard.
2. **Register → Product**: create a product (name, price).
3. **Product row → Add stock**: add opening stock (feeds inventory).
4. **Sale → New Sale**: pick the product, qty, take payment → confirm the invoice number appears.
5. **Sale → Sale Detail Report**: the sale shows with totals/KPIs.

**Multi-location (only if the tenant has more than one store).** A single-store business needs none of
this — it is the degenerate case and behaves exactly as above.

6. **Settings → Stores**: create a store. The owner is auto-granted it (zero-touch), so it becomes their
   active store and new sales are stamped with it.
7. **Team → Manage Users**: add a cashier/admin and assign them store(s) via the chip picker. An **admin**
   may add USERs in their own stores; only an **owner** may create another admin (server-enforced).
   *Existing* members are reassigned with **Edit access** on their row — the picker is their complete set,
   so removing a chip revokes that store.
8. **Sidebar switchers**: with 2+ orgs an **organization** switcher appears; with 2+ stores an **active
   store** switcher appears. Both re-issue the session token — the active store is what new sales,
   purchases, shifts and tenders get stamped with.

### 3.6 Stop / clean up

```bash
docker compose stop                 # stop containers, keep data
docker compose down                 # remove containers+network, keep the mysql-data volume
docker compose down -v              # ALSO delete the database volume (fresh start)
```

---

## 4. Part 2 — Deploy to Hostinger VPS (`187.127.125.91`)

Two ways to get images onto the VPS:

- **A. Build on the VPS** (this runbook's primary path) — clone the repo, build jars + images on the
  box. Simplest single-machine flow; needs JDK/Maven + enough RAM (add swap).
- **B. Registry** (recommended once it's stable) — build+push images from CI/your laptop to a registry
  (Docker Hub / GHCR), then `docker compose pull` on the VPS. No build tools/RAM needed on the VPS.
  See §4.9.

### 4.1 First login + base setup

```bash
ssh root@187.127.125.91

# Update + basics
apt update && apt -y upgrade
apt -y install git ufw

# Add swap (protects small VMs during the Java build) — skip if you have ≥16 GB RAM
fallocate -l 4G /swapfile && chmod 600 /swapfile && mkswap /swapfile && swapon /swapfile
echo '/swapfile none swap sw 0 0' >> /etc/fstab
```

### 4.2 Install Docker Engine + Compose plugin

```bash
curl -fsSL https://get.docker.com | sh
docker version && docker compose version     # verify
```

### 4.3 Install JDK 21 + Maven (only for build-on-VPS / path A)

```bash
apt -y install maven openjdk-21-jdk 2>/dev/null || {
  # Fallback if the distro has no JDK 21 package — use Temurin:
  apt -y install wget apt-transport-https gnupg
  wget -qO- https://packages.adoptium.net/artifactory/api/gpg/key/public | gpg --dearmor | tee /etc/apt/keyrings/adoptium.gpg >/dev/null
  echo "deb [signed-by=/etc/apt/keyrings/adoptium.gpg] https://packages.adoptium.net/artifactory/deb $(. /etc/os-release; echo $VERSION_CODENAME) main" > /etc/apt/sources.list.d/adoptium.list
  apt update && apt -y install temurin-21-jdk maven
}
java -version
```

### 4.4 Get the code

```bash
cd /opt
git clone <YOUR_REPO_URL> myplus        # or: rsync -av from your laptop
cd myplus
git checkout feature/finance-ledger     # the POS branch you're deploying
```

### 4.5 Production secrets

Edit `microservices/.env` on the VPS with **strong, production** values:

```dotenv
DB_PASSWORD=<long-random-db-password>
JWT_SECRET=<openssl rand -base64 48>
INTERNAL_SECRET=<openssl rand -base64 32>     # IMPORTANT: set this in prod (turns on header-forgery protection)
# --- signup e-mail (notification-service) ---
MAIL_USER=<you@gmail.com>                       # full Gmail address
MAIL_PASSWORD=<gmail app password>              # empty = verification e-mail never sent
# --- public URLs baked into the e-mailed links (MUST be your real domain, browser-reachable) ---
APP_BASE_URL=https://maxtheservice.com          # nginx proxies /api/ here -> gateway -> auth-service (§4.8)
RESET_PASSWORD_URL=https://maxtheservice.com/user/changePassword
RECAPTCHA_SECRET=<your key or empty>
# --- observability (only if you run the LGTM overlay; Grafana is localhost-bound but set it anyway) ---
GRAFANA_PASSWORD=<strong-random-password>       # REQUIRED for observability; UNSET => Grafana admin/admin
```

> Never commit real secrets. `.env` is git-ignored.
> `APP_BASE_URL` is what makes the verification link clickable from a customer's phone/PC. If it is
> left at the localhost default, the link is dead — this is the #1 cause of "I can't sign up / verify".
>
> **Do NOT add `APP_SEED_DEMO` or `APP_SEED_ADMIN` to the VPS `.env`.** Compose defaults both to
> `false`; setting either to `true` seeds accounts with a password that is public in this repo —
> including `owner.business@myplus.com`, which holds **ROLE_OWNER + SUPER_PRIVILEGE with no write
> cap**. See the warning in §3.1, and prove it after the first boot with the check at the end of §4.6.

### 4.6 Build + run the POS subset

```bash
cd /opt/myplus/microservices
mvn -q -DskipTests -pl eureka-server,config-server,api-gateway,auth-service,notification-service,catalog-service,inventory-service,business-service,finance-service,audit-service,party-service -am install
cd /opt/myplus && mvn -q -DskipTests clean package        # monolith jar
cd /opt/myplus/microservices

docker compose up -d --build        # the POS subset — see the profile note in §3.3

docker compose ps
```

> Or run **all modules**: `docker compose --profile full up -d --build` (§9).

> **Do not add `--remove-orphans` to a bare `up` on a host that runs the full stack** — the vertical
> services are behind the `full` profile, so compose treats them as orphans and removes them.

First open **http://187.127.125.91:8080** (open port 8080 temporarily — see firewall below) to
confirm it runs, then put it behind nginx + TLS (§4.8).

#### ✅ Prove there is no known-password account (run on EVERY deploy)

A checklist item nobody executes is worth nothing — so prove it, don't assume it:

```bash
# 1. None of the seeded accounts should exist at all.
#    Prefixes, NOT specific names: the seed set is a full ladder per module (demo./user./admin./owner.<module>@)
#    plus the named team fixtures, so matching exact addresses would miss every account added later.
docker compose exec mysql mysql -uroot -p"$DB_PASSWORD" -N -e \
  "select email from myplusdb_auth.users
     where email like 'demo.%' or email like 'owner.%' or email like 'admin.%'
        or email like 'user.%' or email like 'cashier.%' or email like 'teacher.%'
        or email = 'admin@myplus.com';"
# Expect: NO ROWS. Any row here is a live account whose password is published in this repo.

# 2. The one that matters most must not be able to log in.
curl -s -o /dev/null -w '%{http_code}\n' -X POST http://localhost:8765/api/auth/login \
  -H 'Content-Type: application/json' \
  -d '{"email":"owner.business@myplus.com","password":"Demo@2025!"}'
# Expect: 400/401 — never 200.
```

If rows come back you deployed with seeding on. Set `APP_SEED_DEMO=false` / `APP_SEED_ADMIN=false` /
`APP_SEED_TEST_FIXTURES=false` (or simply remove them), restart auth-service, **and then delete the seeded
rows**: turning the flag off stops them being *re-created*, it does not remove what has already been written.

> #### ⚠️ Known gap — the microservices do not run the `prod` profile
>
> `SPRING_PROFILES_ACTIVE: prod` is set on the **monolith container only**. Every microservice, `auth-service`
> included, starts on the `default` profile and therefore requests `auth-service/default` from config-server —
> so **`application-prod.yml` is never applied to them**. Its `seed-admin: false`, the mandatory `JWT_SECRET`,
> and the mandatory `INTERNAL_SECRET` are all inert for the services today.
>
> What actually protects production right now is (a) the `:-false` seeding defaults in this compose file and
> (b) auth-service refusing to seed any account whose password was not explicitly supplied. Both hold, but they
> are the *only* layers — the profile-based ones are not in play.
>
> **Recommended fix:** add `SPRING_PROFILES_ACTIVE: prod` to every service in `docker-compose.yml`. Do it
> deliberately, not casually: it also activates the rest of `application-prod.yml`, which makes `JWT_SECRET` and
> `INTERNAL_SECRET` mandatory with no defaults, so any service missing them will fail to start. Set those in
> `.env` first (§4.5 already lists them), then roll the profile out and verify each service comes up.

### 4.7 Firewall — lock down the internal ports

The compose file publishes 8761/8888/8765/3306 to the host. On a public VPS these must **not** be
world-reachable. Allow only SSH + web:

```bash
ufw allow 22/tcp
ufw allow 80/tcp
ufw allow 443/tcp
ufw allow 8080/tcp           # TEMPORARY, for the first smoke test; remove after nginx is up
ufw enable
```

`ufw` blocks 3306/8761/8765/8888 from the internet while Docker's internal network still works.
(Hardening tip: after testing, `ufw delete allow 8080/tcp` and reach the app only through nginx.
For belt-and-braces, bind those ports to localhost in compose — see §5.)

### 4.8 Reverse proxy + HTTPS (nginx + certbot)

Point your domain's **A record** at `187.127.125.91` (Hostinger DNS), then:

```bash
apt -y install nginx certbot python3-certbot-nginx

cat >/etc/nginx/sites-available/myplus <<'NGINX'
server {
    server_name maxtheservice.com www.maxtheservice.com;   # <-- your domain
    client_max_body_size 20m;

    # API gateway — REQUIRED so the e-mailed verification link
    # (https://<domain>/api/auth/verify-email?token=...) reaches auth-service. The gateway is
    # firewalled off the public internet (§4.7); this is its only public entry point.
    location /api/ {
        proxy_pass http://127.0.0.1:8765;
        proxy_set_header Host $host;
        proxy_set_header X-Real-IP $remote_addr;
        proxy_set_header X-Forwarded-For $proxy_add_x_forwarded_for;
        proxy_set_header X-Forwarded-Proto $scheme;
    }

    # Monolith UI (everything else)
    location / {
        proxy_pass http://127.0.0.1:8080;
        proxy_set_header Host $host;
        proxy_set_header X-Real-IP $remote_addr;
        proxy_set_header X-Forwarded-For $proxy_add_x_forwarded_for;
        proxy_set_header X-Forwarded-Proto $scheme;
    }
}
NGINX

ln -s /etc/nginx/sites-available/myplus /etc/nginx/sites-enabled/myplus
nginx -t && systemctl reload nginx
certbot --nginx -d maxtheservice.com -d www.maxtheservice.com     # issues + wires TLS, auto-renews
```

Now the POS is reachable at **https://maxtheservice.com** (proxied to the monolith on 8080).
Remove the temporary `ufw allow 8080/tcp` afterwards.

### 4.9 Alternative: registry-based deploy (repeatable, no build on VPS)

Once stable, build once and pull on the VPS instead of compiling there:

1. Locally: `mvn ... install` + `docker compose build`, then `docker tag` / `docker push` each image
   to Docker Hub or GHCR.
2. On the VPS: a compose file with `image:` (instead of `build:`) lines → `docker compose pull &&
   docker compose up -d`. This is the basis for CI/CD (GitHub Actions) later.

---

## 5. Security hardening checklist (before real traffic)

- [ ] **No seeded accounts.** `APP_SEED_DEMO` / `APP_SEED_ADMIN` absent (or `false`) in the VPS `.env`
      — compose defaults them off. Then *prove* it with the two checks at the end of §4.6: seeding on
      creates `owner.business@myplus.com` with **ROLE_OWNER + SUPER_PRIVILEGE, no write cap**, and a
      password that is committed to this repo. Turning the flag off later does **not** delete rows that
      were already seeded — delete them.
- [ ] `INTERNAL_SECRET` set to a strong value (gateway↔service trust; empty = protection off).
- [ ] Strong unique `DB_PASSWORD` and `JWT_SECRET` (never the `.env.example` samples).
- [ ] MySQL **not** exposed publicly — `ufw` blocks 3306, or remove `ports: ["3306:3306"]` from compose
      (services reach it on the internal network regardless). Same for 8761/8765/8888.
      Optional: bind to localhost only, e.g. `ports: ["127.0.0.1:8765:8765"]`.
- [ ] HTTPS via certbot; HTTP→HTTPS redirect (certbot adds it).
- [ ] `DDL_AUTO=validate` once Flyway baselines exist (first prod boot may use `update`).
- [ ] Regular `mysql-data` volume backups (§6).
- [ ] Rotate the Gmail app password / reCAPTCHA keys out of any committed history.
- [ ] **Observability (if running the LGTM overlay):** set a strong `GRAFANA_PASSWORD` (unset ⇒
      `admin`/`admin`). Grafana is bound to `127.0.0.1:3000` only — reach it via SSH tunnel
      (`ssh -L 3000:localhost:3000 root@<vps>`); do **not** add a `ufw allow 3000` rule, and if you
      front it with nginx add HTTP basic-auth + TLS. Loki/Tempo/Prometheus/Collector have **no** host
      ports (private `myplus-net`).
- [ ] Treat telemetry as sensitive: app logs/traces may carry PII/tenant data — they live only in the
      internal Loki/Tempo/Prometheus with 7d/3d/15d retention. Ensure the app never logs secrets/JWTs,
      and keep the observability images updated for CVEs.

---

## 6. Operations

```bash
cd /opt/myplus/microservices

# Logs
docker compose logs -f business-service          # one service
docker compose logs --tail=200 monolith

# Restart one service (e.g. after a rebuild)
docker compose up -d --build business-service

# Update to latest code
git pull
mvn -q -DskipTests -pl business-service -am install     # rebuild changed module(s)
docker compose up -d --build business-service monolith  # recreate affected images

# Backup the databases (volume-backed)
docker exec myplus-mysql sh -c 'exec mysqldump -uroot -p"$MYSQL_ROOT_PASSWORD" --all-databases' > /opt/backups/myplus-$(date +%F).sql

# Restore
cat /opt/backups/myplus-YYYY-MM-DD.sql | docker exec -i myplus-mysql sh -c 'exec mysql -uroot -p"$MYSQL_ROOT_PASSWORD"'
```

Containers use `restart: unless-stopped`, so they survive reboots. The MySQL data lives in the
`mysql-data` named volume and persists across `docker compose down` (but not `down -v`).

### 6.1 Log management (VPS disk / memory saving)

Logging is kept deliberately **lightweight** — no aggregation stack, near-zero extra RAM:

- **Rotation is capped in-compose.** Every container inherits the shared `x-logging: &default-logging`
  anchor (`json-file`, `max-size: 10m`, `max-file: 3`) → **≤ 30 MB per container**, ~0.6 GB across the
  whole stack. Docker's default driver never rotates, so without this container logs grow unbounded and
  fill the VPS disk — which then looks exactly like the box running out of memory (MySQL can't write,
  JVMs fail). Recreating a container (`docker compose up -d`) also **discards its old log file**, so a
  redeploy instantly reclaims any pre-cap bloat.
- **Logs are quiet by default.** In prod (`SPRING_PROFILES_ACTIVE=prod`) `root` is `WARN` and SQL echo
  is off; only `com.myplus` (app) and `org.flywaydb` (migrations) stay at `INFO`. Less volume, less CPU/GC.

```bash
# View logs (rotation-capped)
docker compose logs -f business-service,docker compose logs -f myplus-gateway
docker compose logs --tail=200 monolith

# Confirm the cap is applied to a container
docker inspect --format '{{.Name}} {{.HostConfig.LogConfig.Config}}' myplus-business

# Temporarily raise verbosity for one service (no rebuild) — e.g. see SQL or DEBUG a package
docker compose stop business-service
JPA_SHOW_SQL=true LOGGING_LEVEL_COM_MYPLUS=DEBUG docker compose up -d business-service
# ...then revert by restarting it plainly: docker compose up -d --force-recreate business-service
```

**Optional host-level safety net** (applies the same cap to *any* container, incl. ones started outside
this compose file — not repo-tracked, so the compose anchor above remains the reproducible source):

```bash
cat >/etc/docker/daemon.json <<'JSON'
{ "log-driver": "json-file", "log-opts": { "max-size": "10m", "max-file": "3" } }
JSON
systemctl restart docker
```

---

## 7. Troubleshooting

| Symptom | Likely cause / fix |
|--------|--------------------|
| `docker compose build` fails on a service | Its jar isn't built. Re-run the Maven `install`/`package` step; Dockerfiles copy `target/*.jar`. |
| Monolith 502 / login loops right after start | Services still registering with Eureka. Wait 60–90 s; check `docker compose logs -f api-gateway`. |
| Service can't reach MySQL on boot | MySQL still initialising. It has a healthcheck + `depends_on: service_healthy`; on a slow box give it longer, or `docker compose restart <svc>`. |
| "Access denied" to MySQL | `DB_PASSWORD` unset/mismatched. It must be set **before** the mysql volume is first created; if you changed it later, `docker compose down -v` to recreate the DB (destroys data). |
| Sale fails "Not enough sellable stock" | Add stock via Product → Add stock; only non-expired batches are sellable. |
| Add vendor/customer/item/sale fails: "could not read a hi value - you need to populate the table: `*_seq`" | Hibernate sequence tables weren't seeded. Fixed by business-service Flyway `V11__seed_sequence_tables.sql` — rebuild + restart business-service so it applies. (Only business-service uses these; other services use auto-increment.) |
| Signup succeeds but **no verification e-mail** | `notification-service` not running (must be in the `up` list) **or** `MAIL_USER`/`MAIL_PASSWORD` wrong. Check `docker compose logs notification-service`; the Gmail value must be an **app password**. |
| Verification link opens but **doesn't verify / 404** | `APP_BASE_URL` still at the localhost default (link is dead off-box), or nginx has no `location /api/` block (§4.8) routing to the gateway. |
| New account can't log in — "Account not verified" | Expected until the e-mailed link is clicked. Fix e-mail delivery (rows above); the account is enabled only after verification. |
| Identity-header errors between services | `JWT_SECRET` must be identical for auth-service and gateway; if `INTERNAL_SECRET` is set, all services must share the same value. |
| Out-of-memory / build killed on VPS | Add swap (§4.1) or build with the registry approach (§4.9); the POS subset ceiling is 10.75 GB, pharmacy 11.5 GB, the full stack 16.75 GB (measured 2026-08-05). On 8 GB only the POS subset fits, with swap. |
| VPS slowly fills disk / gets unresponsive over days; MySQL write errors | Unrotated container logs. Fixed by the `x-logging` rotation anchor (§6.1) — if a container shows no `max-size` in `docker inspect`, you're on an old compose: `git pull` + `docker compose up -d` to recreate (also clears the accumulated log files). |

---

## 8. Quick reference — the one command

After secrets are set and jars are built:

```bash
cd microservices && docker compose up -d --build      # POS subset (14 services, via the default profile)
# open http://localhost:8080  (local)  /  https://<your-domain>  (VPS)
```

---

## 9. Deploy the full stack (all modules)

Every module ships a Dockerfile (Java 21 runtime) and is wired into `microservices/docker-compose.yml`,
so the whole platform — POS **plus** education, welfare, agriculture, pharma, marketplace, campaign,
analytics, appointment — comes up with the `full` profile:

```bash
cd microservices
# 1. Build every module (one reactor). Slower first time; -DskipTests keeps it moving.
mvn -q -DskipTests install
cd .. && mvn -q -DskipTests clean package        # monolith jar
cd microservices

# 2. Bring up EVERYTHING. The `full` profile adds the eight vertical services on top of the
#    POS subset that a bare `up` starts. Omitting it is now the SUBSET, not everything.
docker compose --profile full up -d --build
docker compose ps
```

#### ⚠️ Pharmacy only — run the clinical-flag backfill once, right after first start

Skip this unless you deploy **pharma-service**. The "prescription required" / "controlled substance" flags now
live on the **catalog product**, because the sell guard reads them off the `ProductRef` it already fetches per
line (no extra call at checkout — see `microservices/docs/pharmacy-rx-enforcement-design.md`). Flags set before
this cutover still sit in `myplusdb_pharma.medicine_clinical`, and the two tables are in **different databases**,
so no Flyway script can copy them across.

**Until this runs, a prescription-only medicine sells like any other product.** It is part of the deploy, not a
follow-up. Idempotent — safe to re-run, and a no-op on a fresh install with no flags.

```bash
# Owner/admin token, then call until "remaining" is 0 (batches of 200, cursor via lastId)
curl -s -X POST "https://<your-domain>/api/pharma/clinical-flags/backfill?limit=200" \
     -H "Authorization: Bearer $TOKEN"
# -> {"scanned":200,"pushed":198,"failed":0,"malformed":0,"orphaned":2,
#     "orphanedProductIds":[...],"lastId":200,"remaining":57}
curl -s -X POST "https://<your-domain>/api/pharma/clinical-flags/backfill?limit=200&afterId=200" \
     -H "Authorization: Bearer $TOKEN"
```

Reading the result:
- `pushed` — flags now enforced at the till.
- `failed` — catalog rejected the write (down / transient). **Re-run**; it will retry those.
- `orphaned` — the row's `product_id` matches no catalog product, so the flag can never be enforced. Re-running
  will **not** fix it: the data needs repointing or deleting. Expect a few on databases that predate the
  productId rebase, whose rows still hold old business item ids (`V3__pharma_itemid_to_productid.sql` renamed
  the column without translating the values). `orphanedProductIds` lists them.
- `malformed` — row has no `product_id` at all.

Verify with a real sale: flag a medicine on **Clinical & Safety**, then try to sell it without a prescription —
it must be refused naming the product.

Notes:
- **RAM:** the full stack is ~16 GB. On an 8–12 GB VPS, stick to the POS subset (§3.3/§4.6) or add
  swap (§4.1) and expect slower boots.
- **Secrets:** same `.env`. Domain services that send mail (education, campaign) reuse
  `MAIL_USERNAME`/`MAIL_PASSWORD`; notification-service uses `MAIL_USER`/`MAIL_PASSWORD`.
- **Selective add-ons:** to add just one domain to a running POS stack, name it — e.g.
  `docker compose up -d --build education-service` (Compose starts its deps as needed). Naming a
  profiled service explicitly activates its profile, so this works without `--profile`.
- **Pharmacy:** prefer the dedicated profile — `docker compose --profile pharmacy up -d --build` brings up
  the POS subset **and** pharma-service in one command (11.5 GB ceiling), instead of the full stack's 16.75 GB.
  Then run the clinical-flag backfill above. See `docs/deploy/DEPLOY-PHARMACY.md`.
