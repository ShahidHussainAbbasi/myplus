# Deploy Appointments / Scheduling

Platform setup, secrets, VPS build-out, TLS, firewall, ops and troubleshooting are in
**[`DEPLOY-COMMON.md`](DEPLOY-COMMON.md)** — read that first. This covers only what appointments add.

**Dashboard:** `/appointmentDashboard` · **User type:** `APPOINTMENT`
**Public pages:** `/appointment`, `/appointmentReq`, `/registerHospital`

---

## 1. What appointments add

| Component | Compose service | Port | DB | Role |
|---|---|---|---|---|
| Appointment service | `appointment-service` | 8091 | `myplusdb_appointment` | Hospitals, doctors, schedules, appointment tokens |

**RAM:** platform ~7 GB + 0.75 ≈ **~7.8 GB**. 8 GB VPS is fine.

**No cross-service dependencies** among the domain services.

---

## 2. Build

```powershell
mvn -q -pl microservices/appointment-service -am -DskipTests clean package -f microservices/pom.xml
mvn -q -DskipTests clean package        # monolith UI
```

## 3. Run

```powershell
cd microservices
docker compose up -d --build `
  mysql redis eureka-server config-server api-gateway auth-service notification-service `
  appointment-service monolith
```

Verify per COMMON §4, then:

```powershell
curl -I http://localhost:8080/appointmentDashboard
curl -I http://localhost:8080/appointment       # PUBLIC — 200 without a session
```

---

## 4. Smoke test

**Staff side** (signed in as `APPOINTMENT`):
1. **Hospital** → register a hospital (timing, appointment type, capacity)
2. **Doctor** → register a doctor with schedule days/times, attached to that hospital

**Patient side** (private/incognito window — no login):
3. `/appointment` → pick the hospital → the doctor list populates
4. Request an appointment → a token is generated
5. Staff dashboard → the request appears

Step 3 exercises `loadDoctorsByHospital` / `loadDoctorDetails`, which are public routes — if the doctor
list stays empty for a guest, that is the thing to check first.

---

## 5. Public-surface note

These paths are deliberately unauthenticated so a patient can book without an account:

```
/appointment      /appointmentReq      /registerHospital*
/loadDoctorsByHospital      /loadDoctorDetails
```

Before exposing publicly:

- [ ] **`APP_SEED_DEMO` unset** — COMMON §6
- [ ] **Rate-limit `/appointmentReq`** at nginx. Anonymous booking is trivially scriptable, and a flood of
      junk tokens is hard to unpick afterwards:
      ```nginx
      limit_req_zone $binary_remote_addr zone=appt:10m rate=5r/s;
      location /appointmentReq { limit_req zone=appt burst=10 nodelay; proxy_pass http://127.0.0.1:8080; }
      ```
- [ ] Enable **reCAPTCHA** (`app.captcha.enabled=true` + `RECAPTCHA_SECRET` / `GOOGLE_RECAPTCHA_KEY_SITE`)
      if the booking form is publicly linked
- [ ] Confirm a guest cannot reach `/appointmentDashboard`

---

## 6. Appointment-specific gotchas

**The monolith's `appointment` still reads the legacy `user` table.** This is the one residual dependency
left from decommissioning the monolith auth store — every other module is clean. If you deploy
appointments and see user-lookup errors, that residue is the first place to look. Tracked in
`docs/monolith-auth-decommission.md`.

**Doctor availability is schedule text, not a booking engine.** Days and times are stored and displayed;
there is no double-booking prevention or slot arithmetic. Capacity is `appointmentOfferValue` (total
patients, or minutes each) — a guideline, not an enforced limit. Say so plainly to a clinic evaluating it.

**Hospital registration is public** (`/registerHospital`). Confirm that is what you want; if hospitals
should be staff-created only, remove that route from the permit-list in `SecSecurityConfig` before launch.

---

## 7. Backup

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

