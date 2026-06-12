# Slice 19 — Demo accounts + 50-entry quota (free-trial gate)

**Status: DESIGN — decisions made, implementing.** SaaS free-trial gate ([[feedback-saas-standards]]).
Then P5. Memory: [[project-demo-quota]].

## 1. What & why
A **demo user per module** (business/education/welfare/agriculture/appointment) can create up to **50
entries per module**; the 51st create is blocked with an upsell: *"Register at maxtheservice.com to use
the full features."* The count **auto-resets daily** and a **"Reset demo"** button purges the demo
user's entries + counter on demand.

## 2. Decisions (made)
- **Enforcement:** API-gateway write-counter (counts create POSTs per demo-user+module). One place,
  covers all services.
- **Counting:** creates only (POST), **50 per module** (independent per module).
- **Reset:** daily auto-reset (time-windowed counter) **+** on-demand "Reset demo" button.
- **Store:** **Redis** (TTL keys; distributed, survives gateway restarts).

## 3. Architecture
```mermaid
flowchart LR
    U[Demo user JWT demo=true] --> M[Monolith dashboard]
    M -- POST create (Bearer) --> GW[Gateway demo filter]
    GW -- INCR demo:{uid}:{module}:{yyyy-MM-dd} EX=endOfDay --> R[(Redis)]
    GW -- count<=50 --> S[Service]
    GW -- count>50 --> X[403 DEMO_LIMIT + upsell msg]
    M -- Reset demo --> P[purge: per-service delete org data + DEL Redis keys]
```

### Demo identity
- auth-service `User.demo` (new bool col, ddl-auto) → JWT claim `demo`. Seeded demo users per module
  (`demo.<module>@myplus.com` / `Demo@2025!`, `userType=<MODULE>`, `demo=true`) via SetupDataLoader
  (self-healing, dev seed flag). userType routes each to its own dashboard (already generic).

### Gateway counter (Redis, reactive)
- key `demo:{userId}:{module}:{yyyy-MM-dd}`; `INCR` + set TTL to end-of-day on first hit.
- module = 2nd path segment (`/api/<module>/...`). Count **POST** only; skip reads via a small denylist
  (`/get`, `/load`, `/list`, `/search`, `/report`, `/find`). If post-increment value > 50 → short-circuit
  403 JSON `{success:false,code:"DEMO_LIMIT",message:"You've reached the 50-entry demo limit. Register at
  maxtheservice.com to unlock the full features."}`.
- Non-demo users bypass entirely.

### Reset ("Reset demo")
- Monolith `POST /demo/reset` → for the logged-in demo user: call each module service's
  `DELETE /api/<module>/demo/purge` (org-scoped truncate of the caller's org) + gateway
  `POST /internal/demo/reset` (DEL the Redis day-keys for that user). Demo data isolated in the user's
  auto-created primary org, so purge = delete all rows for that org.

### UI
- Dashboards surface a `DEMO_LIMIT` 403 (the proxy relays `code/message`); show the upsell + a
  "Register" CTA to maxtheservice.com. A demo banner + "Reset demo" button on each dashboard.

## 4. Phases
- **A. Infra + gateway:** Redis (docker-compose + gateway `data-redis-reactive` + config) + the demo
  counter filter (enforce + daily TTL).
- **B. auth-service:** `demo` claim + per-module demo users (self-healing).
- **C. Reset:** per-service `DELETE /api/<module>/demo/purge`; gateway reset endpoint; monolith
  `/demo/reset` + dashboard button.
- **D. UI:** relay `DEMO_LIMIT` + upsell CTA + demo banner.

## 5. Test
- Cypress/API: as a demo user, 50 creates pass, 51st → 403 DEMO_LIMIT + message; "Reset demo" clears →
  creates allowed again; next-day key resets (simulate by deleting the key). Non-demo unaffected.
