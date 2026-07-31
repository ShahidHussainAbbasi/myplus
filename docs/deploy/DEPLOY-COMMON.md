# Deployment — the common core (every module)

Everything identical across modules lives here: prerequisites, secrets, VPS build-out, TLS, firewall,
operations, troubleshooting. **Each module runbook covers only what differs** — its services, DBs, RAM,
compose subset and smoke test — and links back here.

It is written this way on purpose. Copying the nginx/TLS/firewall sections into seven runbooks means the
next change has to be made seven times, and six of them will be missed.

| You want to deploy | Read |
|---|---|
| POS / Retail | [`../../DEPLOY-POS-RETAIL.md`](../../DEPLOY-POS-RETAIL.md) (the original, most detailed) |
| Pharmacy | [`DEPLOY-PHARMACY.md`](DEPLOY-PHARMACY.md) |
| Education / School | [`DEPLOY-EDUCATION.md`](DEPLOY-EDUCATION.md) |
| E-commerce / Marketplace | [`DEPLOY-MARKETPLACE.md`](DEPLOY-MARKETPLACE.md) |
| Welfare / Donations | [`DEPLOY-WELFARE.md`](DEPLOY-WELFARE.md) |
| Agriculture | [`DEPLOY-AGRICULTURE.md`](DEPLOY-AGRICULTURE.md) |
| Appointments | [`DEPLOY-APPOINTMENT.md`](DEPLOY-APPOINTMENT.md) |
| Everything | [`DEPLOY-FULL-STACK.md`](DEPLOY-FULL-STACK.md) |

---

## 1. The platform every module needs

No module runs without these eight. Module runbooks list only what they add.

| Component | Compose service | Host port | Role |
|---|---|---|---|
| MySQL 8 | `mysql` | 3306 | One DB per service |
| Redis | `redis` | – | Demo-quota / rate-limit counters |
| Eureka | `eureka-server` | 8761 | Service discovery |
| Config server | `config-server` | 8888 | Centralised config |
| API gateway | `api-gateway` | 8765 | Edge — JWT auth, `X-Org-Id` stamping, routing |
| Auth service | `auth-service` | 8081 | Login / JWT / tenants / signup |
| Notification service | `notification-service` | 8093 | Verification + password-reset e-mail |
| Monolith (UI) | `monolith` | **8080** | Thymeleaf dashboards — the app you open |

Only the monolith (8080) and gateway (8765) need to be reachable. Everything else talks over the private
`myplus-net` Docker network.

```
Browser ──▶ :8080 monolith (UI) ──▶ :8765 gateway ──▶ <module services>
                                        │
              eureka :8761  config :8888 │  every service ──▶ MySQL (own DB)
```

**Baseline RAM:** mysql 1.5 GB + monolith 1 GB + 6 JVMs × 0.75 GB ≈ **~7 GB**. Each module service adds
~0.75 GB. Module runbooks give the total.

---

## 2. Prerequisites (local machine)

- **Docker Desktop** (Compose v2) — `docker compose version`
- **JDK 21** + **Maven 3.9+** — the Dockerfiles are runtime-only and copy a pre-built `target/*.jar`,
  so you build with Maven first
- **Git**

---

## 3. Secrets

Create `microservices/.env` (git-ignored). Same file for every module.

```bash
# ── database ──────────────────────────────────────────────────────────────
DB_USER=myplus
DB_PASSWORD=<strong-value>
MYSQL_ROOT_PASSWORD=<strong-value>

# ── JWT signing ───────────────────────────────────────────────────────────
JWT_SECRET=<64+ random chars>

# ── signup e-mail (notification-service) ──────────────────────────────────
MAIL_USERNAME=<gmail address>
MAIL_PASSWORD=<gmail app password>

# ── public URLs baked into e-mailed links (PROD: your real domain) ────────
# Leave unset locally — links default to http://localhost:8765 / :8080, which work on one machine.
APP_BASE_URL=https://your-domain
RESET_PASSWORD_URL=https://your-domain/user/changePassword

# ── seeding: LOCAL ONLY. Never set on a public host. ──────────────────────
APP_SEED_DEMO=true
```

> ### ⚠ `APP_SEED_DEMO` must be `false` (or unset) in production
> It seeds the full demo/user/admin/owner account ladder **with published passwords**. §6 has the
> verification query — run it before exposing anything.

---

## 4. Run locally

From the repo root:

```powershell
# 1. Build. -am pulls in the shared libs (commerce-contracts, common-*).
mvn -q -pl microservices/<services…> -am -DskipTests clean package -f microservices/pom.xml

# 2. Build the monolith (UI) jar
mvn -q -DskipTests clean package        # → target/myplus.jar

# 3. Bring up your module's subset (each runbook gives the exact list)
cd microservices
docker compose up -d --build mysql redis eureka-server config-server api-gateway auth-service notification-service <module services> monolith
```

### Verify

```powershell
docker compose ps                                   # all healthy
curl http://localhost:8761                          # eureka — every service registered
curl http://localhost:8765/actuator/health          # gateway UP
curl -I http://localhost:8080/login                 # monolith 200
```

### Stop / clean

```powershell
docker compose down            # keep data
docker compose down -v         # ALSO drop the MySQL volume (destroys all data)
```

> **The stale-jar trap.** `docker compose up --build` copies `target/*.jar`. Running `mvn compile`
> refreshes `target/classes` but **not** the jar — so the container runs old code while the source looks
> right. Always `package`, never `compile`, before a rebuild. If behaviour contradicts the source, check
> the jar's timestamp first.

---

## 5. Deploy to a VPS

Reference host: Hostinger `187.127.125.91`. Substitute your own.

### 5.1 Base setup

```bash
ssh root@<host>
apt update && apt -y upgrade
apt -y install git curl ufw

# Swap protects small VMs during the Java build — skip if ≥16 GB RAM
fallocate -l 4G /swapfile && chmod 600 /swapfile && mkswap /swapfile && swapon /swapfile
echo '/swapfile none swap sw 0 0' >> /etc/fstab
```

### 5.2 Docker

```bash
curl -fsSL https://get.docker.com | sh
docker compose version
```

### 5.3 JDK + Maven (only if building on the VPS)

```bash
apt -y install openjdk-21-jdk maven
java -version && mvn -version
```

### 5.4 Code + secrets

```bash
git clone <your-repo> /opt/myplus && cd /opt/myplus
nano microservices/.env          # §3 — with APP_SEED_DEMO unset
```

### 5.5 Build + run

Same two commands as §4, then bring up your module's subset.

### 5.6 Firewall

```bash
ufw allow OpenSSH
ufw allow 80/tcp
ufw allow 443/tcp
ufw --force enable
```

Nothing else. 8080/8765/3306/8761/8888 must **not** be publicly reachable — nginx fronts the app.

### 5.7 nginx + HTTPS

```bash
apt -y install nginx certbot python3-certbot-nginx

cat >/etc/nginx/sites-available/myplus <<'NGINX'
server {
    listen 80;
    server_name your-domain www.your-domain;
    client_max_body_size 20m;

    location / {
        proxy_pass http://127.0.0.1:8080;
        proxy_http_version 1.1;
        proxy_set_header Host              $host;
        proxy_set_header X-Real-IP         $remote_addr;
        proxy_set_header X-Forwarded-For   $proxy_add_x_forwarded_for;
        proxy_set_header X-Forwarded-Proto $scheme;
    }
}
NGINX

ln -sf /etc/nginx/sites-available/myplus /etc/nginx/sites-enabled/myplus
rm -f /etc/nginx/sites-enabled/default
nginx -t && systemctl reload nginx
certbot --nginx -d your-domain -d www.your-domain
```

> **Do not add hop-by-hop headers to the proxy block.** Relaying `Transfer-Encoding` from the gateway
> made Tomcat emit it twice and nginx answered 502 (logging you out). The gateway strips them; leave it.

---

## 6. Before real traffic — the checklist

```bash
# 1. NO seeded demo accounts. Prefixes, not exact names: the ladder is demo./user./admin./owner.<module>@
docker compose exec mysql mysql -uroot -p"$MYSQL_ROOT_PASSWORD" myplusdb_auth -e \
"SELECT email FROM user_account WHERE email LIKE 'demo.%' OR email LIKE 'user.%' \
 OR email LIKE 'admin.%' OR email LIKE 'owner.%' OR email LIKE '%teacher.%' OR email LIKE '%cashier.%';"
# Expect: NO ROWS. Any row is a live account whose password is published in this repo.

# 2. The one that matters most cannot log in.
curl -s -o /dev/null -w '%{http_code}\n' -X POST https://your-domain/api/auth/login \
  -H 'Content-Type: application/json' \
  -d '{"email":"owner.business@myplus.com","password":"Demo@2025!"}'
# Expect: 400/401 — never 200.
```

- [ ] `APP_SEED_DEMO` unset/false, and the query above returns nothing
- [ ] `JWT_SECRET` ≥ 64 random chars, not the example
- [ ] DB passwords strong and unique
- [ ] Only 80/443 open (`ufw status`)
- [ ] HTTPS valid; HTTP redirects
- [ ] `app.live-users.multiplier=1` if you don't want the inflated badge (see the users-online feature)
- [ ] Database backup scheduled (§7)

---

## 7. Operations

```bash
cd /opt/myplus/microservices

docker compose logs -f <service>                     # follow logs
docker compose up -d --force-recreate <service>      # restart one
docker compose ps                                    # health

# Update to latest code
git pull
mvn -q -DskipTests clean package -f pom.xml
mvn -q -DskipTests clean package -f ../pom.xml       # monolith
docker compose up -d --build

# Backup / restore
docker compose exec mysql sh -c 'exec mysqldump -uroot -p"$MYSQL_ROOT_PASSWORD" --all-databases' > backup-$(date +%F).sql
docker compose exec -T mysql sh -c 'exec mysql -uroot -p"$MYSQL_ROOT_PASSWORD"' < backup-2026-01-01.sql
```

### Log rotation (protects VPS disk)

Compose caps each container's logs. Confirm it applied:

```bash
docker inspect --format '{{ .HostConfig.LogConfig }}' myplus-<service>
```

Raise verbosity for one service without a rebuild:

```bash
docker compose exec <service> sh -c 'echo ok'   # then set LOGGING_LEVEL_* in .env and recreate that one
docker compose up -d --force-recreate <service>
```

---

## 8. Troubleshooting

| Symptom | Cause | Fix |
|---|---|---|
| Service not in Eureka | Started before config-server | `docker compose up -d --force-recreate <service>` |
| 502 from nginx, random logouts | Hop-by-hop headers relayed | Use the §5.7 proxy block verbatim |
| `Access denied for user` | `.env` not loaded / wrong `DB_PASSWORD` | Services read `${DB_PASSWORD}`; unset = access denied |
| Startup crash on timezone | Bad `connectionTimeZone` | Must be URL-encoded offset `%2B05:00` — not bare, not `LOCAL`, not a named zone |
| Code change has no effect | **Stale jar** | `package` (not `compile`), then `up -d --build` |
| Flyway "Unknown setting" / missing column | Migration didn't run | Check the service log for `Migrating schema … to version "N"` |
| `Data truncated for column 'status'` | Java enum value added without an `ALTER … MODIFY enum` | `@Enumerated(STRING)` → MySQL enum needs a migration; `ddl-auto` won't do it |

---

## 9. Known gap — `party-service` is not in `docker-compose.yml`

`party-service` (8096, `myplusdb_party`) is the shared contact/CRM master. Five modules bridge to it
(business, education, welfare, pharmacy, marketplace) and the owner **Contact 360** view reads it — but it
has **no entry in `microservices/docker-compose.yml`**, so `docker compose up` never starts it.

**Impact is contained by design:** every bridge is best-effort, off the domain transaction, with a short
timeout and a circuit breaker. Records save with `party_id = NULL` and re-link on the next write. So
nothing breaks — but Contact 360 stays empty and party ids never populate.

**Until it is added**, either accept that (fine for a single-module pilot) or run it outside compose:

```bash
java -jar microservices/party-service/target/party-service-*.jar --server.port=8096
```

Adding it to the compose file is a small change and worth doing before any deployment that sells
Contact 360.
