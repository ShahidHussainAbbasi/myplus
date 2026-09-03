# ONB-3 — does Shahzad need his own module? And the two elaborations

**Status:** ANALYSIS, shared for review. No design, no code.
**Raised:** 2026-09-03 — *"there should be another module of mobile shop as per requirements from Shahzad
Mobile Shop are not matching with the POS/Retail."*
**Standing rulings applied:** [`vertical-profile-any-business-design.md`](../vertical-profile-any-business-design.md)
§3c (the Q1/Q2/Q3 test) · [`capability-platform-design.md`](../capability-platform-design.md) §4b (two axes).
**Follows:** [`onb-2-assign-business-type-analysis.md`](onb-2-assign-business-type-analysis.md).

**Decisions taken** (owner, 2026-09-03): keep `general` selectable, renamed **"General — show every feature"**,
counted in the needs-attention filter · bulk assign **last** · reassignment **allowed with warnings**.

---

## 1. Verdict on the module question

**No new module, and the five requirements are the evidence — not an opinion about architecture.**

The platform has a standing test for exactly this question. Applied to a mobile shop:

> **Q1 — does it need its own SERVICE?** Only if it owns data with its own lifecycle *and* its own integration
> surface. A mobile shop's data is products, purchases, sales, customers, stock. **No.**
>
> **Q2 — does it need its own DASHBOARD?** Only if its information architecture is genuinely different.
> Shahzad buys stock, sells it over a counter, takes payments, chases debtors — the same screens as any
> retailer. **No.**
>
> **Q3 — otherwise it is a PROFILE.** Which the design already names: *"Mobile shop is not a shape — it is
> RETAIL plus serial tracking, condition grading and installments."*

**Classifying the five requirements settles it.** Not one of them is about a mobile shop:

| # | Requirement | What it actually is | Scope |
|---|---|---|---|
| 1 | No batch # on purchase; serial ⇒ qty = 1 | **capability-driven form behaviour** | anyone with `serialTracking` on / `batchTracking` off |
| 2 | Invoice shows many zeros after the decimal | **a bug** | everyone |
| 3 | Duplicate entry on the sale form | **a bug** | everyone |
| 4 | Installment guarantors — CNIC, mobile, name, address | **new domain data** | anyone with `installments` |
| 5 | Show/hide column toggle on the product grid | **a general UI feature** | everyone |

Three are general, one is a form rule the capability system already knows how to express, one is a genuine new
feature scoped to *selling on terms* — not to *selling phones*. A jeweller, a furniture showroom and a bike
dealership all sell on installments and all want a guarantor.

**Building a "Mobile Shop" module would put three general bug fixes and one general feature behind a wall that
only Shahzad is on**, and the next customer who reports the duplicate-sale bug would be told it is a mobile-shop
feature. `if ("MOBILE".equals(type))` is `if (organizationId == 24)` one indirection away.

---

## 2. The five requirements, on evidence

### R1 — purchase form: no batch #, and a serial implies quantity 1 🟢 small

**Half of it is a missing attribute.** The purchase Batch # field is **not capability-gated**:

```html
<label for="purchaseBatchNo">Batch #</label>
<input id="purchaseBatchNo" name="stock.batchNo" .../>   <!-- no data-capability -->
```

Every tenant sees it, including one with `batchTracking` off. The dashboard uses `data-capability` in **34**
places and this field was simply missed. Adding `data-capability="batchTracking"` to the enclosing group is the
fix — and it needs a sweep, because if this one was missed others were.

**The other half is real, small work:** when a serial is entered, quantity is 1 by definition — one IMEI is one
handset. That belongs on the purchase path beside the existing `SerialUnitService.validateForPurchase`, gated on
`serialTracking`, and it is genuinely useful to every serial-tracking trade.

### R2 — invoice shows many zeros after the decimal 🟠 a bug, needs a diagnostic

`receipt.js` formats money through `toFixed(2)` in the paths I read, so this is a path that does **not**. Money
is `BigDecimal(19,2)` but **quantity is a float**, and a float rendered raw is exactly how
`1.0000000000000002` reaches a printed document.

I will not guess which field. **This needs one screenshot or one invoice number**, and then it is a small fix
in one formatter — plus the question of whether the same value is wrong elsewhere.

### R3 — duplicate entry on the sale form 🟠 a bug, and NOT the obvious one

Worth stating because it changes what to look for: **the obvious guards already exist.**

```js
beforeSend : function(){ $('#addSell').prop('disabled', true); },
complete   : function(){ $('#addSell').prop('disabled', false); },
```

and a `saleIdempotencyKey` that is deliberately **kept on failure so a retry dedups**, and retired only after a
committed sale.

So a plain double-click is already handled. That leaves: a retry after a timeout where the first request *did*
commit, two browser tabs, or a path that mints a fresh key when it should not. **This needs the actual duplicate
invoices** — their numbers and timestamps — before anybody writes code. Guessing at a concurrency bug produces a
fix that cannot be shown to work.

### R4 — installment guarantors 🟢 the one substantial build, and it is not mobile-specific

Nothing like it exists: a repo-wide grep for `guarantor` / `witness` returns only education's `Guardian` and a
customer `cnic` field. This is genuinely new domain data — name, CNIC, mobile, address, plus an existing
customer chosen from a dropdown as an alternative to typing one in.

**Scoped to the `installments` capability, not to a trade.** And it wants a policy switch rather than a new
capability: `pos.installment.requireGuarantor` in the settings catalog, because a shop selling a ₨5,000 phone on
terms may not want a guarantor while one selling a ₨200,000 bike certainly does. Capabilities answer *may this
tenant do this*; this is *how does this tenant do it*, which is a setting.

⚠ One design question it raises: a guarantor is a **person with a CNIC and an address** — which is what
`party-service` exists for. Reusing `Party` beats inventing a second person table, and it means a guarantor who
is also a customer is one record, not two. Worth deciding before building.

### R5 — column show/hide on the product grid 🟢 small, general

DataTables **Buttons is already loaded** (`dataTables.buttons.min.js`, `buttons.html5`, `buttons.print`) but
`buttons.colVis.js` is **not** in `jQExp/`. So this is: add the one vendor file, add `colvis` to the product
grid's button list, and persist the choice per user in `localStorage`.

Every register screen benefits, not only products — but starting with `tableProduct` as asked is right.

---

## 3. Elaboration — why bulk assign comes LAST

**What makes it the most dangerous control in the console.** Every other operator action affects one tenant and
is reversible. Bulk assign is neither:

* **It multiplies.** One mis-click applies to N businesses at once.
* **It destroys, by your own ruling.** A shape change re-applies the preset, which **clears every capability
  switch that tenant's owner had set**. Across 39 tenants that is 39 owners' deliberate configuration, gone in
  one action.
* **It has no natural undo.** Reverting means knowing each tenant's *previous* shape and *previous* overrides.
  Nothing records the second today.

**So the order is not politeness, it is dependency.** ONB-2e (the preview that counts consequences) has to
exist first, because bulk without it is a button that says *"change 39 businesses, effects unknown"*.

**What makes it safe enough to build, after that:**

| Safeguard | Why |
|---|---|
| **Only offer tenants with no real shape** (unset or `general`) | Never bulk-*re*assign. A tenant already on `pharmacy` has a decision behind it and a person who made it; changing it belongs on the per-tenant screen where the preview is |
| **Name the tenants, not just the count** | *"Change 39 businesses"* is a number; a list is a decision. Scrollable, with each name visible |
| **One reason, applied to all** | Same rule as every control-plane write, so E4 audits it |
| **Record each tenant's previous shape and overrides** | The only thing that makes the action reversible. Without it, "undo" is a support ticket per tenant |
| **Report per tenant, not once** | 39 writes will not all succeed; a single "Done" hides the three that did not |

**And the honest expectation:** most of your 39 are POS shops that want `retail`. Bulk turns an afternoon into a
minute *for that majority*. The pharmacies and distributors in the list should still be done individually —
which the "unset only" restriction naturally encourages.

---

## 4. Elaboration — why reassignment is ALLOWED, with warnings

**Blocking is the tempting answer and it is wrong**, for a reason worth stating plainly: Shahzad has 14
IMEI-tracked products. A rule that refuses the switch while data conflicts exist means **he can never become a
pharmacy** — the product would be telling a real business it is not allowed to change trade, and offering no
path at all. The conflicts are not an error; they are his history.

**What "with warnings" has to mean, concretely**, or it is just a shrug:

**Before — the preview counts DATA, not only switches.** Today it lists capabilities turning on and off. It must
also say:

> *14 products require a serial number and will not be sellable*
> *3 open installment plans, ₨84,000 outstanding, will stay collectable but leave the dashboard*
> *0 products have batch or expiry recorded — nearest-expiry allocation will have nothing to sort on*

Each line is a count the server already has, and each is a consequence he can weigh.

**After — a cleanup list, not just a warning.** *"These 14 products need their serial requirement cleared"*, with
a bulk-clear. C6 deliberately permits clearing a product policy **even without the capability**, precisely so
nobody is stranded — what is missing is finding the products. Without this, "unsellable stock" is a support call
and the warning was advice he could not act on.

### ⚠ The part that is genuinely irreversible, and it is not what you would expect

Switching back restores **capabilities** instantly — the shape is just a settings row, and nothing is deleted:
his products keep `requires_serial`, his serial register keeps every IMEI, his installment plans keep every
schedule.

**What does not come back is his own configuration.** The re-apply cleared every `org.cap.*` override he had
set; switching back re-applies the *retail* preset, not the switches he personally chose. For a tenant who has
tuned their setup, that is the real loss — and it is silent, because nothing records what was cleared.

**The mitigation is the same one bulk assign needs:** record the tenant's capability overrides before clearing
them. It makes reassignment genuinely reversible, it makes bulk assign safe, and it is the same small piece of
data in both cases.

---

## 5. What I would build, in order

| | Work | Why here |
|---|---|---|
| **1** | **R1a** — `data-capability="batchTracking"` on the purchase Batch # group, **plus a sweep** for other ungated fields | One attribute; and if one was missed, others were |
| **2** | **ONB-2g** — reads stop being capability-gated (`installmentsDue`) | A shipped defect: a capability must never hide money already owed |
| **3** | **ONB-2a/2b/2c** — shape badge, needs-attention filter, enforced fixture shapes | Makes the 39 visible and correctable |
| **4** | **ONB-2e + record-previous-overrides** | The preview that counts data, and the thing that makes reassignment reversible |
| **5** | **ONB-2f** — the post-switch cleanup list | Turns a warning into an action |
| **6** | **R5** — column toggle | Small, general, independently useful |
| **7** | **R1b** — serial implies qty 1 | Small, on the serial capability |
| **8** | **R4** — installment guarantors | The substantial one; needs the `Party` decision first |
| **9** | **ONB-2d** — bulk assign | Last, per §3 |
| **—** | **R2, R3** — the two bugs | **Blocked on evidence**: an invoice number, and the duplicate invoices with timestamps |

---

## 6. Questions

**Q1 — R4 guarantors: reuse `party-service`'s `Party`, or a table local to installments?** *Recommendation:
`Party`.* A guarantor is a person with a CNIC, a phone and an address, which is precisely what the shared party
master holds — and a guarantor who is also a customer should be one record. A local table is quicker now and is
the second person-store the platform said it would not build.

**Q2 — R2 and R3 need evidence before code.** Can you send one invoice showing the decimal problem, and the
invoice numbers plus rough timestamps of a duplicated sale? The duplicate especially: the obvious guards are
already in place, so without the real case any fix would be a guess dressed as a diagnosis.

**Q3 — should the "General" option be renamed everywhere it appears**, or only on the operator console? It shows
on the tenant's own Configuration screen too. *Recommendation: both* — an owner picking it should see the same
plain words the operator does.
