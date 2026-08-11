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

> ### ⚠️ FIRST DEPLOY ON A HOST — create the data volume once, ever
>
> ```bash
> docker volume create myplus-mysql-data
> ```
>
> The database volume is declared `external: true` in `docker-compose.yml`, which is what stops
> Docker deleting it. Until it exists the stack refuses to start with *"external volume
> myplus-mysql-data not found"* — a deliberate, loud stop. **If you see that error on a host that was
> already serving traffic, do NOT create the volume and carry on.** It means the stack was previously
> running against a different volume that still holds your data; find it first (§Data safety below).
>
> Existing host, already deployed the old way? Copy the data across once, with mysql stopped.
>
> ### 🛑 FIND the source volume first — never type a guessed name
>
> `docker run -v <name>:/from` **silently creates an empty volume if `<name>` does not exist**. There is
> no error. Copying from it therefore copies *nothing*, MySQL initialises a fresh datadir, and the app
> comes up healthy with no data — indistinguishable from data loss. This exact mistake cost a second
> outage on 2026-08-10, from a guessed source name in this very runbook.
>
> ```bash
> # 1. WHICH volume is the live database actually on? This is the only source you may use.
> docker inspect -f '{{range .Mounts}}{{println .Name .Destination}}{{end}}' myplus-mysql
>
> # 2. Cross-check what each candidate holds — the real one has dozens of .ibd files under myplusdb.
> for v in $(docker volume ls -q | grep -i mysql); do
>   echo "=== $v"
>   docker run --rm -v $v:/v alpine sh -c 'du -sh /v; ls /v | head; echo "myplusdb files: $(ls /v/myplusdb 2>/dev/null | wc -l)"'
> done
> ```
>
> Only once you have a verified source name, substitute it for `<LIVE_VOLUME>`:
>
> ```bash
> docker compose stop mysql
> docker volume create myplus-mysql-data
> docker run --rm -v <LIVE_VOLUME>:/from -v myplus-mysql-data:/to alpine sh -c 'cd /from && cp -a . /to'
> docker run --rm -v myplus-mysql-data:/v alpine sh -c 'ls /v; du -sh /v'   # expect ibdata1, myplusdb, …
> ```
>
> **Verify rows, not size** — a fresh empty MySQL is ~200 MB and looks identical to a real one at a
> glance. After starting: see §3.3 "verify the data is real".
>
> Keep the old volume until the app is verified working. It costs nothing to leave it there.

Back up before touching a stack that holds real data — it takes seconds and it is the only step here
that cannot be redone afterwards:

```bash
./backup-db.sh
```

```powershell
cd microservices
docker compose --profile full up -d --build
```

**This is the same command on your laptop and on the VPS.** There is no prod override and nothing
per-host to set: `docker-compose.yml` pins `name: myplus` (so the directory you run from cannot
change which volume is used), declares the data volume `external` (so `down -v` cannot delete it),
and binds MySQL to `127.0.0.1` only (so the VPS never exposes 3306 to the internet).

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

> ### "dependency failed to start: container myplus-gateway is unhealthy" — usually a lie
>
> On the full stack this almost always means **slow, not broken**. 21 JVMs starting at once contend for
> CPU; the gateway can take 3+ minutes to answer its first probe. Compose gives up, aborts, and never
> starts the monolith — then every service reports healthy a minute later.
>
> `start_period` is 240s (raised from 90s on 2026-08-05 for exactly this). If you still hit it, **check
> before assuming a fault**:
>
> ```powershell
> docker compose --profile full ps        # are they healthy NOW?
> ```
>
> If they are, only the monolith is missing — bring it up on its own, its dependency is now satisfied:
>
> ```powershell
> docker compose --profile full up -d monolith
> ```
>
> Read the service's log before restarting anything. A genuinely broken service shows a stack trace; a
> slow one shows ordinary startup lines with 30–60s gaps between them. Those gaps are the tell.

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
    "SELECT '${db%%:*}', version FROM ${db%%:*}.flyway_schema_history
      WHERE success=1 ORDER BY installed_rank DESC LIMIT 1;"
done
```

> **Do not use `MAX(version)` here.** `flyway_schema_history.version` is a **VARCHAR**, so `MAX()` compares
> it lexically: a database sitting at V36 reports **`9`**, because the string `'9'` sorts above `'36'`. That
> makes a fully-migrated schema look years out of date and sends you rebuilding jars that were fine.
> Order by `installed_rank` (an integer, and the true apply order) instead.

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

Compose staggers the stack into **tiers** via `depends_on` (changed 2026-08-05 — see below):

```
tier 0  mysql · redis · eureka-server
tier 1  config-server
tier 2  api-gateway + the 8 core services (auth, notification, catalog, inventory,
        business, finance, audit, party)
tier 3  the 8 verticals (education, welfare, agriculture, pharma, marketplace,
        campaign, analytics, appointment)
tier 4  monolith
```

> ### Why the verticals wait on the gateway
>
> They do not need it to boot — this is deliberate **CPU staging**. Every vertical used to depend only on
> `mysql` + `config-server`, so the moment config-server went healthy **18 JVMs started at once** and
> JIT-compiled in parallel, starving each other. That is what made api-gateway take 193s to answer its
> first probe and produce *"dependency failed to start: container myplus-gateway is unhealthy"*.
>
> Splitting into waves of 9 → 8 → 1 gives each wave the whole CPU. It costs nothing in capability: every
> request routes through the gateway, so a vertical that starts before it is up serves no one.
>
> **Behaviour change:** if api-gateway never becomes healthy, the verticals now never start (previously
> they started anyway and sat unreachable). That is a clearer failure, not a worse one — but it does mean
> **the gateway is the single thing to diagnose first** when the full stack won't come up.

If you start services by hand, follow the same order.

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

## 8. Backup and restore — end to end

Everything below assumes the production host: `/opt/myplus/microservices`, compose project `myplus`,
container `myplus-mysql`, data on the external volume `myplus-mysql-data`.

Set this once per shell; every command in this section uses it:

```bash
cd /opt/myplus/microservices
DB_PASSWORD="$(grep -m1 '^DB_PASSWORD=' .env | cut -d= -f2-)"
```

### 8.1 A complete backup is three things, not one

Restoring the database alone will **not** bring the platform back. You need all three, from the same
point in time:

| # | What | Where it lives | Why it is required |
|---|---|---|---|
| 1 | **The data** | `myplus-mysql-data` → dumped to `backups/myplus-<stamp>.sql.gz` | The 16 interlinked databases. |
| 2 | **The secrets** | `microservices/.env` | `DB_PASSWORD`, `JWT_SECRET`, `INTERNAL_SECRET`, `MAIL_PASSWORD`. Git-ignored, so it exists on **no** other machine. A restored database with a different `JWT_SECRET` invalidates every session; a different `INTERNAL_SECRET` makes every service reject the gateway's headers. |
| 3 | **The code version** | git SHA of the deployed commit | The dump's schema matches the Flyway version of the code that wrote it. Restoring last month's dump onto today's jars, or the reverse, fails validation at startup. |

Capture 2 and 3 alongside the dump:

```bash
cp .env "backups/env-$(date +%F-%H%M).bak"        # then move it OFF the host, encrypted
git rev-parse HEAD > "backups/deployed-sha-$(date +%F-%H%M).txt"
```

> `.env` is a secret. Never commit it, never rsync it to a shared path in the clear. Encrypt it —
> `gpg -c backups/env-*.bak` — or store it in a password manager and keep only the SHA and the dump
> on the backup host.

### 8.2 Taking the backup

Use the script, never a hand-typed `mysqldump`:

```bash
./backup-db.sh                          # -> ./backups/myplus-<stamp>.sql.gz
BACKUP_DIR=/srv/backups ./backup-db.sh  # or somewhere off the app disk
```

`backup-db.sh` does four things a bare dump does not: it **refuses to run if MySQL is down** (rather
than writing a 0-byte file that looks like a backup), it takes `--single-transaction` so the shop keeps
trading while it runs, it **verifies** the gzip is valid *and* actually contains the `myplusdb` schema
before keeping it, and it rotates anything older than `KEEP_DAYS` (14).

If it fails with `Permission denied`, the execute bit is missing on this checkout — `bash backup-db.sh`
works regardless, and `chmod +x backup-db.sh` fixes it for good.

**All databases go in ONE dump on purpose.** They are interlinked: a sale in `myplusdb` references a
product in `myplusdb_catalog` and a payment in `myplusdb_finance`. Per-database dumps taken at
different moments restore into a state that never existed — an invoice pointing at a product row that
is not there yet.

**Install it as a cron job.** An uninstalled backup script is not a backup:

```bash
crontab -e
30 2 * * * cd /opt/myplus/microservices && ./backup-db.sh >> /var/log/myplus-backup.log 2>&1
```

**Copy them off the box.** A dump on the same disk as the database does not survive the failure it
exists to protect against — nor does it survive `docker volume rm`:

```bash
rsync -az --delete ./backups/ user@elsewhere:/srv/myplus-backups/
```

**Know your RPO.** A 02:30 daily job means up to ~24 hours of orders can be lost. If that is too much,
run it more often (`30 2,14 * * *`) or use §8.6 point-in-time recovery. Decide this deliberately rather
than discovering it during an incident.

### 8.3 Verify the backup — a backup you have never restored is a hope

The script's own checks catch a truncated or empty dump. They do **not** prove it restores. Once a
month, prove it on a scratch container — this touches nothing in production:

```bash
docker run -d --name verify-mysql -e MYSQL_ROOT_PASSWORD=verify mysql:8.0
sleep 40
zcat backups/myplus-<stamp>.sql.gz | docker exec -i -e MYSQL_PWD=verify verify-mysql mysql -uroot

docker exec -e MYSQL_PWD=verify verify-mysql mysql -uroot -N -e \
  "SELECT table_schema, COUNT(*) FROM information_schema.tables
   WHERE table_schema LIKE 'myplusdb%' GROUP BY table_schema;"
docker exec -e MYSQL_PWD=verify verify-mysql mysql -uroot -N -e \
  "SELECT COUNT(*) FROM myplusdb_auth.user;"

docker rm -f verify-mysql
```

Expect ~16 schemas and a non-zero user count. **Count rows, never trust size** — a freshly initialised
empty MySQL is ~200 MB and is indistinguishable from a full one by `du` alone. That single confusion
caused both August 2026 incidents.

### 8.4 Before every deploy

Non-negotiable, and it takes seconds:

```bash
./backup-db.sh && echo "safe to deploy"
```

`up -d --build` replaces containers in place and does not touch the volume — but a bad migration in the
new jars can rewrite data, and that is not reversible without this file.

### 8.5 Restore — full disaster recovery

Use when the database is corrupt, wrong, or genuinely empty. **Read §8b first**: if the app merely
*looks* empty, you are probably on the wrong volume and restoring would overwrite good data.

```bash
# 1. Stop everything that writes. Keep the volume — NEVER -v.
docker compose --profile full down

# 2. Bring up ONLY mysql (it carries no profile, so a bare up starts just it) and wait for healthy.
docker compose up -d mysql
until [ "$(docker inspect -f '{{.State.Health.Status}}' myplus-mysql)" = healthy ]; do sleep 3; done

# 3. Restore.
zcat backups/myplus-<stamp>.sql.gz | docker exec -i -e MYSQL_PWD="$DB_PASSWORD" myplus-mysql mysql -uroot

# 4. The dump includes the `mysql` system schema, so users and grants were replaced.
docker exec -e MYSQL_PWD="$DB_PASSWORD" myplus-mysql mysql -uroot -e "FLUSH PRIVILEGES;"

# 5. Verify BEFORE starting the app — rows, not size.
docker exec -e MYSQL_PWD="$DB_PASSWORD" myplus-mysql mysql -uroot -N -e \
  "SELECT table_schema, COUNT(*) FROM information_schema.tables
   WHERE table_schema LIKE 'myplusdb%' GROUP BY table_schema ORDER BY table_schema;"
docker exec -e MYSQL_PWD="$DB_PASSWORD" myplus-mysql mysql -uroot -N -e \
  "SELECT COUNT(*) FROM myplusdb_auth.user;"

# 6. Restore .env from the same point in time if secrets were lost, then start the stack.
docker compose --profile full up -d
```

Two things that bite here:

- **The `shahid` app user comes from the dump, not from compose.** `MYSQL_USER`/`MYSQL_PASSWORD` only
  take effect on a *first* initialisation of an empty datadir. If services report access denied after a
  restore, re-apply the grants: `docker exec -i -e MYSQL_PWD="$DB_PASSWORD" myplus-mysql mysql -uroot < init-db.sql`.
- **Deploy the matching code.** If the dump predates a Flyway migration, check out the SHA from §8.1
  item 3 and rebuild, or Flyway will refuse to validate at startup.

### 8.6 Restore — one database only

Occasionally right (one module corrupted, the rest healthy) and usually risky, because the databases
reference each other. `mysql -o` restricts an `--all-databases` dump to a single schema:

```bash
zcat backups/myplus-<stamp>.sql.gz \
  | docker exec -i -e MYSQL_PWD="$DB_PASSWORD" myplus-mysql mysql -uroot -o myplusdb_education
```

Stop that module first so nothing writes mid-restore (`docker compose stop education-service`), and
afterwards accept that it is now at an *older* point in time than every other database. For anything
touching orders, stock, or money, restore the whole set instead.

### 8.7 Point-in-time recovery (binary logs)

Binary logging is on — you can see `binlog.0000NN` in the data volume — so you can roll forward from a
nightly dump to a few minutes before a mistake (a bad `DELETE`, a wrong bulk edit):

```bash
docker exec myplus-mysql sh -c 'ls -la /var/lib/mysql/binlog.*'

# after restoring the dump per §8.5, replay events up to just before the damage
docker exec myplus-mysql sh -c \
  "mysqlbinlog --stop-datetime='2026-08-11 14:22:00' /var/lib/mysql/binlog.000012" \
  | docker exec -i -e MYSQL_PWD="$DB_PASSWORD" myplus-mysql mysql -uroot
```

**The binlogs live inside `myplus-mysql-data`.** They are gone with the volume, so this covers logical
mistakes only — it is not a substitute for off-host dumps.

### 8.8 Cold volume snapshot (secondary, not primary)

A file-level copy is only consistent with MySQL **stopped** — copying a running datadir yields a file
that looks fine and may not restore, because InnoDB has writes in flight:

```bash
docker compose --profile full down
docker run --rm -v myplus-mysql-data:/v -v "$(pwd)/backups:/backup" alpine \
  tar czf /backup/volume-$(date +%F-%H%M).tar.gz -C /v .
docker compose --profile full up -d
```

Useful as a fast rollback immediately before a risky migration. The logical dump remains the primary
backup: it survives a MySQL version change, and a tar of a datadir does not.

### 8.9 Drill schedule

| Cadence | Action |
|---|---|
| Every deploy | §8.4 pre-deploy dump |
| Daily 02:30 | `backup-db.sh` via cron, log to `/var/log/myplus-backup.log` |
| Daily | rsync `backups/` off-host |
| Weekly | read the log — a silent cron failure is the classic way to discover you have no backups |
| Monthly | §8.3 scratch-container restore drill, ending in a real row count |
| Quarterly | full §8.5 rehearsal on a scratch VPS, including `.env` and the matching code SHA |

### 8.10 Never do these on production

- `docker compose down -v` · `docker volume prune` · `docker system prune` — the volume is `external`
  so `-v` is refused, but the other two are typed by hand and take no notice of intent.
- `docker volume rm <anything>` before listing its contents. Both August incidents began with a volume
  whose contents nobody had checked.
- Copy a datadir while MySQL is running (§8.8).
- Restore over a database you have not first dumped. Even a corrupt database is evidence.
- Judge a database by `du`. **Count rows.**

---

## 8b. Data safety — what protects the database, and what to do when it looks gone

Three guards are built into `microservices/docker-compose.yml`. None of them needs per-host setup:

| Guard | What it stops |
|---|---|
| `name: myplus` | The directory you deploy from deciding the volume name. Renaming or re-cloning the deploy dir used to point MySQL at a new **empty** volume — it initialises, runs `init-db.sql`, and comes up healthy with zero rows. Nothing errors. |
| `mysql-data` → `external: true`, `name: myplus-mysql-data` | `docker compose down -v` and `docker volume prune` deleting the data. Docker refuses to remove an external volume. |
| `127.0.0.1:3306:3306` | MySQL being reachable from the public internet on a VPS. |

**Never on a production host:** `down -v`, `docker volume prune`, `docker system prune`, or a bare `up`
paired with `--remove-orphans` (it deletes containers of disabled profiles — see §3).

There is no reason to `down` a running production stack at all: `up -d --build` replaces whatever
changed, in place. `down` only adds the chance of typing `-v` after it.

### If the app comes up with no data

Do **not** run `down -v`, `volume prune`, or another `up` — each one makes the real volume harder to
identify. Most of the time nothing was deleted; you are looking at a *different, empty* volume.

```bash
docker volume ls | grep -i mysql
docker system df -v | grep -i -A40 "VOLUME NAME"        # sizes — the real one is hundreds of MB / GB
docker run --rm -v <VOLUME>:/v alpine sh -c 'ls /v; du -sh /v'   # expect ibdata1, myplusdb, myplusdb_education, …
```

Then check for a dump (§8) before concluding anything is lost:

```bash
ls -lh backups/ /srv/backups/ 2>/dev/null
find / -name 'myplus-*.sql.gz' 2>/dev/null | head
tail -30 /var/log/myplus-backup.log
```

---

## 9. When the host is smaller than the stack

The full stack wants ≥ 16 GB. Below that, **choose a smaller profile rather than squeezing this one** —
an under-provisioned full stack does not fail cleanly, it degrades into intermittent Eureka dropouts and
timeouts that read like network faults and cost hours to diagnose.

Measured 2026-08-05 — `mem_limit` ceilings, and idle RSS of the whole 21-container stack for comparison:

| Profile | Containers | Ceiling | Command |
|---|---|---|---|
| default (POS) | 14 | **10.75 GB** | `docker compose up -d --build` |
| `pharmacy` | 15 | **11.5 GB** | `docker compose --profile pharmacy up -d --build` |
| `full` | 22 | **16.75 GB** | `docker compose --profile full up -d --build` |

**Idle RSS of the full stack was 7.5 GB** — far under its 16.75 GB ceiling. That gap is why an 8 GB box
*looks* like it might cope and then dies: it boots, idles near the limit with nothing left for the OS,
page cache or nginx, and the first real load OOM-kills a JVM. Startup is worse than idle, because every
JVM sizes its heap from the limit before settling.

| Host RAM | What actually fits |
|---|---|
| ≥ 16 GB | Everything (22) — the 16.75 GB ceiling is *oversubscribed* by 0.75 GB on a 16 GB box. That is fine and normal: `mem_limit` is a ceiling, not a reservation, and measured idle is 7.5 GB. It does mean **do not run a Maven build on the box while the stack is up** — that is what turns oversubscription into an OOM. |
| 12–16 GB | POS + one or two verticals — bare `up`, then name them: `docker compose up -d --build education-service` |
| 12 GB | POS + pharmacy |
| **8 GB** | **POS subset only, and only with 4 GB swap.** Not pharmacy, not full. |
| < 8 GB | Nothing here fits — upgrade, or move MySQL off-box to free 1.5 GB |

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
