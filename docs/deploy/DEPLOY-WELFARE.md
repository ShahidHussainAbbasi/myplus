# Deploy Welfare / Donations

Platform setup, secrets, VPS build-out, TLS, firewall, ops and troubleshooting are in
**[`DEPLOY-COMMON.md`](DEPLOY-COMMON.md)** — read that first. This covers only what welfare adds.

**Dashboard:** `/welfareDashboard` · **User type:** `WELFARE`

---

## 1. What welfare adds

| Component | Compose service | Port | DB | Role |
|---|---|---|---|---|
| Welfare service | `welfare-service` | 8085 | `myplusdb_welfare` | Donators, donations, receipts |
| Party service | `party-service` | 8096 | `myplusdb_party` | Shared contact master — a donor may also be a customer elsewhere. **Not in compose** (COMMON §9) |

**RAM:** platform ~7 GB + 0.75 ≈ **~7.8 GB**. This is the lightest module — an 8 GB VPS is fine.

**Droppable:** `party-service` (Contact 360 goes empty; donations are unaffected).

---

## 2. Build

```powershell
mvn -q -pl microservices/welfare-service -am -DskipTests clean package -f microservices/pom.xml
mvn -q -DskipTests clean package        # monolith UI
```

## 3. Run

```powershell
cd microservices
docker compose up -d --build `
  mysql redis eureka-server config-server api-gateway auth-service notification-service `
  welfare-service monolith
```

Verify per COMMON §4, then:

```powershell
curl -I http://localhost:8080/welfareDashboard
```

---

## 4. Smoke test

1. **Add Donator** → register a donor (name, mobile, address, show/hide preference)
2. **Add Donation** → record a donation against that donor
3. **View Donations** → it appears in the list
4. **Configuration** → the settings screen renders

---

## 5. Owner configuration

**Configuration** (both default **OFF** = today's behaviour):

| Setting | Effect when ON |
|---|---|
| `welfare.donation.requireDonor` | A donation must name a donor — attribution/audit for grant-funded charities |
| `welfare.donator.allowDuplicateNames` | Donors with the same name are allowed (families, common names) |

`requireDonor` is the one worth discussing with a charity before go-live: grant reporting usually needs
donor attribution, but anonymous cash donations are common. Off means anonymous donations are accepted.

---

## 6. Welfare-specific gotchas

**Donation amounts are `BigDecimal`.** They were `Float` and were migrated — if you restore an old backup,
check the column type survived. Float money silently loses precision.

**The donor "show me" flag is a display preference, not access control.** A donor choosing *Hide my
donation* is hidden from public-facing lists, not from staff reads. Do not present it to a donor as
privacy protection.

**No Cypress coverage.** Welfare and agriculture are the two modules with no end-to-end specs. Smoke-test
manually after every deployment — nothing will catch a regression for you.

---

## 7. Backup

```bash
docker compose exec mysql sh -c \
  'exec mysqldump -uroot -p"$MYSQL_ROOT_PASSWORD" myplusdb_welfare' > welfare-$(date +%F).sql
```

Include `myplusdb_auth` — accounts and tenants live there.
