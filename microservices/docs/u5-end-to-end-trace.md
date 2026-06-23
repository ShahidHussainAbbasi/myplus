# U5 end-to-end trace — register → purchase → sale, tracking IDs across every table

Goal: create a **fresh** set of records and follow their IDs through **every** table, so the full
trade/saga pipeline is verifiable and any silent drop (like the saga sells `getUserSell` bug) is obvious.

**Setup**
- Saga **ON** (config-server `business-service.yml` `trade.saga.enabled: true`, rebuilt config-server **and**
  business-service, restarted). Verify: `curl :8888/business-service/default` → `"trade.saga.enabled":true`.
- Log into the monolith as **`demo.business@myplus.com` / `Demo@2025!`** → org **16**, user **60** (where the
  saga + inventory are set up). (For pharmacy, use `demo.pharma` and its org instead.)
- MySQL: `& "C:\Program Files\MySQL\MySQL Server 8.0\bin\mysql.exe" -u root -p<DB_PASSWORD>`
- Fill in the **Tracking table** at the bottom as you go.

> Tip: every capture query is "newest row for org 16" or "by the unique name you typed", so you always grab
> exactly what you just created.

---

## Phase 1 — Registration (Register menu). After each UI add, run its capture query.

**1. Company (pharmacy: "Distributor")** — add name `TRACE-CO`
```sql
SELECT id AS company_id, name FROM myplusdb.company WHERE name='TRACE-CO' AND organization_id=16;
```
**2. Vendor/Supplier** — add name `TRACE-VEN`
```sql
SELECT vender_id, name FROM myplusdb.vender WHERE name='TRACE-VEN' AND organization_id=16;
```
**3. Item (pharmacy: "Medicine")** — add name `TRACE-ITEM` (pick Company=TRACE-CO, Vendor=TRACE-VEN)
```sql
SELECT item_id, iname, company_id, vender_id FROM myplusdb.item WHERE iname='TRACE-ITEM' AND organization_id=16;
```
**4. Customer (pharmacy: "Patient")** — add name `TRACE-CUST` (unique contact, e.g. `0300TRACE01`)
```sql
SELECT customer_id, name, contact FROM myplusdb.customer WHERE name='TRACE-CUST' AND organization_id=16;
```

## Phase 2 — Migrate the new item to catalog (saga needs a productId)
A fresh item has no catalog product, so the saga would reject it. **Two equivalent ways:**

**(a) SQL script (transparent, recommended for the trace)** — edit `@itemId` and run
[`scripts/migrate-item-to-catalog.sql`](scripts/migrate-item-to-catalog.sql). It creates the catalog product +
the `item_catalog_map` bridge, idempotently. (Leaves `selling_price` NULL like the real ETL — uncomment the
`UPDATE ... selling_price` line if you want the saga to sell at a real price instead of 0.00.)

**(b) Java endpoint (canonical / production path)** — through the gateway with a SUPER/ADMIN token:
```powershell
curl.exe -s -X POST "http://localhost:8765/api/business/admin/migrate-catalog" -H "Authorization: Bearer <TOKEN>"
curl.exe -s -X POST "http://localhost:8765/api/business/admin/migrate-stock"   -H "Authorization: Bearer <TOKEN>"
```
Capture the productId + catalog product:
```sql
SELECT item_id, product_id FROM myplusdb.item_catalog_map WHERE item_id=<ITEM_ID>;
SELECT id AS product_id, name FROM myplusdb_catalog.products WHERE id=<PRODUCT_ID>;
```

## Phase 3 — Purchase (stock in) — Purchase → New Purchase, item=TRACE-ITEM, qty 50
Capture the purchase, the local Stock, and the inventory entry (D3 dual-write pushes purchases to inventory):
```sql
SELECT purchase_id, user_id FROM myplusdb.purchase WHERE organization_id=16 ORDER BY purchase_id DESC LIMIT 1;
SELECT stock_id, batch_no, current_stock FROM myplusdb.stock WHERE item_id=<ITEM_ID> AND organization_id=16;
SELECT id, quantity, reserved_quantity FROM myplusdb_inventory.stock_entries
  WHERE product_id=<PRODUCT_ID> AND organization_id=16;
```

## Phase 4 — Sale via the saga — Sale → New Sale, item=TRACE-ITEM qty 2, customer=TRACE-CUST, submit
Capture the invoice, the sell line, the reservation, and the inventory decrement:
```sql
-- invoice header: note CH_ID + RESERVATION_ID; saga_status should be PENDING then CONFIRMED
SELECT customer_history_id, invoice_no, saga_status, reservation_id
  FROM myplusdb.customer_history WHERE organization_id=16 ORDER BY customer_history_id DESC LIMIT 1;
-- sell line: saga line carries product_id, stock_id is NULL (this is correct)
SELECT sell_id, product_id, stock_id, quantity, sell_rate FROM myplusdb.sell WHERE customer_history_id=<CH_ID>;
-- reservation row (inventory) — should be CONFIRMED
SELECT id, status, created_at FROM myplusdb_inventory.reservations WHERE id=<RESERVATION_ID>;
SELECT reservation_id, product_id, quantity FROM myplusdb_inventory.reservation_picks WHERE reservation_id=<RESERVATION_ID>;
-- inventory decremented by 2 (50 -> 48), reserved back to 0
SELECT id, quantity, reserved_quantity FROM myplusdb_inventory.stock_entries
  WHERE product_id=<PRODUCT_ID> AND organization_id=16;
```
Then confirm the **UI**: the sale shows in `#tableSell` **with item name TRACE-ITEM** (the getUserSell saga fix).

---

## Tracking table (fill in)
| Step | Entity | Table | ID column | Value |
|---|---|---|---|---|
| 1 | Company   | `myplusdb.company`  | `id`                  | COMPANY_ID = |
| 2 | Vendor    | `myplusdb.vender`   | `vender_id`           | VENDER_ID  = |
| 3 | Item      | `myplusdb.item`     | `item_id`             | ITEM_ID    = |
| 4 | Customer  | `myplusdb.customer` | `customer_id`         | CUSTOMER_ID= |
| 2 | Catalog map | `myplusdb.item_catalog_map` | `product_id`  | PRODUCT_ID = |
| 3 | Purchase  | `myplusdb.purchase` | `purchase_id`         | PURCHASE_ID= |
| 3 | Local stock | `myplusdb.stock`  | `stock_id`            | STOCK_ID   = |
| 3 | Inventory | `myplusdb_inventory.stock_entries` | `id`     | ENTRY_ID   = |
| 4 | Invoice   | `myplusdb.customer_history` | `customer_history_id` | CH_ID = |
| 4 | Sell line | `myplusdb.sell`     | `sell_id`             | SELL_ID    = |
| 4 | Reservation | `myplusdb_inventory.reservations` | `id`    | RESERVATION_ID = |

**Pass criteria:** every row above has an ID; the sell line has `product_id` set + `stock_id` NULL; the
reservation is `CONFIRMED`; inventory `quantity` dropped 50→48; and `#tableSell` shows the sale with the item name.
