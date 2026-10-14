# SRA — Sales by rep and area: who sold, where, and every column a dimension

**Status:** SRA-1 GREEN 2026-10-14 (§4.1). SRA-2 BUILT 2026-10-14, awaiting deploy + gate (§5.1). SRA-3..4: design.
**Client:** Shafeeq Medicine (Mr. Javed) — the distributor O7 (pre-sales) and O8 (round documents) were built for.
**Prompt:** a sample invoice from the distributor's current software (Eman Medicine & Surgical) printing
*"Salesman: IRFAN (DAVIS)"* and *"City: KHANPUR(1ST)"*, and the ask: *"report by salesman/booker … sales by area …
extract and download"*.

---

## 1. Requirement, as settled with the owner (2026-10-14)

| # | Owner's answer | What it means in the design |
|---|---|---|
| R1 | The distributor is Shafeeq Medicine | Builds on O7/O8; no new module |
| R2 | A booker can be a salesman and a salesman can book — **one role** | One list: **sales rep** |
| R3 | A rep books by **logging in**, or by a **hand-written order** the owner/admin keys in | The rep and the person who keyed it are **two different facts** (R5) |
| R4 | A customer can walk into the outlet, or be a shop in another area that a rep visits; **in future a customer books on the portal and picks the distributor** | Every sale records its **source**: counter · rep (login) · rep (paper) · portal |
| R5 | Keep **both**: who keyed it in the system, and who booked the order | `keyed by` (exists: `booked_by_name`) + **`rep`** (new), stamped separately |
| R6 | Printed "Booked By" = operator is fine | The print is unchanged; the **report** runs on the rep |
| R7 | Report by date, rep, area, product, company/supplier, bonus … — **every column a filter/dimension**, view and download the report or the invoice | One analysis engine (§6) |
| R8 | When a customer's address/area changes, **reports follow it** | Area is read from the customer **at report time** (§3.3) |
| R9 | Customer and supplier registration must have an **inline address form** | City → Area pickers with inline "+ New" (§4) |
| R10 | Grouping depth: *"see how top-level applications do it and follow them"* | Nested grouping, Odoo-style (§6.2) |
| R11 | Who sees what: *"depends on the type of user and the permission the owner assigns"* | PERM-1 permission set + its existing **Sees: Own / All** scope (§6.5) |

---

## 2. Verified state — read, not assumed

| Fact | Evidence |
|---|---|
| The invoice's `booked_by_name` is **whoever keyed the sale**, not the rep | `SagaSaleWriter.java:183` stamps `user.getDisplayName()` (falls back to e-mail); one writer |
| An order invoiced at dispatch credits **the dispatcher** | `DispatchInvoiceService.java:135` calls `tradeClient.recordSale`; `SaleRecordRequest` has **no** rep/booker field — **defect D1** |
| Orders know their booker; invoices do not | `orders.booked_by_user_id` + `booked_by_name` (marketplace V19); never sent to business-service |
| The invoice does **not** record its source | `CustomerHistory` has `orderType` (DINE_IN/TAKE_AWAY/DELIVERY) only; `channel` is on the request and dropped |
| There is **no rep list** | three unrelated notions: `booked_by_name` (keyer), `orders.booked_by_user_id` (login bookers only), O8 `rsSalesman` (free text) |
| A rep's outlets: `Customer.assignedRepUserId` (V38) | read by `CustomerRepo.findOutletsForRep:117`, written by `:164`; **gate red 2/7 since 2026-09-16** per the D6a doc — not re-run here |
| There is **no area list** | `Customer.city` free text (80); `Vender` has `address` only; `Party.address` text. O8 uses the order's shipping address as "area" and notes the client puts the area **inside the account name** (`LABAIK PHARMACY ~ ZAHIR PER`) |
| The Sale Detail Report | `SellController.loadSR` + `/saleReport.csv`: **5 filters** (customer, product, category, customer type, manufacturer), **7 single-level groupings**, CSV only, **no bonus**, filtering done **in Java memory** after loading every line |
| Visibility is permission DATA already | PERM-1 "Sees: Own / All" is enforced through `scope.ALL` (`LocationScope.seesWholeOrg:82`) |
| Excel can be written server-side | `poi-ooxml` is already a business-service dependency |
| No withholding tax anywhere | the sample invoice has WH Tax % / Rs — **out of scope**, raised separately |

---

## 3. Decisions

### 3.1 The rep is a LIST OF PEOPLE, not a list of logins
Reps on paper never log in. A **sales rep** row: name, phone, active, and an **optional** link to a login. A
rep who logs in is matched to their row through that link. Industry: SAP Business One's *Sales Employees* are a
master independent of users; Zoho's *Salesperson* likewise.

### 3.2 The rep is STAMPED on the document; the area is NOT
- **Rep — stamped at write** (id + name on the invoice and the order). A sale belongs to the rep who made it.
  If the outlet is later moved to another rep, last month's figures must not move with it — commission and
  performance would rewrite themselves. Same rule as `booked_by_name` and `balanceAfter`.
- **Area — read at report time** (owner, R8). Reports show the customer's **current** area. Changing a
  customer's area moves their history in the report. Free-text address edits do **not** move it; only the
  area picker does.

### 3.3 Two people on every sale (R5)
| Field | Meaning | Source |
|---|---|---|
| `booked_by_name` *(exists)* | **Keyed by** — the logged-in user who entered it | stamped, unchanged |
| `rep_id`, `rep_name` *(new)* | **Booked by (rep)** — who took the order from the customer | picked / defaulted, stamped |
| `sale_source` *(new)* | `COUNTER` · `REP_LOGIN` · `REP_PAPER` · `PORTAL` | derived at write (§5) |

### 3.4 One report engine, not a second report
The Sale Detail Report screen becomes the analysis engine. A second "sales by rep" report would answer the
same totals differently the first time either changed.

### 3.5 The customer's usual rep replaces `assignedRepUserId`
Today's territory link points at a **user**. It becomes `default_rep_id` → the rep list, and the territory rule
reads the rep's linked login. One concept, not two.

---

## 4. SRA-1 — Areas + inline address (customer AND supplier)

**Schema (business-service):**
- `city` (id, org, name, active) · `area` (id, org, city_id, name, active) — unique `(org, city, name)`,
  case-insensitive. Rows = DATA, owner-maintained.
- `customer.area_id`, `vender.area_id` (nullable). `customer.city` text stays (printed documents read it) and is
  **written from the picked city** so the two cannot disagree.

**Inline address form** (registration and edit, customer and supplier): address line · **City** picker with
**+ New** in place · **Area** picker filtered by city with **+ New** in place. Never leaves the form.

**Import:** `city`, `area` columns — matched case-insensitively; an unknown value is created and **counted in the
import summary**, so a typo is visible rather than silent.

**Existing data:** distinct `customer.city` values are seeded as cities (trimmed, case-folded) — **owner to
confirm** (Q1). Areas start empty; a "customers without an area" filter makes the gap workable.

**Gate:** create inline from both forms · picker filters by city · duplicate refused case-insensitively ·
another tenant's area id refused (anti-IDOR) · import creates + counts · supplier form saves its area.

### 4.1 SRA-1 as built (2026-10-14)

| Where | What |
|---|---|
| business-service V82 | `city`, `area` (InnoDB, UNIQUE per business — case-insensitive by collation), `customer.area_id` + index, `vender.area_id` |
| business-service | `City`/`Area` + repos (every read by org), `AddressService` (add = return existing as FOUND; race-safe, add methods NOT @Transactional on purpose), `AddressController` `/getCities` `/getAreas` `/addCity` `/addArea`; `areaId` on Customer/Vender + DTOs (null = keep, 0 = clear, else must be this business's); picked area writes `customer.city`; import column `area` + "N areas created (names)" in the answer |
| auth-service V31 | `area.view` + `area.create` (view exists only so the matrix row is tickable), granted to every set holding customer.create or supplier.create; `endpoint_permission` POST /addCity, /addArea → area.create |
| monolith | `AddressController` proxy; City/Area pickers with inline "+ New" on customer AND supplier forms (`static/js/business/address.js`, loaded after crud-modal.js); area label under the address in both grids; 12 keys × 6 languages; matrix label "Cities & areas" |
| gate | `cypress/e2e/business/sra-areas.cy.js` — A1–A5 + the four fixes below, each with a positive control |

**Four defects found while tracing the customer/supplier save path — owner-approved, fixed in SRA-1 (found by reading,
not reproduced before the fix):**
1. **Cross-business takeover:** `/addCustomer` and `/addVender` looked the edited row up with a plain `findById` and saved
   it under the CALLER's business — another business's customer or supplier moved into the caller's (EDU-IDOR-2 shape).
   Now `findByIdScoped` / `inMyTenant`; a foreign id is NOT_FOUND and nothing is written.
2. **An edit wiped City, CNIC, Licence no/expiry:** the grid row carried no cells for them, so editRecord() left the
   form fields empty (or holding the previously edited customer's values) and the save wrote that back. They now ride
   hidden in the id cell (never a visible cell — export/search would show a CNIC under Address).
3. **An edit reset the credit account:** `toEntity` ignores `creditAccountCustomerId`, so every edit wrote NULL and
   `stampSelfAsCreditAccount` re-stamped the customer onto ITSELF — a grouped branch fell off its company's limit.
4. **An edit unassigned the outlet from its rep:** same mechanism for `assignedRepUserId` (a likely contributor to the
   D6a red gate). Both are now copied from the stored row.

Not done in SRA-1: Q1 (seeding cities from free-text `customer.city`) — nothing seeded; a customer with typed city and
no area shows "Typed before: …" under the City picker until it is filed.

## 5. SRA-2 — Sales reps

- `sales_rep` (id, org, name, phone, user_id nullable + unique per org, active, version).
- `customer.default_rep_id`; migrate every `assigned_rep_user_id` to a rep row for that user (created if
  missing, named from auth-service), then drop the old column in a **later** release (dual-read window).
- Territory rule `findOutletsForRep` re-pointed to `default_rep_id` → rep.user_id; **fix the D6a red gate here**.
- Screen: Team → Sales reps (owner/admin; PERM-1 action `rep.manage`).

**Gate:** rep without login · rep linked to a login · a login linked twice refused · territory narrows
(D6a's 7 cases go green) · deactivated rep leaves the pickers but keeps history.

### 5.1 SRA-2 as built (2026-10-14)

| Where | What |
|---|---|
| business-service V83 | `sales_rep` (UNIQUE name and login per business, @Version), `customer.default_rep_id`; existing `assigned_rep_user_id` copied across as reps named "Member #<id>" (the owner renames them). The old column is no longer read; drop it later |
| business-service | `SalesRep` + repo + `SalesRepService`; `SalesRepController` `/getSalesReps` (open — pickers) `/addSalesRep` `/updateSalesRep` (rep.manage, checked HERE too); `RequestUtil.holds(code)`; territory rule rebuilt: rep.manage → all outlets, a login linked to a rep → own + unassigned, else all; `/outletAssignments` + `/assignOutlets` need rep.manage (were `seesAllOrg()`), take/return `repId`, refuse another business's or an inactive rep; customer `defaultRepId` (kept on edit; a CHANGE needs rep.manage; must be this business's rep) |
| auth-service V33 | `rep.view` + `rep.manage` (Administrator set), endpoint rules, ui_menu `territory` and `action.rep.assign` |
| monolith | `SalesRepController` proxy; Territory screen gains a Sales reps panel (add, rename, phone, link/unlink a login, deactivate); assign picker lists reps; customer form "Usual rep" picker (rep.manage only); menu + screen gated by `@perm.menu('territory')` instead of role names; matrix label "Sales reps" |
| specs | NEW `sra-reps.cy.js` (B1–B5 + cross-business); `territory-assignment.cy.js` and `territory-screen.cy.js` rebuilt on reps; `sra-areas.cy.js` F4 and `capability-enforcement.cy.js` moved to `repId` |

**The D6a red gate — likely cause, not confirmed:** every territory decision asked `seesAllOrg()` = the permission set's
"Sees: All" ROW scope. A booker whose set had scope ALL was never narrowed and could assign. SRA-2 asks rep.manage to
manage and "is this login a rep" to narrow. Not confirmed because the booker's set was never read in the database.

**Found while building:** `editRecord()` RENAMES the selected option of a searchable picker to the row cell's text —
an id for both new pickers. Both scripts repaint from their list after the edit overlay opens.

## 6. SRA-3 — The rep and source on every order and sale

| Path | Rep | Source |
|---|---|---|
| Rep logs in and books | their own row (from the login link), not editable by them | `REP_LOGIN` |
| Admin keys a paper order | **picker**, defaulted from the customer's `default_rep_id` | `REP_PAPER` |
| Counter sale | picker, default **none** ("Counter") | `COUNTER` |
| Portal order (future) | customer's `default_rep_id` if the setting `sales.rep.creditPortalOrders` is on, else none | `PORTAL` |

- `orders.rep_id/rep_name/sale_source`; `customer_history.rep_id/rep_name/sale_source`.
- **`SaleRecordRequest` gains `repId`, `repName`, `source`** → the dispatch invoice carries the order's rep.
  **Fixes D1.**
- Sale edit: the rep may be corrected by an admin; the change is written to the audit trail.
- O8 route sheet: the free-text salesman becomes the rep picker and filter.
- **History:** no backfill guessed. Field orders already carry `booked_by_user_id` → their invoices **can** be
  backfilled through the order (exact, not a guess). All other past sales show **"Not recorded"**.

**Gate:** each of the 4 paths stamps the right rep + source · paper order keyed by admin shows **keyed by =
admin, rep = the rep** · dispatch invoice carries the order's rep (the D1 case, with a positive control: the
dispatcher's name must NOT appear as the rep) · a rep cannot book as another rep · another tenant's rep id
refused.

## 7. SRA-4 — The analysis report

### 7.1 Dimensions (filter AND group by) and measures
**Dimensions:** date (day / week / month / quarter / year) · rep · source · keyed by · **area** · **city** ·
customer · customer type · product · category · company (manufacturer) · **supplier** (`Product.mainSupplierId`,
the SSR's own rule) · invoice · payment mode · store/location.
**Measures (sum):** qty · **bonus qty** · **bonus value** · gross · discount · tax · net · return qty · return
value · net after returns · invoice count · customer count.
**Bonus as a filter:** *with bonus / without bonus*. Bonus value is valued at **TP** (last purchase rate), the
same rule the SSR uses, so the two reports agree.

### 7.2 Grouping depth — following the market (R10)
| Product | Grouping |
|---|---|
| **Odoo** Sales Analysis | nested groups on rows **and** columns, any depth; flip axes; drill down; export .xlsx |
| **SAP Business One** | fixed 2 levels (sales employee → customer), drill to the documents |
| **Zoho Inventory** | one dimension; unassigned invoices grouped as "Others" |

**We follow Odoo:** any number of **row** levels (the picker offers up to 4 — deeper is unreadable on paper), one
optional **column** split (e.g. month or rep), subtotals at every level, and **drill-down** from any figure to the
invoices behind it, then to the invoice itself (view / print / PDF). Unassigned values group as **"Not recorded"**
(Zoho's "Others").

### 7.3 Downloads — exactly what is on screen
- **Excel (.xlsx)** — server-side via POI, grouped rows with subtotals, numbers as numbers.
- **CSV** — the existing `/saleReport.csv` contract, now carrying the new columns.
- **PDF** — landscape, the grouping and the filter summary printed in the header.
All three call the **same** service method the screen calls, so a file cannot disagree with the screen.

### 7.4 Performance
Filtering moves from Java memory into **SQL** (criteria query over sale lines ⨝ invoice ⨝ customer ⨝ area ⨝ rep ⨝
product). Grouping and totals run in SQL. Indexes: `customer_history (organization_id, dated)`,
`(organization_id, rep_id, dated)`; `customer (organization_id, area_id)`. Detail rows paged; exports capped with a
clear message when exceeded.

### 7.5 Who sees what (R11) — permission DATA, no names in code
- PERM-1 action **`report.sales.view`** grants the screen; **`report.sales.export`** the downloads.
- The set's existing **Sees** scope decides the rows: **All** → the whole business; **Own** → sales where
  **rep = me** or **keyed by me**. The owner decides per set; a rep's set is normally *Own*, Javed's *All*.

**Gate:** each dimension filters · 3-level grouping subtotals reconcile to the grand total · column split ·
drill-down count = the invoices behind the figure · CSV / XLSX / PDF totals = screen totals · *Own* user sees
only their rows (positive control: another rep's sale present for *All*, absent for *Own*) · bonus value at TP
matches the SSR for the same product and period.

---

## 8. Order and effort

```
SRA-1 Areas + inline address ─┐
                              ├─> SRA-3 Rep + source on every sale ──> SRA-4 Analysis report
SRA-2 Sales reps ─────────────┘
```
SRA-1 and SRA-2 are independent. Each slice: Cypress gate + Test Book cases, user builds and deploys.

## 9. Open questions for the owner

- **Q1** Seed cities from the existing free-text `customer.city` values, or start empty?
- **Q2** Portal orders (future): credit the customer's usual rep by default (`sales.rep.creditPortalOrders` on),
  or no rep?
- **Q3** Withholding tax (WH Tax % / Rs on the sample invoice) — wanted? Separate slice.

## 10. Out of scope
Rep commission and targets (O7 B8) · beat/journey plans and visit KPIs (O7 B5/D6) · multiple addresses per
customer (billing/shipping) · withholding tax.
