# Customer requirements — review, analysis & implementation plan

**Status:** ANALYSIS v2 — reconciled with your answers and with the existing OMS program.
**Scope:** 12 requirements (1–13, no 12) for POS / Retail + Pharmacy.
**Method:** every claim checked against the code and against the OMS docs — not assumed.

> **v2 changes:** *booker = supplier* (no new party type) · credit limit = **warn** · returns get their own
> series · promo **off by default** · "multi-dimensional" = selectable filters, not pivot tables · and the
> whole set is now mapped onto [`oms-program-plan.md`](oms-program-plan.md) /
> [`oms-b2b-b2c-implementation-plan.md`](oms-b2b-b2c-implementation-plan.md).

---

## 1. The most important finding: two of these are already planned work

Your requirements **#9** and **#10** are not new — they are **OMS Track B** items, already analysed:

| Your requirement | Already tracked as | OMS doc says |
|---|---|---|
| **#9** dues limit (customer/supplier) | **B4 — Credit limits & terms** | *"`CreditLimit = 0`; `Customer` has only `dueAmount`. Add credit limit + payment terms (Net 30/60), a credit-check gate at order validation, credit hold"* |
| **#10** customer/product-wise discount | **B1 — Contract & tiered pricing + price lists** | *"`ContractPrice/TieredPrice/PriceList = 0`. Add customer-/group-specific price lists and volume breaks; resolve price = base → contract → volume tier → promotion"* |
| **#6** multi-dimension reports | **D3 — OMS analytics** (partial overlap) | analytics read-model on analytics-service |

**Implication:** if I build #9 and #10 as standalone POS features, they will be rebuilt when Track B lands
— and a second credit-limit implementation is worse than none, because the two will disagree. They should
be built **as** B4 and B1, scoped to what your customer needs now.

This is the compose-don't-duplicate standard applied to the plan itself.

**Also confirmed:** **G2 is fixed.** The OMS plan lists *"POS returns are not saga-aware"* as blocking, but
`SellController.saleReturn` now calls `inventoryClient.returnStock(...)` (and `importStock` for non-saga
sells), and a `SaleReturn` entity exists. So **requirement #1 has no blocker underneath it** — the OMS
tracker's Phase 0 is effectively done and should be marked ✅.

---

## 2. Booker = supplier — three requirements just got simpler

You confirmed a booker **is** a supplier. That removes the new-party-type problem entirely:

- **#6** — "by supplier/booker" is one dimension (`Vender`), not two
- **#9** — supplier dues limit extends the existing `Vender.dueAmount`
- **#11** — targets/bonuses attach to `Vender`; **no new entity, no new role, no auth change**

`Vender` already carries `dueAmount` with a targeted updater, and is already party-bridged. #11 drops from
**L** to **M**.

---

## 3. Revised effort

| # | Requirement | Today | Effort | Home |
|---|---|---|---|---|
| 3 | Consent when profit ≤ 0 | 🟢 **built** | XS (verify + submit-time check) | business-service |
| 8 | Previous dues on sale/purchase | 🟢 built on sale | XS (purchase side) | monolith UI |
| 13 | MaxTheService promo | 🔴 new | XS | receipt/statement renderer |
| 1 | Return invoices | 🟡 returns work | S | business-service |
| 4 | Receipt: batch, expiry, prev. bal, line no. | 🟡 | S (after F1) | receipt.js |
| 5 | Statement download | 🟡 on-screen only | S | monolith UI |
| 2 | Batch # on purchase | 🔴 fields commented out | M | **F1 — do first** |
| 7 | Stock cap + expiry e-mail | 🟡 alerts exist | M | inventory + notification |
| 9 | Dues limit | 🔴 | M | **= OMS B4** |
| 10 | Customer/product discount | 🔴 | M | **= OMS B1** |
| 11 | Supplier targets & bonuses | 🔴 | M *(was L)* | business-service |
| 6 | Multi-dimension reports | 🟡 one report | M *(was L)* | **= F2** |

**#6 dropped from L to M** on your clarification: *"option to select or add filter to view/export"* is a
**filterable report with column/dimension selection** — not an interactive pivot engine. That is a
filter rail + a group-by dropdown + export, which is much less work and much easier to use.

---

## 4. Foundations (unchanged from v1, both confirmed still needed)

### F1 — Batch & expiry on the purchase line → unblocks #2, #4, #7

`PurchaseDTO` **already has the fields, commented out**:
```java
//	private String batchNo;
//	private LocalDate bexpDate;
```
`inventory-service.StockEntry` already stores `batchNo` + `expiryDate`, and FEFO already picks on them. So
storage and consumption exist; only **capture at purchase** is missing. Today batch/expiry can only arrive
through `addProductStock`, not through the purchase flow a shopkeeper actually uses — which is why expiry
alerts (#7) are currently near-useless.

### F2 — Filterable report engine → delivers #6, feeds #1, #5, #11

One parameterised endpoint: **measure** (qty · revenue · cost · profit) × **group by** (product · customer
· supplier · manufacturer · day/week/month) × **filters** (date range · branch · category · party), and
one screen that renders whatever comes back, with CSV/PDF export.

**Profit is real, not estimated:** `Sell.costPrice` is persisted per line. Caveat to surface in the UI —
it is **null for legacy sells and never-purchased products**, so those rows must be excluded and counted
("N lines have no cost recorded"), never silently treated as 100% margin.

---

## 5. B2B / B2C setup

You asked to implement "B2B and B2C based setup". Reading your 12 requirements against the OMS plan, they
are **almost entirely B2B-flavoured**: supplier targets, credit limits, contract discounts, statements,
supplier reports. The B2C storefront path is already the stronger one.

That matches the OMS plan's own recommendation:

> *"**B2B-first** — the storefront/POS B2C path is largely built; B2B commercial is the true 0-today gap
> and higher-value."*

**So: B2B-first, and your 12 requirements ARE the first concrete slice of it.** Rather than run a separate
"customer requirements" track alongside OMS Track B, I propose folding them together — your customer's
needs become the acceptance criteria for B1/B4, which stops the abstract Track B from being over-built.

**Channel model.** B2B vs B2C should be a **per-customer flag**, not a separate deployment or a fork of
the sell screen:

| | B2C (default) | B2B |
|---|---|---|
| Pricing | catalog price | contract / tiered price list (**#10**) |
| Credit | pay now | credit limit + terms (**#9**) |
| Discount | manual | rule-driven (**#10**) |
| Documents | receipt | invoice + statement (**#5**, **#13**) |

One sell screen, one order path, behaviour switched by the customer's type — exactly how PHARMA/BUSINESS
already white-label one dashboard.

---

## 6. Answers folded in

**#9 credit limit → warn.** Non-blocking by default; the cashier sees *"Ali Traders is Rs 45,000 over
their Rs 200,000 limit"* and may proceed. Configurable `off | warn | block` so a stricter org can harden
it. No supervisor-override privilege needed for warn — that only matters if you later choose block.

**#1 return series → own numbers.** Best practice and what auditors expect:

| Document | Series | Why |
|---|---|---|
| Sale invoice | `INV-…` | existing |
| **Sale return / credit note** | **`CRN-…`** | a credit note is its own legal document; reusing the invoice number breaks the audit trail and most tax regimes require a distinct reference |
| Purchase | `PUR-…` | existing |
| **Purchase return / debit note** | **`DBN-…`** | same, from the other side |

Each return references its source invoice (`against INV-00123`), so the trail is explicit in both
directions. Per-org numbering already exists, so this is a new series, not new plumbing.

**#13 promo → off by default.** Your wording, adopted verbatim into the setting's help text:

> *"The promo footer prints a 'Powered by MaxTheService' line on invoices and receipts. To avoid surprising
> paying clients, we keep this off by default and only enable it for trial accounts or with explicit
> consent."*

`pos.receipt.showPromo` default **false**; trial/demo tenants seeded **true**. Applies to receipts,
statements and report exports.

**#6 filters → a filter rail, not a pivot.** Selectable dimensions and filters, with the current selection
carried into the export so what you print is what you saw.

---

## 7. Sequence

Merged with the OMS tracker — Track B slices are named so they land as B1/B4, not as duplicates.

| Phase | Contents | Notes |
|---|---|---|
| **0** | Mark OMS Phase 0 ✅ (G2 verified done) · **#3** submit-time margin check · **#8** purchase-side dues · **#13** promo block | Days. Immediately visible, zero dependencies |
| **1** | **F1** batch/expiry on purchase → **#2** · then **#4** receipt lines | Highest-value single change; unblocks #7's expiry alerts |
| **2** | Document/export writer → **#5** statements · **#1** return series (CRN/DBN) | One PDF/CSV writer serves both; `jspdf` already vendored |
| **3** | **F2** report engine → **#6** | Filterable + exportable; reuses phase 2's writer |
| **4** | **#7** stock cap + expiry config + **daily digest** e-mail | Independent — pull earlier if urgent |
| **5** | **OMS B1** → **#10** customer/product pricing & discount rules | Track B proper; your requirement is the acceptance criteria |
| **6** | **OMS B4** → **#9** credit limit (warn) + terms | Pairs with B1 — both touch order validation |
| **7** | **#11** supplier targets & bonuses | Needs F2 for achievement calculation |

Each phase: design doc (3 Mermaid diagrams per `docs/DESIGN-STANDARD.md`) → implement → `mvn test` → headed
Cypress gate you run → next.

---

## 8. UI/UX

The infrastructure exists and must be reused, not reinvented: responsive contract (767/991/1199), shared
confirm dialog, crud-modal, self-rendering settings screen, six languages with RTL, `.table-scroll` for
wide grids.

- **Reports (#6)** — filter rail left, results right, export in the header. One screen, not six.
- **Discount rules (#10)** / **targets (#11)** — shared crud-modal, so they look like every other screen.
- **Alerts (#7)** — a dashboard card matching the existing KPI cards.
- **Receipt options (#4, #13)** — live preview beside the toggles; printing to check a setting is a
  miserable loop.
- **Credit warning (#9)** — inline on the sell screen next to the dues block that already exists, not a
  modal. A warning you must dismiss on every sale becomes a warning nobody reads.
- **Every new string** through `#{ui.*}` / `t('ui.js.*')` into all six bundles — the gate fails the build
  on a missing key.

---

## 9. Configuration

All on the existing common-settings catalog — a new setting is a one-line entry, and the screen renders
itself.

| Group | Settings |
|---|---|
| **Selling** | `pos.sale.marginPolicy` (off/warn/block) · `pos.sale.marginCheckOnSubmit` |
| **Receipts** | `pos.receipt.showBatch` · `showExpiry` · `showLineNo` · `showPreviousBalance` · `showPromo` |
| **Credit** | `pos.credit.policy` (off/**warn**/block) · `pos.credit.defaultLimit` |
| **Discounts** | `pos.discount.autoApply` · `pos.discount.allowOverride` |
| **Inventory alerts** | `inv.stock.minQty` · `inv.expiry.warnDays` · `inv.alerts.email` · `inv.alerts.recipients` · `inv.alerts.digestHour` |
| **Targets** | `sales.target.period` · `sales.bonus.model` |

Every default = today's behaviour. An existing shop sees no change until it opts in.

---

## 10. Remaining decisions

**Q1 — Confirm the merge.** Build **#9/#10 as OMS B4/B1** (my recommendation), or as standalone POS
features now and reconcile later? Standalone is faster to demo and guarantees rework.

**Q2 — Which phase first?** Phases are independent enough to reorder. If a specific requirement is
blocking a sale or a complaint, name it and it goes first.

**Q3 — Supplier target metric** (#11): revenue, quantity, or margin? And per period — monthly, quarterly?
The bonus model (flat / % / tiered) follows from that. Simplest that works, then iterate.

**Q4 — Statement scope** (#5): customer statements only, or supplier statements too? Both exist on screen
today; download is the gap.

---

## 11. Risks

- **Duplicate credit-limit implementations** if #9 ships outside B4. The two would disagree, and the
  disagreement would surface as "the till let me sell but the books say credit hold".
- **Historical profit is incomplete** — `costPrice` is null for legacy sells and never-purchased products.
  Surface the count in the report; do not let it skew a total silently.
- **#11 bonus rules escalate** (tiers, per-product weighting, partial periods, clawbacks). Ship the
  simplest model, iterate.
- **`party_id` is best-effort**, so cross-module customer identity may be incomplete. Fine for POS-scoped
  reports; do not promise a unified cross-vertical customer view on it yet.
- **The OMS tracker will drift** if these land without updating it. Phase 0 should also mark G2 ✅ and
  reconcile `commerce-backend-audit.md`, which the OMS plan already flags as stale on G3/G5.
