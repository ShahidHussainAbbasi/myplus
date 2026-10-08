# FP-6 — retire business as the payables source

**Status:** FP-6a BUILT (2026-10-04/05; user: "28 days", "everything fixed automatically, no manual action, no issue on production"). FP-6b/6c follow once tenants reach 28 clean days. Programme: [`../finance-payables-subledger-design.md`](../finance-payables-subledger-design.md) §4:
*"`recomputePayable` reads the projection; remove dual paths after N weeks at zero diff; flag removed per tenant only
after a clean reconciliation history."*

## 1. Review (2026-10-04, measured from the databases, not inferred)
**There is no reconciliation history.** finance's `GET /payables/reconciliation` compares the subledger with GL 2000 for
one tenant, on request, and stores nothing. So the FP-6 gate ("a clean reconciliation history") cannot be proven by
anything today. That is the first thing to build.

Snapshot. B = business Σ purchase.due_amount, stored as **paid − bill**, so −B is what is owed. F_p = finance open
PURCHASE docs. F_all = all open docs, bills included. G = GL 2000. Clean means −B = F_p **and** F_all = G.

| Org | −B ⇄ F_p | F_all ⇄ G | |
|---|---|---|---|
| 13 owner.business | 16,180 = 16,180 | 20,760 = 20,760 | clean |
| 44 | 20,000 = 20,000 | 20,000 = 20,000 | clean |
| 46 owner.lifecycle | 3,855,300 = 3,855,300 | equal | clean |
| 149 owner.payables | 100 = 100 | 150 = 150 | clean |
| 6 demo | −1,259.50 vs 2,920.00 | 2,920.00 vs 14,070.50 | **both off** |
| 20 marketplace | 270,000 = 270,000 | 270,000 vs 288,000 | **GL off 18,000** |
| 41 Shahzad Mobile | −25,200 = −25,200 | −25,200 vs 288,000 | **GL off 313,200** |

Orgs 20 and 41 are the known FP-4b-GL finding (the same 288,000 purchase journal of 23 Aug in both); org 6 is the known
demo drift. Nothing new is broken; but those three tenants can never qualify for FP-6 until corrected.

## 2. Design — three slices
| Slice | What | Gate |
|---|---|---|
| **FP-6a** reconciliation history | finance records, **daily per tenant** (scheduled, plus on demand), one row: B (via business internal read), F_p, F_all, G, both differences, clean yes/no. Append-only, `UNIQUE(org, day)`. Operator panel: the last 90 days per tenant + "clean days in a row" | a day with a forced difference records unclean; history survives restarts; one row per tenant per day however often it runs |
| **FP-6b** projection becomes the truth | for a FINANCE tenant with ≥ N clean days in a row: business's `recomputePayable` / supplier due read finance's projection (stamped balances, FP-4c), no longer its own sum | screens and specs give the same figures as before; a tenant below N is refused with the count |
| **FP-6c** remove the dual path | business stops maintaining its own payable for those tenants; the switch can no longer go back for them (one-way, recorded) | reconciliation stays clean for 7 more days; then the old code path is deleted when **no** tenant uses it |

**Recommended N = 28 days** (4 weeks, which covers a month-end close). Configurable per platform, not per tenant.

## 3. Decisions (user, 2026-10-04)
1. FP-6a: build it now. **Built.**
2. N = **28** consecutive clean days.
3. Dirty tenants: **fixed automatically**, with no manual action, the same on dev and production. Approach chosen
   (user: "choose the best approach"): trace the causes, guard the live one, and align the ledger only once the
   documents agree, posting to a named difference account (2990), as QuickBooks' Reconciliation Discrepancies and Odoo's
   suspense account do.

## 4. FP-6a — as built (2026-10-05)
**Corrections to §1 (Rule 0):**
- B must count **supplier** purchases only. 468 org-6 purchases have no supplier: cash purchases, owed to nobody.
  Counted that way, business and finance's documents agree for **every** tenant.
- finance's `sumOpen` ignored overpaid (SETTLED, negative) documents, i.e. advances. The reconciliation now uses
  `sumNet` (all non-void).

**Causes found (all historical, except the one now guarded):**
- org 41: one purchase journal of 23 Aug posted 10× its bill (320,000 / 288,000 owed for a 32,000 bill paid in full).
- org 20: 20 malformed purchases (no total, 900 tax) posted Cr 2000. These stopped on 20 Sep.
- org 6 (demo.business, continuous test traffic): among others, **no-supplier purchases left part-unpaid on a taxed
  bill** (200 typed on a 220 bill) posted the tax to 2000 "owed to nobody". This was live; it is now guarded.

**Built:**
- **Guard** (`PurchaseService.cashPurchasePaidInFull`): a purchase with no supplier is a cash purchase. paid = the bill,
  tax included, on add and edit. With a supplier, nothing changes.
- **Daily check** (business `PayablesReconciliationService`): 03:30 Karachi and 10 min after every start.
  - Measure, then re-send documents if they differ. Only once they agree, finance aligns GL 2000.
  - finance `PayablesLedgerAlignment` computes the amount itself. It posts one journal against **2990 Payables
    Reconciliation Difference**, idempotent per **ledger state** (account 2000's newest line) via `gl_processed_event`. The gate showed a per-day key left a second same-day difference standing until tomorrow; fixed.
- **History**: V78 `payables_recon_day`, one row per tenant per day.
  - A day that needed a repair stays unclean, even after a later clean run.
  - A day that could not be checked is never clean. A missing day breaks the streak.
- **Operator panel**: "Automatic check (daily)", with the streak, 28 required, and the last 7 days.

**Proven live (2026-10-05):**
- The automatic post-start run, with no one triggering it, recorded 8 tenants. 5 were clean; orgs 6/20/41 were
  aligned by exactly 11,150.50 / 18,000 / 313,200.
- Afterwards GL 2000 = the supplier ledger for **every** tenant, and each tenant's books still balance.

**Open:** expense-bill documents are trusted as reported by expense-service; there is no expense-side parity in the check yet.
