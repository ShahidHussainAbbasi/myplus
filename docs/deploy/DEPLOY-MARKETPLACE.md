# Deploy E-commerce / Marketplace

Platform setup, secrets, VPS build-out, TLS, firewall, ops and troubleshooting are in
**[`DEPLOY-COMMON.md`](DEPLOY-COMMON.md)** — read that first. This covers only what marketplace adds.

**Dashboards:** `/businessDashboard` (relabelled *Store*) for the merchant · `/store` public storefront
**User type:** `MARKETPLACE`

> **This is the only module with a PUBLIC, unauthenticated surface.** `/store` and `/storefront/**` are
> reachable without login so a guest can browse and check out. Read §7 before exposing it.

---

## 1. What marketplace adds

| Component | Compose service | Port | DB | Role |
|---|---|---|---|---|
| Catalog service | `catalog-service` | 8092 | `myplusdb_catalog` | Product master — what the storefront lists |
| Inventory service | `inventory-service` | 8082 | `myplusdb_inventory` | Stock; guest orders reserve/confirm through the **same saga POS uses** |
| Business service | `business-service` | 8083 | `myplusdb` | The order *is* a sale — invoice, tax, tenders, ledger |
| Marketplace service | `marketplace-service` | 8088 | `myplusdb_marketplace` | Storefront, cart, guest orders, fulfilment lifecycle, coupons, refunds |
| Finance service | `finance-service` | 8094 | `myplusdb_finance` | Payments, receipts, ledger |
| Party service | `party-service` | 8096 | `myplusdb_party` | Shopper identity (anonymous → `runAs`) |

**RAM:** platform ~7 GB + 5 services × 0.75 ≈ **~10.7 GB**. Size ≥ 12 GB.

**Not droppable:** catalog, inventory, business — an order without them cannot reserve stock or become an
invoice.

---

## 2. Build

```powershell
mvn -q -pl microservices/marketplace-service,microservices/business-service,microservices/catalog-service,microservices/inventory-service,microservices/finance-service `
    -am -DskipTests clean package -f microservices/pom.xml

mvn -q -DskipTests clean package
```

## 3. Run

```powershell
cd microservices
docker compose up -d --build `
  mysql redis eureka-server config-server api-gateway auth-service notification-service `
  catalog-service inventory-service business-service marketplace-service finance-service monolith
```

Verify per COMMON §4, then:

```powershell
curl -I http://localhost:8080/store          # 200 WITHOUT a session — it is public by design
```

---

## 4. Smoke test

**Merchant side** (signed in as `MARKETPLACE`):
1. **Product** → create one with stock and a price
2. **Orders** → the list renders

**Shopper side** (a private/incognito window — no login):
3. `/store` → the product is listed
4. Add to cart → checkout as a guest → order placed
5. Merchant **Orders** → the order appears; advance its fulfilment status
6. Cancel an order → stock returns to inventory

Step 4 is the deployment-critical one: it proves the anonymous path reaches inventory's reserve/confirm
saga through the gateway's public route.

---

## 5. Stock behaviour

Guest orders use the **same reserve/confirm saga as the POS**, running as a synthetic user. Consequences
worth knowing before launch:

- An out-of-stock item **blocks checkout** with `OUT_OF_STOCK` — the storefront cannot oversell.
- Stock is decremented on confirm, not on add-to-cart.
- Cancelling an order **returns** its stock.
- **On-hand ≠ sellable.** Expired and quarantined batches inflate on-hand but are excluded from FEFO. A
  storefront can therefore show "in stock" and still refuse checkout. Keep quarantine and expiries tidy.

---

## 6. Owner configuration

Storefront/coupon settings are on the merchant **Configuration** screen. Payment: COD → `PENDING`;
CARD → sandbox charge (see the storefront slice docs before wiring a real processor).

---

## 7. Public-surface hardening — read before going live

The storefront is the only route that serves anonymous traffic, so it is the only one an attacker can
reach without credentials.

- [ ] **`APP_SEED_DEMO` unset.** Non-negotiable here — a public URL plus published demo passwords is an
      open door. Run COMMON §6's query.
- [ ] **Rate-limit `/storefront/**` at nginx.** The gateway's demo-quota counter is not a rate limiter.
      ```nginx
      limit_req_zone $binary_remote_addr zone=store:10m rate=20r/s;
      location /storefront/ { limit_req zone=store burst=40 nodelay; proxy_pass http://127.0.0.1:8080; }
      ```
- [ ] **CSRF:** `/storefront/**` is CSRF-exempt (guests have no token). Confirm nothing state-changing
      beyond guest checkout is under that path.
- [ ] **HTTPS only** — a cart and address over plain HTTP is a data-protection problem, not just a warning.
- [ ] Verify a guest **cannot** reach `/businessDashboard` or any `/api/**` route except the storefront's.

---

## 8. Marketplace-specific gotchas

**An order is a sale.** It reuses the trade saga and gains a fulfilment lifecycle. New sales by a Store
user appear in Orders automatically — that is intended, not a leak.

**Anonymous writes run as a synthetic user (id 0).** If org-scoped queries look wrong for guest orders,
check that the storefront request carried an org — an anonymous request has no JWT to derive one from.

**Refunds are privilege-gated** while most storefront reads are not. Confirm the merchant account holds
the refund privilege before handing over.

---

## 9. Backup

```bash
docker compose exec mysql sh -c \
  'exec mysqldump -uroot -p"$MYSQL_ROOT_PASSWORD" myplusdb_marketplace myplusdb_catalog myplusdb_inventory myplusdb' \
  > marketplace-$(date +%F).sql
```

Plus `myplusdb_auth`. An order references a catalog product and a business-service invoice — restore them
together or you get dangling references.
