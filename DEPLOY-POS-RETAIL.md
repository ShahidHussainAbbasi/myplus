# Deploy POS / Retail (Docker) — Local → Hostinger VPS

End-to-end runbook to build and run the **POS / Retail** slice of MyPlus with Docker, first
locally, then on the Hostinger VPS `187.127.125.91`.

POS/Retail does **not** need every microservice. This runbook's **primary path runs only the
subset** the retail POS depends on; the other domain services (education, welfare, agriculture,
pharma, marketplace, analytics, appointment) are left out of the `up` command. Every module is
Docker-packaged, though, so you can bring up the **entire platform** with a single `docker compose
up -d --build` (no service list) — see §9 *Deploy the full stack*.

> **Signup e-mail:** account verification and password-reset e-mails are sent by
> **notification-service**. It is included in the POS subset below — without it, new users never
> receive the verification link and therefore can never log in.

---

## 1. What gets deployed

| Component | Container | Host port | Role |
|-----------|-----------|-----------|------|
| MySQL 8 | `myplus-mysql` | 3306 | Per-service DBs (`myplusdb` = monolith+business, `myplusdb_auth`, `myplusdb_catalog`, `myplusdb_inventory`, `myplusdb_finance`) |
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

**Approx. RAM for the POS subset:** mysql 1.5 GB + monolith 1 GB + 8 JVMs × 0.75 GB +
notification 0.5 GB ≈ **~9.5 GB**. Size the VPS accordingly (≥ 8 GB, 12 GB comfortable).
The **full stack** (all 18 services) needs ~16 GB — see §9.

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
```

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
mvn -q -DskipTests -pl eureka-server,config-server,api-gateway,auth-service,notification-service,catalog-service,inventory-service,business-service,finance-service -am install
cd ..

# Build the monolith (UI) jar -> target/myplus.jar
mvn -q -DskipTests clean package
```

> First build is slow (downloads dependencies). To build **everything** instead, just run
> `cd microservices && mvn -DskipTests install` — simpler, just slower.

### 3.3 Bring up the POS subset

```bash
cd microservices
docker compose up -d --build \
  mysql redis eureka-server config-server api-gateway \
  auth-service notification-service catalog-service inventory-service business-service finance-service monolith
```

Compose starts these in dependency order (mysql/redis → config/eureka → gateway → services →
monolith). First boot creates the databases automatically (`createDatabaseIfNotExist=true`).

> To run **every** module instead of the POS subset, drop the service list: `docker compose up -d
> --build` (see §9).

### 3.4 Verify

```bash
docker compose ps                       # all should be "running"/"healthy"
docker compose logs -f monolith         # watch the UI come up (Ctrl-C to stop tailing)
```

- Eureka dashboard: <http://localhost:8761> — should list AUTH, NOTIFICATION, CATALOG, INVENTORY, BUSINESS, FINANCE, GATEWAY
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
```

> Never commit real secrets. `.env` is git-ignored.
> `APP_BASE_URL` is what makes the verification link clickable from a customer's phone/PC. If it is
> left at the localhost default, the link is dead — this is the #1 cause of "I can't sign up / verify".

### 4.6 Build + run the POS subset

```bash
cd /opt/myplus/microservices
mvn -q -DskipTests -pl eureka-server,config-server,api-gateway,auth-service,notification-service,catalog-service,inventory-service,business-service,finance-service -am install
cd /opt/myplus && mvn -q -DskipTests clean package        # monolith jar
cd /opt/myplus/microservices

docker compose up -d --build \
  mysql redis eureka-server config-server api-gateway \
  auth-service notification-service catalog-service inventory-service business-service finance-service monolith

docker compose ps
```

> Or run **all modules**: `docker compose up -d --build` with no service list (§9).

First open **http://187.127.125.91:8080** (open port 8080 temporarily — see firewall below) to
confirm it runs, then put it behind nginx + TLS (§4.8).

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

- [ ] `INTERNAL_SECRET` set to a strong value (gateway↔service trust; empty = protection off).
- [ ] Strong unique `DB_PASSWORD` and `JWT_SECRET` (never the `.env.example` samples).
- [ ] MySQL **not** exposed publicly — `ufw` blocks 3306, or remove `ports: ["3306:3306"]` from compose
      (services reach it on the internal network regardless). Same for 8761/8765/8888.
      Optional: bind to localhost only, e.g. `ports: ["127.0.0.1:8765:8765"]`.
- [ ] HTTPS via certbot; HTTP→HTTPS redirect (certbot adds it).
- [ ] `DDL_AUTO=validate` once Flyway baselines exist (first prod boot may use `update`).
- [ ] Regular `mysql-data` volume backups (§6).
- [ ] Rotate the Gmail app password / reCAPTCHA keys out of any committed history.

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
| Out-of-memory / build killed on VPS | Add swap (§4.1) or build with the registry approach (§4.9); the POS subset needs ~9.5 GB at runtime, the full stack ~16 GB. |

---

## 8. Quick reference — the one command

After secrets are set and jars are built:

```bash
cd microservices && docker compose up -d --build \
  mysql redis eureka-server config-server api-gateway \
  auth-service notification-service catalog-service inventory-service business-service finance-service monolith
# open http://localhost:8080  (local)  /  https://<your-domain>  (VPS)
```

---

## 9. Deploy the full stack (all modules)

Every module ships a Dockerfile (Java 21 runtime) and is wired into `microservices/docker-compose.yml`,
so the whole platform — POS **plus** education, welfare, agriculture, pharma, marketplace, campaign,
analytics, appointment — comes up with no service list:

```bash
cd microservices
# 1. Build every module (one reactor). Slower first time; -DskipTests keeps it moving.
mvn -q -DskipTests install
cd .. && mvn -q -DskipTests clean package        # monolith jar
cd microservices

# 2. Bring up EVERYTHING (omit the service list = all services in the compose file)
docker compose up -d --build
docker compose ps
```

Notes:
- **RAM:** the full stack is ~16 GB. On an 8–12 GB VPS, stick to the POS subset (§3.3/§4.6) or add
  swap (§4.1) and expect slower boots.
- **Secrets:** same `.env`. Domain services that send mail (education, campaign) reuse
  `MAIL_USERNAME`/`MAIL_PASSWORD`; notification-service uses `MAIL_USER`/`MAIL_PASSWORD`.
- **Selective add-ons:** to add just one domain to a running POS stack, name it — e.g.
  `docker compose up -d --build education-service` (Compose starts its deps as needed).
