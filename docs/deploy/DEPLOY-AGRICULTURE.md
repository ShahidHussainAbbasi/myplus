# Deploy Agriculture

Platform setup, secrets, VPS build-out, TLS, firewall, ops and troubleshooting are in
**[`DEPLOY-COMMON.md`](DEPLOY-COMMON.md)** — read that first. This covers only what agriculture adds.

**Dashboard:** `/agricultureDashboard` · **User type:** `AGRICULTURE`

---

## 1. What agriculture adds

| Component | Compose service | Port | DB | Role |
|---|---|---|---|---|
| Agriculture service | `agriculture-service` | 8086 | `myplusdb_agriculture` | Land registration, expenses, income |

**RAM:** platform ~7 GB + 0.75 ≈ **~7.8 GB**. Tied with welfare as the lightest module — 8 GB is fine.

**No cross-service dependencies.** Agriculture calls no other domain service — the only module that is
genuinely standalone on top of the platform. Nothing to drop, nothing optional.

---

## 2. Build

```powershell
mvn -q -pl microservices/agriculture-service -am -DskipTests clean package -f microservices/pom.xml
mvn -q -DskipTests clean package        # monolith UI
```

## 3. Run

```powershell
cd microservices
docker compose up -d --build `
  mysql redis eureka-server config-server api-gateway auth-service notification-service `
  agriculture-service monolith
```

Verify per COMMON §4, then:

```powershell
curl -I http://localhost:8080/agricultureDashboard
```

---

## 4. Smoke test

1. **Register Land** → add a plot (name, type, unit — Acre/Bigha/Kanal/Marla)
2. **Add Expense** → record an expense against that land, with a crop and category
3. **Add Income** → record income against the same land
4. **Configuration** → the settings screen renders

---

## 5. Agriculture-specific gotchas

**Amounts are `BigDecimal`.** They were `Float` and were migrated — if you restore an old backup, verify
the column type survived. Float money loses precision silently.

**Crop and equipment lists are hard-coded in the UI**, not data-driven: Sugarcane, Wheat, Rice, Maize,
Cotton, Mustard, Canola, Peanuts, Sunflower, Black/White Gram, Jantar, Barley, Sorghum, Pearl Millet,
Field Pea, Lentil, Mung/Mash bean, Arhar, Black-eyed Pea, Cowpea, Rapeseed — and Cultivators, Rotavator,
Harvester, Drill, Raja Hal, Raijor, Leveler, Laser Leveler, Broadcast Seeder, Planter, Cultipacker,
Triller, Fertilizers, Spray, Seed, Diesel, Petrol, Water, Labour, Ushr.

A region needing a different crop is a **template + translation-bundle change**, not configuration. Check
this against your customer's crops before committing to a deployment date — it is the most likely
surprise in this module.

**Land units are South-Asian** (Acre, Bigha, Kanal, Half Kanal, Marla). Same caveat for other regions.

**No Cypress coverage.** Agriculture and welfare are the two modules with no end-to-end specs. Smoke-test
manually after every deployment.

---

## 6. Backup

```bash
docker compose exec mysql sh -c \
  'exec mysqldump -uroot -p"$MYSQL_ROOT_PASSWORD" myplusdb_agriculture' > agri-$(date +%F).sql
```

Include `myplusdb_auth` — accounts and tenants live there.
