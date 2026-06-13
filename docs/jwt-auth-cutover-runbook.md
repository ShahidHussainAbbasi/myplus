# MyPlus — JWT / Auth-Server Integration: Cutover Runbook

Single source of truth for taking the monolith from **local DB authentication** to the
**auth-service (JWT) Identity Provider**, with all microservice calls flowing through the API
gateway. Covers what changed, the ordered cutover, verification, and rollback.

> **Model:** *Hybrid* — the monolith keeps its Thymeleaf form + HTTP session, but credentials are
> verified by the auth-service, the JWT is held server-side in the session, and downstream calls
> go through the gateway with `Authorization: Bearer`. **Model A:** privileges live in the
> auth-service and travel inside the token.

---

## 1. Target architecture

```
Browser ──login(form)──▶ MONOLITH (:8080, session)
                          │  POST /api/auth/login ─────────────▶ gateway ─▶ auth-service
                          │  ◀── { accessToken, refreshToken, roles, privileges }
                          │  (JWT stored server-side in TokenStore / HttpSession)
                          │
                          └─ data calls: Authorization: Bearer <jwt>
                                         ▼
                          API GATEWAY (:8765)  ── validates JWT, injects
                            X-User-Id/Email/Roles/Privileges + X-Internal-Secret,
                            StripPrefix(/api/<svc>)
                                         ▼
                          resource services (business/education/…) — HeaderAuthFilter
                            trusts the headers ONLY if X-Internal-Secret matches
```

Single identity store: **`myplusdb_auth`** (auth-service). Microservice business data references
users by `user_id` = the monolith's original `user_account.id` (IDs are **preserved** in migration).

---

## 2. What changed (by phase)

| Phase | Change | Key files |
|------|--------|-----------|
| 0 | Shared, auto-configured header-auth + JWT plumbing | `microservices/common-security/*`, `microservices/service-parent/pom.xml`, gateway `JwtAuthenticationFilter` (adds `X-User-Privileges`, `X-Internal-Secret`), auth-service `AuthService.buildClaims` (`privileges` claim) |
| 1 | Auth-service owns the full role/privilege catalog + user migration | auth-service `SetupDataLoader`, `microservices/auth-service/migration/migrate_monolith_users.sql` |
| 2 | Monolith login delegation (hybrid) | `AuthServerClient`, `AuthServerAuthenticationProvider`, `TokenStore`, `AuthServerLoginResponse/Envelope`, `SecSecurityConfig` (`auth.mode`) |
| 3 | Single outbound client through the gateway | `GatewayClient` (Bearer + refresh-on-401 + dual-mode), `BusinessRestClient`/`EducationRestClient` reduced to facades, gateway `StripPrefix=2` |
| 4 | Enforce internal secret + network isolation | `config-server/configs/application.yml` (`gateway.internal-secret`/`service.internal-secret`), `docker-compose.yml` (edge-only ports, `expose`, `INTERNAL_SECRET`) |

All modules compile (`mvn -o compile`). Nothing is active until the switches below are flipped.

---

## 3. The switches (defaults are all "off / legacy")

| Setting | Where | Default | Cutover value |
|--------|-------|---------|---------------|
| `auth.mode` | monolith `application.properties` | `local` | `server` |
| `auth.server.url` | monolith | `http://localhost:8765` | gateway URL |
| `gateway.url` | monolith | `http://localhost:8765` | gateway URL |
| `INTERNAL_SECRET` | env on gateway + every resource service | *(unset → enforcement OFF)* | a strong shared random string |
| `JWT_SECRET` | env on gateway + auth-service | dev default | a strong shared random string (≥256-bit) |

> **Golden rule:** `INTERNAL_SECRET` enforcement and the docker network isolation **require**
> `auth.mode=server`. In `local` mode the monolith calls services directly without the secret and
> would be rejected. Flip `auth.mode` **before** enabling enforcement.

---

## 4. Pre-cutover checklist

- [ ] Full backup of `myplusdb` **and** `myplusdb_auth`.
- [ ] `mvn -o compile` succeeds for `microservices/` and the monolith.
- [ ] eureka, config-server, mysql reachable.
- [ ] A strong `INTERNAL_SECRET` and `JWT_SECRET` chosen and stored in your secret manager.
- [ ] Maintenance window agreed (login is briefly affected at step 5).

---

## 5. Cutover procedure (ordered)

### Step 1 — Start core services, seed the catalog
Bring up eureka, config-server, mysql, then **auth-service**. On startup `SetupDataLoader` seeds the
full role/privilege catalog into `myplusdb_auth`.

Verify the catalog (the migration depends on these role names existing):
```sql
SELECT name FROM myplusdb_auth.roles ORDER BY name;
SELECT COUNT(*) FROM myplusdb_auth.privileges;   -- expect ~30
```

### Step 2 — Migrate users (preserves IDs)
**Preflight** — confirm source column names match the script's assumptions:
```sql
DESCRIBE myplusdb.user_account;
DESCRIBE myplusdb_auth.users;
```
Edit the `SELECT` in the script if your monolith used snake_case columns. Then run:
```bash
mysql -uroot -p < microservices/auth-service/migration/migrate_monolith_users.sql
```
**Verify** (from the script's own checks):
- `source_users` ≈ `target_users` (minus any pre-existing duplicates).
- `unmapped_role_name` query returns **zero rows**. *(If not: add the missing role name to
  auth-service `SetupDataLoader`, restart auth-service, re-run step 2 of the script.)*
- "users with no roles" query: investigate any rows.

### Step 3 — Smoke-test auth-service directly
```bash
curl -s -X POST http://localhost:8765/api/auth/login \
  -H 'Content-Type: application/json' \
  -d '{"email":"admin@myplus.com","password":"Admin@2025!"}'
```
Expect `success:true` with `accessToken`, `refreshToken`, `roles`, **`privileges`**. Try a real
migrated user to confirm the copied BCrypt password verifies.

### Step 4 — Start resource services + gateway (enforcement still OFF)
Leave `INTERNAL_SECRET` **unset** for this step so you can validate routing in isolation. Verify
the gateway path + JWT injection end to end:
```bash
TOKEN=... # accessToken from step 3
curl -s http://localhost:8765/api/business/getUserCompany \
  -H "Authorization: Bearer $TOKEN"
```
Expect a normal business-service response (proves JWT validation, `StripPrefix`, and
`HeaderAuthFilter` reading the injected headers). A call with **no** token must return `401`.

### Step 5 — Flip the monolith to server mode
In the monolith `application.properties` (or its config source):
```properties
auth.mode=server
auth.server.url=http://localhost:8765
gateway.url=http://localhost:8765
```
Restart the monolith. Verify in a browser:
- Login with a migrated account succeeds; you land on the correct dashboard.
- Privilege-gated UI (`sec:authorize`) and method security behave as before (privileges came from
  the token).
- A business/education screen loads data (proves `GatewayClient` → gateway → service with Bearer).
- Let an access token expire (15 min) and confirm a data call still works (refresh-on-401).

### Step 6 — Enable the internal secret + isolation
Set the **same** `INTERNAL_SECRET` on the gateway and every resource service, then deploy:
```bash
cd microservices
INTERNAL_SECRET='<your-strong-secret>' JWT_SECRET='<your-strong-secret>' \
  docker compose up -d --build
```
The compose already publishes **only** edge ports (8765/8761/8888/3306); resource services are on
the private `myplus-net` and reachable only via the gateway.

**Verify enforcement:**
- App still works through the monolith (gateway stamps the secret).
- Resource service ports are **not** reachable from the host (`curl http://localhost:8083/...` →
  connection refused).
- If reachable internally, a request carrying `X-User-Id` but a wrong/missing `X-Internal-Secret`
  is treated as anonymous → `401`.

---

## 6. Post-cutover verification checklist

- [ ] Login (migrated user) works via the monolith.
- [ ] Logout clears the session.
- [ ] Business + Education data screens load and mutate correctly.
- [ ] A user's privileges in the JWT match what they had in the monolith (spot-check `DELETE_*`,
      `CHANGE_PASSWORD_PRIVILEGE`).
- [ ] Token refresh works silently after 15 min.
- [ ] Direct service ports refuse host connections; gateway-only access enforced.
- [ ] No `myplusdb`-side authentication is happening (check logs).

---

## 7. Rollback

| If failure at… | Rollback |
|----------------|----------|
| Step 2 (migration) | `myplusdb_auth` users/users_roles are additive; restore the `myplusdb_auth` backup. `myplusdb` is untouched. |
| Step 5 (monolith server mode) | Set `auth.mode=local`, restart monolith. It immediately authenticates against `myplusdb` again (local provider still present). |
| Step 6 (enforcement) | Unset `INTERNAL_SECRET` (and/or re-publish ports) → enforcement disables; combine with `auth.mode=local` to fully revert. |

The legacy path (`CustomAuthenticationProvider`, `MyUserDetailsService`, direct `*.service.url`
calls) is intact behind the flags, so rollback is a config flip — no redeploy of code required.

---

## 8. Known limitations / follow-ups (not blocking)

1. **Logout token revocation** — `MyLogoutSuccessHandler` does not yet call auth-service
   `/api/auth/logout`; the refresh token lives until expiry. Low risk; wire when convenient.
2. **Facade removal** — `BusinessRestClient`/`EducationRestClient` are thin wrappers over
   `GatewayClient`. Optional: migrate the ~22 controllers to call `GatewayClient` directly and
   delete the facades.
3. **Config-server fail-open** — if config-server is unreachable, services fall back to the empty
   `internal-secret` default (enforcement off). Ensure config-server availability, or pin the env
   on each container (the compose already does).
4. **Monolith build nit** — `src/main/java/com/spring/PersistenceJPAConfig.java:56` has a stray
   `OK` after `return dataSource;` that breaks compilation; remove it before building the monolith.
5. **Decommission `myplusdb` users** — after a stable period on `auth.mode=server`, remove the
   monolith's local auth classes and the `user_account`/`role`/`privilege` tables.

---

## 9. Production hardening & secret externalization (pre-AWS)

Design + findings: [`prod-hardening.md`](prod-hardening.md) and
[`microservices/docs/security/DFD-and-findings.md`](../microservices/docs/security/DFD-and-findings.md).

> **Change from earlier in this runbook:** the committed *default* values for `JWT_SECRET`,
> `INTERNAL_SECRET`, `DB_PASSWORD`, `MAIL_PASSWORD`, and `RECAPTCHA_SECRET` are being **removed**
> (branch `security/prod-hardening`, phase P1). In the `prod` profile these are now **mandatory** —
> a service fails fast if they are unset. Dev uses a gitignored `.env` (+ committed `.env.example`).

**Operator actions (owned by you — not in code):**
- [ ] **Rotate every leaked secret** (they are public in git history): generate a new ≥256-bit
      `JWT_SECRET`, a new MySQL password, a new Gmail app password, a new reCAPTCHA secret, a strong
      `INTERNAL_SECRET`. Store in **AWS Secrets Manager / SSM**.
- [ ] Change the seeded **admin password** (`admin@myplus.com`) — it is no longer hardcoded; supply
      via env. Disable demo/seed users in prod (`seed-demo-users=false`).
- [ ] Run with `SPRING_PROFILES_ACTIVE=prod` and the env injected from the secret store.

**Required prod env vars:** `DB_USER`, `DB_PASSWORD`, `JWT_SECRET`, `INTERNAL_SECRET`,
`MAIL_PASSWORD`, `RECAPTCHA_SECRET`, `CONFIG_SERVER_PASSWORD`, `EUREKA_PASSWORD`,
`CORS_ALLOWED_ORIGINS`, `DDL_AUTO` (`update` on first prod boot, then `validate` once Flyway baselines
land — phase P4).

**What the `prod` profile hardens** (see design doc §2): gateway discovery-locator **off** (F1),
inbound identity headers fully stripped (F3), CORS allow-list (F4), `ddl-auto=validate` (F9),
actuator `health,info` only (F10), SQL/bind logging `WARN` (F11), upload size limits (F13), DevTools
off, seed users off (F15). Monolith CSRF (F12) is a separate later phase (P5).

## 10. Quick reference

| Component | Port (host) | Notes |
|-----------|-------------|-------|
| Monolith | 8080 | session UI |
| API gateway | 8765 | only public entry to services |
| auth-service | 8081 | `expose` only in docker; via gateway `/api/auth/**` |
| eureka / config-server | 8761 / 8888 | edge |
| mysql | 3306 | `myplusdb`, `myplusdb_auth`, per-service DBs |
| resource services | 8082–8090 | `expose` only — gateway-internal |

**Auth endpoints (via gateway, open):** `POST /api/auth/login`, `/api/auth/refresh`,
`/api/auth/register`, `/api/auth/verify-email`, `/api/auth/forgot-password`,
`/api/auth/reset-password`.

**Default admin (seeded):** `admin@myplus.com` / `Admin@2025!` *(dev only — rotate for prod, set via
env; see §9)*.

**Secrets:** `JWT_SECRET` (gateway + auth-service, ≥256-bit), `INTERNAL_SECRET` (gateway + all
resource services, identical value), plus `DB_PASSWORD`, `MAIL_PASSWORD`, `RECAPTCHA_SECRET`,
`CONFIG_SERVER_PASSWORD`, `EUREKA_PASSWORD`. **All committed defaults removed in prod profile (§9) —
rotate the leaked values.** Manage via AWS Secrets Manager / SSM.

---

## 11. Demo accounts & free-trial gate (slice 19)

**Demo accounts (seeded by auth-service `SetupDataLoader`, dev seed flag `app.seed-admin`):**
one per domain microservice — `demo.<domain>@myplus.com` / `Demo@2025!` for
`business, education, welfare, agriculture, appointment, inventory, pharma, marketplace, campaign, analytics`.
All carry `demo=true` (JWT claim) and `DEMO_ROLE` = full module privileges + `DEMO_PRIVILEGE`. They log in
as normal users; `userType` routes each to its module dashboard (the 5 with a monolith dashboard —
education/business/appointment/agriculture/welfare — are offered on the login + landing "Try a demo"
selectors; the rest fall back to `/`).

**The 50-entry cap (gateway, Redis):** for a `demo=true` JWT, the gateway counts **create POSTs** per
`(userId, module)` per day in Redis (`demo:{userId}:{module}:{yyyy-MM-dd}`, TTL → local midnight, so it
**auto-resets daily**). The 51st → `403 {code:"DEMO_LIMIT", message:"…register at maxtheservice.com…"}`,
surfaced as an upsell modal (`js/demo.js`). Reads-via-POST (`/get,/load,/list,/search,/report,/find,/view,
/export`) and non-demo users bypass; **fail-open** on Redis errors.

> **Infra dependency:** the gateway now needs **Redis** (`spring.data.redis`, `REDIS_HOST`/`REDIS_PORT`).
> Dev: `docker run -d --name myplus-redis -p 6379:6379 redis:7-alpine`. Compose: `redis` service added,
> gateway `depends_on` it. If Redis is down the cap fails open (creates allowed) — not a hard outage.

**"Reset demo"** (banner button / `POST /demo/reset`): clears the demo user's gateway counters
(gateway `POST /demo/reset`, Bearer → `DEL demo:{userId}:*`) **and** purges their module data. The purge is
ONE shared implementation in the **`common-service`** library (`DemoPurgeController`, auto-applied to every
JPA service via `service-parent`): `DELETE /demo/purge` deletes the caller's rows by whichever tenancy
column each entity has (`organizationId`/`userId`), **demo-only** two ways
(`@PreAuthorize("hasAuthority('DEMO_PRIVILEGE')")` + explicit authority check → real tenant **403**). The
monolith calls it only for a demo principal (`DemoResetController.PURGE_PATHS` by `userType` → all 5
dashboard modules). Gateway routes `/api/<module>/demo/**` → `/demo/purge` (StripPrefix). `auth-service` is
not a `service-parent` child, so it never receives the purge.

> **`common-service`** is the home for future cross-cutting platform code (auto-configured like
> `common-security`): add a shared bean there and it lights up in every JPA service.

**P5 note:** the monolith owns **no database** — `JDBC_URL`/`DB_*` are gone from the monolith; only the
microservices use `DB_PASSWORD`/MySQL. Identity is the in-memory `User` principal from the JWT.
