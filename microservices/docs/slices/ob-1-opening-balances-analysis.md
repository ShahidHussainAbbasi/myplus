# OB-1 — dues that existed before the shop started using MaxTheService: analysis

**Status:** ANALYSIS, shared for review. No design, no code.
**Question asked (2026-09-05):** *"how to add or adjust due of customer or supplier if we have some dues
before registration?"*

Live figures read from the Docker MySQL on 2026-09-05. Every count is a real query.

---

## 1. The problem, in one sentence

**A shop that switches to MaxTheService on a Tuesday already has money owed to it on the Monday, and there is
nowhere to put it.**

Every customer balance and every supplier balance in the system today is the arithmetic of documents *this
system recorded*. A shop's real opening position — the ledger book it is replacing — has no way in.

---

## 2. Why "just type it into the due field" cannot work

This is the part that decides the whole slice, and it is not obvious from the screen.

**`Customer.dueAmount` is DERIVED, not stored.** Every sale and every receipt calls `recomputeDue()`:

```java
BigDecimal sumDue = customerHistoryRepo.sumDueByCustomer(customer.getCustomerId());
BigDecimal owed   = sumDue.negate();          // floored at 0
customer.setDueAmount(owed);                  // ← overwrites whatever was there
```

The vendor side is the same shape — `recomputePayable()` sums `purchase.due_amount` and overwrites
`vender.due_amount`.

> **So a figure typed directly into either column survives exactly until that party's next transaction, and
> then vanishes — silently, with no error, at the moment the shop is most likely to trust it.**

That also rules out a one-off SQL update, an import that writes the column, and an "adjust balance" screen
that sets it. The number is not a fact the system keeps; it is an answer the system recalculates.

⚠ The code already knows this and protects it in two places — `CustomerController` and `VenderController`
both re-stamp the existing due when a profile is edited, with the comment *"a profile edit must not wipe the
payable."* Those guards are evidence of the trap, not a way around it.

---

## 3. What that leaves: the opening balance must be a DOCUMENT

If the balance is derived from documents, an opening balance has to be a document. That is also the correct
accounting answer, not a workaround — an opening receivable is a real claim and needs a real record behind it:
a date, an amount, a reference, and something the customer can be shown when they dispute it.

**And it must be a document the existing machinery already understands**, because the balance is not the only
thing derived from it:

| Reads the invoice headers, not the column | Consequence if the opening balance is not a document |
|---|---|
| `recomputeDue` / `recomputePayable` | the balance itself disappears |
| Receive Payment (FIFO allocation) | a payment cannot be allocated against the old debt |
| Customer statement | the opening amount never appears on it |
| Aging | old money shows as current, or not at all |
| Installment plan sync | plan restatement disagrees with the invoice |

A column-only figure would show a number on the customer card that **no other screen in the product agrees
with**. That is worse than showing nothing.

---

## 4. Measured: what is out there today

| | |
|---|---|
| customers | **2,620** |
| **customers with a balance owed** | **950** |
| vendors | 128 |
| **vendors with a balance owed** | **55** |

⚠ **Every one of those balances came from documents this system recorded.** None is an opening balance,
because there is no way to enter one. A shop that migrated with money on its books has that money missing
from the product today, or has faked it by entering old invoices as if they were new sales — which posts
revenue and tax in the wrong period, and is the failure mode this slice exists to prevent.

---

## 5. The accounting half, which is where this gets dangerous

An opening receivable is a **debit to AR (1100)**. Double entry demands a credit somewhere. There are only
three candidates and only one is right:

| Credit to | Verdict |
|---|---|
| **4000 Sales** | ❌ **wrong, and damaging.** It books last year's trade as this month's revenue — inflating the P&L, and the tax register with it |
| 3100 Retained Earnings | ❌ close, but it is the accumulation account; writing to it directly makes the opening entry indistinguishable from real prior profit |
| **3000 Owner's Equity** | ✅ this is what an opening balance *is* — the owner's stake in the business as it stood on day one |

The chart already carries **3000 Owner's Equity** and **3100 Retained Earnings**, so nothing new is needed.

⚠ **The trial balance is the gate.** Opening AR and opening AP both post against equity, so the sum must
still be zero after a migration. `project_gl_outbox_drops_new_fields` records what happens when a new posting
path is added and one of the five places is missed: the field vanishes and the books drift silently. This
slice adds a posting path, so that risk is live.

⚠ **`pos.installment.markupEnabled` is a precedent worth reading.** It is switched off with the note that
charging a markup *"makes the difference finance income rather than sales, which needs its own account before
it can be booked correctly."* The same discipline applies here: get the account right before the feature, not
after.

---

## 6. What I would build

| | Work | Why |
|---|---|---|
| **1a** | **An opening-balance DOCUMENT per party** — an invoice-shaped `CustomerHistory` (and a bill-shaped `Purchase`) marked as an opening balance, dated the shop's cutover date, with no lines | §2/§3: it is the only shape the balance, the statement, the aging and the allocator all already read |
| **1b** | **Post it Dr AR / Cr Owner's Equity** (and Cr AP / Dr Owner's Equity for suppliers) | §5 — never through Sales |
| **1c** | **A cutover date, set once per tenant.** Every opening document carries it, and it is what tells an opening balance from a real sale for ever afterwards | without it, nobody can answer "is this figure ours or theirs?" a year later |
| **1d** | **Bulk entry — the CSV importer, not a form.** 950 customers is not a screen | I1/I2 already ship a per-grid importer; this is one more entity, not a new mechanism |
| **1e** | *(follow-on)* **a reconciliation report**: total opening AR entered vs the shop's own closing figure from its old system | a migration nobody can check is a migration nobody should trust |

---

## 7. Performance and security

**Performance.** One document per party with a balance, written once at cutover. Nothing on the sale path.
The importer is the existing batch path.

**Security.** ⚠ **This writes to the general ledger, so it is not an ordinary data entry.** It should be
`ADMIN_PRIVILEGE` at least — consistent with `project_method_authz`'s rule that money-safe destructive and
policy operations are admin-tier — and arguably owner-only. An opening balance is also the easiest possible
place to hide a fabricated receivable, which is a second reason it wants an audit record naming who entered
it. E4's control-plane trail exists and this is the kind of write it was built for.

---

## 8. Questions — ANSWERED by the owner, 2026-09-05

All five recommendations approved, four with added controls, one refined. Recorded here in the owner's terms;
the design implements these, not my original wording.

| Q | Ruling | The refinement that changes the design |
|---|---|---|
| **Q1** party or invoice | **both** | invoice-level is the **preferred** method whenever legacy detail exists — it is the only one that preserves aging, allocation and disputes. A summary balance must be **labelled** as such and must not pretend to 30/60/90 aging |
| **Q2** edit or reverse | **reverse** | ⚠ **a PARTIALLY PAID opening document may not be reversed** — a full reversal breaks the payment allocation. That case needs a controlled adjustment posting the NET correction |
| **Q3** cutover default | **none** | required before the first import · confirmed twice · **locked after the first posting** · reopened only by an audited workflow |
| **Q4** credit limit | **yes** | only the **unpaid remainder** of **posted, un-reversed** opening AR. Never from a typed card figure |
| **Q5** who enters | **the tenant** | operator provides template, validation and time-limited audited assistance — never routine posting |

### Five controls the owner added, and none of them is optional

1. **Migration is a BOUNDED PROCESS, not a screen that stays open for ever.**
   `NOT_STARTED → DRAFT → VALIDATED → POSTED → RECONCILIATION_PENDING → RECONCILED → LOCKED`.
   After LOCKED: no imports, no edits, corrections only through reversal or adjustment.
2. **Reconciliation before completion.** Legacy AR aging total vs opening AR total; the same for AP; record
   counts and sample references. *"A migration is not complete because the CSV imported successfully. It is
   complete when control totals reconcile and a responsible owner signs off."*
3. **Say what is NOT migrated.** Cash, bank, inventory valuation, loans, tax, fixed assets and equity are not
   in this. The product must state that plainly or a shop will believe its whole books came across.
4. **A batch id and an idempotency key.** A double-click or a timed-out retry must return the first batch,
   never a second set of 950 documents.
5. **Four dates, kept apart:** legacy invoice date · legacy due date · posting date · tenant cutover date.
   One date used for all four is how aging silently becomes wrong.

---

## 9. What the rulings mean for the build — and one thing that now comes free

**Q4 needs no new code.** Credit exposure sums `Customer.dueAmount` through
`CreditStandingService.groupExposure` → `sumDueByCreditAccount`, and `recomputeDue()` derives that column
from the invoice headers. **An opening balance entered as a document is therefore in the credit exposure the
moment it posts**, with the shared-pool rule and the warn/block policy already applied to it.

That is the §3 argument proving itself: the document shape is not merely the correct one, it is the one where
the balance, the statement, the aging, the FIFO allocator and the credit limit all already work. Every
alternative would have needed five separate integrations, and would have got some of them wrong.

**Q2's partial-payment rule is the one genuinely new mechanism.** Everything else composes from what exists —
period lock, `idempotency_record`, the CSV importer, the GL outbox. A net-correction adjustment on a
part-settled document does not, and it is where this slice's risk sits.

---

## 10. Scope: this is not one slice

The rulings describe a migration SUBSYSTEM — a seven-state lifecycle, two entry modes, reconciliation
reporting, dual approval, a reversal path and an adjustment path. Shipping that as one change would be
unreviewable, and it would violate the standing rule that a slice finishes one thing end to end.

Proposed phasing, each phase green before the next:

| Phase | Delivers | Why this boundary |
|---|---|---|
| **OB-1** | cutover date (set, confirm, lock) · **summary** opening balance per party · Dr AR / Cr 3000 · appears in balance, statement, aging, credit exposure · reversal of an UNPAID document · audit record | the smallest thing that is genuinely end to end: a shop with a notebook can migrate and the books balance |
| **OB-2** | **invoice-level** import via the CSV path · legacy invoice/due dates · true aging buckets · batch id + idempotency | the distributor case. Needs OB-1's document and posting to exist first |
| **OB-3** | reconciliation report · control totals vs legacy · owner sign-off · the LOCKED state | cannot be built before there is something to reconcile |
| **OB-4** | adjustment workflow for a PARTIALLY PAID opening document | the hardest case, and the rarest. Deliberately last, with OB-1's reversal refusing it explicitly in the meantime |

⚠ **OB-1 must refuse what it cannot do**, rather than doing it approximately: a partially paid document
refuses reversal and names OB-4; a summary balance is labelled as having no invoice-level aging. Control 3
says the same thing about the balance sheet, and this is that rule applied inside the slice.

---

## 11. Original questions, as asked

**Q1 — one document per party, or one line per outstanding invoice?**
*Recommendation: one document per party, with the option of several.* A shop that knows only "Imran owes
45,000" can enter that; a shop with its old invoice list can enter one per invoice and keep the aging
accurate. Forcing per-invoice would block the first shop entirely; forcing a single total would throw away
information the second shop already has.

**Q2 — should an opening balance be editable after entry, or reversed and re-entered?**
*Recommendation: reversed, not edited.* It is a posted document. Editing one silently changes a prior-period
ledger entry, which is what period close exists to prevent — and the void/reverse path already exists.

**Q3 — what is the cutover date's default?**
*Recommendation: none. Make the operator state it.* A defaulted cutover date is a wrong cutover date on every
tenant that did not notice the field, and it is the one value the entire migration is anchored to.

**Q4 — do opening balances count towards the credit limit?**
*Recommendation: yes.* It is money the customer owes. A limit that ignored the largest part of a migrated
customer's balance would be a limit that does not limit — and `pos.sale.creditLimitPolicy` is already
shipped, so this is not a new mechanism, only a question of whether the figure feeds it.

**Q5 — does this belong to the shop, or to the platform operator?**
*Recommendation: the shop's owner/admin, not the operator.* An operator does not know what a customer owed
before migration, and E5's support session is deliberately bounded. This is the tenant's own data entry —
gated, audited, but theirs.
