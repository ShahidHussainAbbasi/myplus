# Manual test & demo use cases — commerce (POS / B2B / B2C)

**Purpose.** One list that serves two jobs: a **manual regression script** a tester can follow end to end,
and the **shot list** for recorded customer demos. Ordered as a story, not by screen — each section is a
journey a real user takes, so it can be recorded start-to-finish without cutting.

**How to read a case.** Every case names its **persona**, its **preconditions**, numbered **steps**, the
**expected result**, and the **slice that proves it** (so a failure has somewhere to go). The 🎬 flag marks
cases worth recording for customers.

**Accounts** (seeded, dev only — `app.seed-demo`):

| Account | Password | Use for |
|---|---|---|
| `demo.business@myplus.com` | `Demo@2025!` | day-to-day operator (has admin privileges) |
| `owner.business@myplus.com` | `Demo@2025!` | owner-only screens — Finance reports, Configuration |
| `demo.pharma@myplus.com` | `Demo@2025!` | pharmacy vertical wording |
| `demo.marketplace@myplus.com` | `Demo@2025!` | storefront back-office |

> **Status caveat (2026-08-04).** These cases describe **shipped, gated** behaviour. A full-suite run is in
> progress; anything it contradicts should be treated as the suite being right and this doc being stale —
> update it here rather than working around it. Cases marked ⚠️ have a **known open question** recorded in
> §K and should not be recorded for customers until it is resolved.

---

## A. Foundation — the buyer's identity drives everything

The single most important concept to land in a demo: **B2B vs B2C is a property of the customer, not a mode
you switch the app into.** The shopkeeper is neither.

### A1 🎬 — A customer's *type* is what makes a sale B2B or B2C
**Persona:** shop operator · **Proves:** Phase 0 (`b2b-customer-type.cy.js`)

1. Log in as `demo.business@myplus.com`, go to **Registration → Customer**.
2. Create a customer, setting **Customer type** = `WALK_IN`.
3. Create a second customer with type = `WHOLESALE`.
4. Reopen the wholesale customer and save it again **without touching the type**.

**Expected:** the type list offers exactly four values — **`WALK_IN`, `RETAILER`, `WHOLESALE`, `VIP`**
(there is no `RETAIL`). The wholesale customer is **still** `WHOLESALE` after the blind re-save.

> **Why this matters in a demo:** step 4 is the interesting one. An edit that omitted the field used to
> silently demote a trade account to walk-in — and with it, their contract prices and credit terms.

### A2 — One login reaches every module the org owns
**Persona:** owner of more than one business · **Proves:** Phase 0.5 (`org-type-routing.cy.js`)

1. Log in as the multi-module fixture (`multi.module@myplus.com`).
2. Use the **organisation switcher** to move between the commerce org and the school.

**Expected:** the dashboard follows the **active organisation's** type, not the user's. Switching org
switches module without logging out.

---

## B. 🎬 The flagship demo — selling to a trade account

**Record this as one continuous video.** It is the strongest story the product currently tells: an agreed
price is honoured at the till, credit is controlled, and the paperwork reconciles afterwards.

### B1 — Author a contract price
**Persona:** owner · **Proves:** Phase 2 + P2-UI (`price-rules-screen.cy.js`)

1. Log in as `owner.business@myplus.com` → **Price Rules**.
2. Create a rule: **Customer** = your wholesale customer, **Product** = a stocked item, **−12%**.
3. Add a second, broader rule: **Customer type** = `WHOLESALE`, same product, **−5%**.

**Expected:** both rules list, ordered by precedence (most specific first). The broader rule is marked
**"Overridden by #n"**.

> **Talking point:** rules **never stack**. The most specific live rule wins *alone* — so a customer on a
> −12% contract does not silently also take the −5% tier and land at −17%.

### B2 🎬 — The contract price is the price actually charged
**Persona:** cashier · **Proves:** P2-UI (`contract-price-charged.cy.js`)

1. Go to **Sell**. **Add the product to the cart first, with no customer selected** — note the rate is the
   plain catalog price.
2. *Now* choose the wholesale customer.
3. Observe the cart line.
4. Complete the sale and open the receipt.

**Expected:** on selecting the customer the line **re-prices to the contract price**, and a **price reason**
appears beside the rate. The receipt shows the discounted amount, and the invoice records *why*.

> **Why this is the money shot:** "scan first, ask who's buying second" is how a counter actually works. The
> price has to follow the buyer being identified, not the other way round.

### B3 — A cashier's manual override still wins
**Persona:** cashier · **Proves:** P2-UI

1. With the contract price applied, **type a different rate** into the rate box.
2. Change the customer, or add another line.

**Expected:** the typed rate is **never** overwritten. Automatic re-pricing only replaces a rate the system
itself set.

### B4 🎬 — Credit limit warns, and asks for a decision
**Persona:** cashier / supervisor · **Proves:** Phase 1 (`credit-limit.cy.js`)

1. Set a **credit limit** and **payment terms (days)** on the wholesale customer.
2. Sell to them on credit for **more than the remaining limit**.

**Expected:** a **confirmation dialog** appears before anything is written — **no invoice, no stock
reserved**. Confirming completes the sale; cancelling leaves no trace.

> **Talking point:** "warn" means *take a decision*, not *log it afterwards*. Under the `block` policy the
> same attempt is refused outright and the confirmation is ignored — that is the entire difference between
> the two policies. Set it in **Configuration → `pos.sale.creditLimitPolicy`** (default `warn`).

### B5 — The supplier side works the same way
**Persona:** buyer · **Proves:** Phase 1

1. Set a credit limit on a **supplier**.
2. Record a purchase that takes you past it.

**Expected:** the same warn/confirm behaviour, capping a **payable** instead of a receivable.

---

## C. 🎬 The retail counter — everyday B2C

### C1 🎬 — Barcode-first selling
**Persona:** cashier · **Proves:** barcode-first sell (`barcode-scan.cy.js`)

1. On **Sell**, put the cursor in the scan box and scan (or type) a barcode.

**Expected:** the line is added to the cart **without any further clicks**. Stock is re-validated at submit,
so a race with another till cannot oversell.

### C2 — Receipt vs invoice, decided by the buyer
**Persona:** cashier · **Proves:** Phase 3b-1

1. Sell to a `WALK_IN` customer → print.
2. Sell to a `WHOLESALE` customer → print.

**Expected:** the walk-in document is titled **RECEIPT**; the trade one is titled **INVOICE**.

### C3 — Split tender and change
**Persona:** cashier · **Proves:** G5 payments

1. Take a sale part cash, part card.

**Expected:** tenders are recorded individually; change is calculated on the cash portion.

### C4 — Park and resume a sale
**Persona:** cashier · **Proves:** park/hold/resume

1. Build a cart, **Park** it, start a new sale, then **Resume** the parked one.

**Expected:** the parked cart returns intact, including its customer.

---

## D. Purchasing, batches and expiry

### D1 🎬 — Capture batch and expiry when goods arrive
**Persona:** buyer · **Proves:** Phase 3a (`purchase-batch-expiry.cy.js`)

1. **Registration → Purchase**, record a purchase entering a **batch number** and **expiry date**.
2. Review the purchase list.

**Expected:** batch and expiry are stored and shown as columns.

### D2 🎬 — Sell traces which batch left the building
**Persona:** cashier / auditor · **Proves:** Phase 3b-2

1. Sell that product.
2. Open the receipt.

**Expected:** the receipt shows **which batch(es)** supplied the line. A single line split across two batches
by FEFO shows **both** — this is why batch is a child table, not a column.

> **Domain point for pharmacy/food demos:** this is the difference between "we sold 10 boxes" and "we can
> tell you exactly which boxes, from which delivery, expiring when" — the whole basis of a recall.

---

## E. 🎬 Returns become real documents

### E1 🎬 — A customer return issues a credit note
**Persona:** counter staff · **Proves:** Phase 3c (`return-documents.cy.js`)

1. Take a **partial return** against a trade invoice.

**Expected:** the response names a **`CRN-` credit note** with its **own number**, which is *not* the invoice
number, while still referencing the invoice it reverses.

### E2 — A supplier return issues a debit note
**Persona:** buyer · **Proves:** Phase 3c

1. Return part of a purchase to the supplier.

**Expected:** a **`DBN-` debit note** number is returned — a document the supplier can reconcile against.
Before this slice the supplier side produced **no document at all**.

### E3 🎬 — The statement tells the truth
**Persona:** accounts / the customer · **Proves:** Phase 3f (`statement-credit-notes.cy.js`)

1. Sell **500** on credit to a trade customer.
2. Return **200** of it.
3. Open that customer's **Statement**.

**Expected:** the statement shows the invoice **still at 500**, a separate **`CRN-` credit line of 200**, and
a balance of **300**.

> **Why this is worth demoing to a finance buyer:** the invoice the customer holds says 500. Before this,
> the statement said 300 with nothing explaining the gap — the balance was right but the paperwork could not
> be reconciled. An issued invoice is never retro-edited; a credit note explains it.

### E4 — Voiding an invoice leaves a trail
**Persona:** supervisor · **Proves:** Phase 3f

1. Void an invoice, then open the statement.

**Expected:** the bill **and** its cancellation both appear, netting to **zero** — rather than the invoice
silently vanishing.

---

## F. Reporting, statements and finance

### F1 🎬 — Filter and group the Sale Detail Report
**Persona:** owner · **Proves:** Phase 3e (`report-grouping.cy.js`)

1. **Sell → Sale Detail Report**. Run it unfiltered.
2. Filter by **customer**, **product**, **category**, **channel**.
3. **Group by** day / month / customer / product / category / channel.
4. **Export CSV**.

**Expected:** subtotals per group; a two-line invoice counts as **one transaction**; the **CSV mirrors the
screen** including grouping.

### F2 — Statements download and reconcile
**Persona:** accounts · **Proves:** Phase 3d (`statement-download.cy.js`)

1. Open a customer statement → **Download**. Repeat for a supplier.

**Expected:** the CSV matches the on-screen statement **line for line**, because both come from the same
service method.

### F3 — Ageing
**Persona:** owner · **Proves:** F2

1. Open **Receivables ageing**, then **Payables ageing**.

**Expected:** 0–30 / 31–60 / 61–90 / 90+ buckets; settled parties are absent.

### F4 — Owner-only finance reports
**Persona:** owner · **Proves:** finance reports UI

1. Log in as `owner.business@myplus.com` → **Finance**.
2. Open **P&L**, **Balance Sheet**, **Tax register**, **Audit trail**.

**Expected:** the Balance Sheet **balances**. The Finance section is **not visible** to a non-owner.

---

## G. E-commerce storefront (B2C online)

### G1 🎬 — Guest buys online and it reaches the back office
**Persona:** shopper, then staff · **Proves:** slice 68 cart + storefront (`storefront.cy.js`)

1. Open the public **/store**, add an item to the **cart**, check out as a guest.
2. In the back office, open **Orders**.

**Expected:** the order appears with source **STOREFRONT** and status **NEW**.

> **Note for testers:** checkout is **cart-based** — items are added to a server cart addressed by a
> `cartToken`, then checked out. Posting items inline no longer works (slice 68).

### G2 — Online sales reserve real stock
**Persona:** staff · **Proves:** storefront stock saga

1. Note on-hand, place a storefront order for 2, re-check on-hand.

**Expected:** on-hand drops by 2 through the **same reservation saga the POS uses**.

### G3 — Out-of-stock is refused with a real reason
1. Try to check out an item with no stock.

**Expected:** the message names the **stock** problem — not a generic retry.

### G4 — Cancel returns the stock
1. Cancel a storefront order from the back office and confirm the dialog.

**Expected:** status **CANCELLED** and stock restored.

### G5 — Track an order publicly
1. Track by **reference + contact**; then retry with the **wrong contact**.

**Expected:** the timeline shows for the right contact; the wrong contact is refused.

---

## H. Multi-location

### H1 — Stores and grants
**Persona:** owner · **Proves:** multi-location

1. Create two stores; grant a user access to Store B only.
2. As that user, list sales and use the **store switcher**.

**Expected:** only Store B data is visible, and the switcher offers only granted stores.

### H2 ⚠️ — Cross-store isolation
1. As a Store-B admin, try to open a **Store-A** sale by id.

**Expected:** `NOT_FOUND`.

> ⚠️ **Do not record this for customers yet** — see §K1. The guard exists but has a fail-open path.

---

## I. Configuration (owner)

### I1 — Per-tenant settings actually change behaviour
**Persona:** owner · **Proves:** common-settings, standard C1/C2

1. **Configuration**, change `pos.sale.marginPolicy` (`off`/`warn`/`block`, default **warn**).
2. Sell below cost.
3. Repeat for `pos.receipt.showPromo` and `pos.sale.creditLimitPolicy`.

**Expected:** each toggle changes **behaviour**, not just the stored value.

### I2 — Tax codes and multi-rate tax
1. Define tax classes; sell items at differing rates.

**Expected:** the receipt shows a **per-rate breakdown**.

---

## J. Suggested demo recordings

| # | Title | Cases | Story |
|---|---|---|---|
| **1** | **Selling to a trade account** | A1 → B1 → B2 → B4 → E1 → E3 | Agreed price honoured, credit controlled, paperwork reconciles |
| **2** | **Running the counter** | C1 → C2 → C3 → D1 → D2 → F1 | Fast selling, full traceability, reports that answer questions |
| **3** | **Selling online** | G1 → G2 → G4 → G5 | One stock pool, one order book, across counter and web |
| **4** | **The owner's view** | F1 → F2 → F3 → F4 → I1 | Money, ageing, tax and control in one place |

Record with the established pattern (`cypress/e2e/education/demo.cy.js`): headed Chrome, `video=true`, an
on-screen caption banner, and deliberate pauses. **Reuse selectors from the real specs** so a demo cannot
drift away from the app.

---

## K. Open questions — resolve before recording the affected cases

1. ⚠️ **Location guard fail-open (case H2).** `LocationScope.canAccess` returns `true` when the caller has
   **no grants** *or* the **row has no store**. The first arm is deliberate (single-store tenants); the
   second means an unstamped row is visible to every store in a multi-store org. Slice 106 §2e.
2. ⚠️ **Team nav vs API.** The Team nav is gated to `ROLE_OWNER` **or** `ADMIN_PRIVILEGE`, but `/team/users`
   still requires `ROLE_OWNER` — an admin can open *Manage Users* and be refused on submit. Slice 106 §8.2.
3. **Payment terms are captured but not enforced** — `paymentTermsDays` is stored; deriving the invoice due
   date from it is still deferred. Do not claim automatic Net-30 due dates in a demo.
