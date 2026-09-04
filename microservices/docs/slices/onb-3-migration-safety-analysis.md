# ONB-3 — making a business-type change safe: analysis

**Status:** ANALYSIS, shared for review. No design, no code.
**Follows:** [`onb-2-assign-business-type-design.md`](onb-2-assign-business-type-design.md) (✅ green) ·
[`onb-3-mobile-shop-requirements-analysis.md`](onb-3-mobile-shop-requirements-analysis.md) §4, which sketched
this and is superseded on the numbers by §2 below.
**Decisions already taken** (owner): reassignment is **allowed with warnings**, never blocked · bulk assign
comes **last**, after the preview exists.

Live figures read from the Docker MySQL on 2026-09-03. Every count below is a real query, not an estimate —
the earlier sketch used illustrative numbers and two of them were an order of magnitude low.

---

## 1. The problem, in one sentence

**Changing a business type is instant. Changing trade is not.** ONB-1 and ONB-2 made the switch easy and
visible; nothing yet tells an operator what it will cost, or helps them clean up afterwards.

---

## 2. What a switch actually costs — measured

### Shahzad (org 44, `retail` → `pharmacy`)

| | |
|---|---|
| products | **304** |
| **require a serial** (`requires_serial = 1`) | **19** |
| track a batch | 0 |

`SerialUnitService.validateForSale` calls `assertEnabled(SERIAL_TRACKING)` for any product with the flag. Switch
him to pharmacy and **19 products become unsellable** — refused at the till with *"This is not switched on for
your business"*, which names neither the product nor the reason. His remaining handset stock is frozen until
somebody clears 19 flags one at a time, and nothing tells him which 19.

### The tenant where this would have been expensive (org 13, `owner.business@`)

| | |
|---|---|
| active installment plans | **206** |
| unpaid schedule rows | **979** |
| **outstanding** | **₨ 7,716,000** |

That is the money that used to vanish from the dashboard when a capability was withdrawn — the defect ONB-2
fixed. Worth recording the real figure, because "the tile disappears" and "₨7.7 million stops being chased"
are the same sentence and only one of them gets acted on.

**And the good news for the preview:** the outstanding figure is **computable today**, one aggregate over
`installment` joined to `installment_plan`. No new column, no new table:

```sql
SUM(i.amount - COALESCE(i.paid_amount,0))
  WHERE p.status='ACTIVE' AND i.amount > COALESCE(i.paid_amount,0)
```

The earlier design doc assumed this would need new schema. It does not.

### The third cost, which produces no error at all

Switching **into** a batch-tracking shape turns on FEFO and expiry over stock that has neither — org 44 has
**0** products tracking a batch. Nearest-expiry allocation then has nothing to sort on, and per
`project_stock_sellable_vs_onhand` expired/held rows inflate on-hand and produce false *"Insufficient stock"*.
Nothing names the cause.

---

## 3. What is genuinely irreversible — and it is not what you would expect

Switching back restores **capabilities** instantly and **deletes nothing**: products keep `requires_serial`, the
serial register keeps every IMEI, installment plans keep every schedule row.

**What does not come back is the tenant's own configuration.** `applyShape` clears every `org.cap.*` override,
and switching back applies the *other shape's preset* — not the switches the owner personally chose. For a
tenant that has tuned its setup, that is the real loss, and it is **silent**: nothing records what was cleared,
so nobody can even say what was lost.

> This is the one part of a business-type change that cannot be undone today, and it is invisible. Everything
> the operator can see is reversible; the only irreversible thing is the thing nothing shows them.

---

## 4. What I would build

| | Work | Why |
|---|---|---|
| **3a** | **The preview counts DATA.** *"19 products require a serial and will not be sellable"* · *"206 open plans, ₨7,716,000 outstanding, still collectable but leaving the dashboard"* · *"0 products have a batch — nearest-expiry allocation will have nothing to sort on"* | §2 — every line is a query that already works |
| **3b** | **Record the previous shape and overrides before clearing.** A small `org_shape_history` row: org, when, who, previous shape, the cleared overrides, reason | §3 — the only thing that makes a switch reversible, and what bulk assign needs |
| **3c** | **A post-switch cleanup list** — the affected products, with **bulk clear**. C6 already permits clearing a product policy *without* the capability, precisely so nobody is stranded; what is missing is finding them | §2 — turns a warning into an action |
| **3d** | *(follow-on)* an **Undo** control reading 3b | needs its own confirmation; see Q1 |

**3a is the cheap half and delivers most of the value** — an operator who sees "19 products will stop selling"
before pressing the button will often decide differently.

---

## 5. Where the history lives

A new `org_shape_history` table in **auth-service**, beside `org_entitlement`:

```
id · organization_id · changed_at · changed_by · previous_shape · previous_overrides (JSON) · reason
```

**Why a table and not the audit log:** E4 does not exist yet, and this is not only an audit record — it is the
**input to an undo**. An audit trail answers *what happened*; this has to answer *what to put back*. When E4
lands it reads this rather than replacing it.

**Why JSON for the overrides**, against this codebase's usual habit: the payload is *"the set of key/value rows
that existed at an instant"* — it is never queried by key, never joined, and never aggregated. It is a
snapshot, which is the one shape JSON is genuinely right for. `SAAS-BUILD-STANDARDS.md` §4a's objection to JSON
was about entitlement *status, dates and source* — facts that must be queryable. This is not that.

---

## 6. Performance and security

**Performance.** The preview adds three counts to a screen an operator opens deliberately, none on any hot
path. The history write is one insert per shape change. A tenant that never changes its type pays nothing.

**Security.** No new authority: `ROLE_ADMIN` for the operator path, owner/admin for a tenant's own. The cleanup
list is scoped to the tenant being viewed. **The bulk clear can only clear** — it can never set a policy flag,
which keeps C6's rule (a tenant without the capability may not set the policy, only remove it).

---

## 7. Questions before I design

**Q1 — is Undo a button in this slice, or just a record?** *Recommendation: record now, button later.* 3b makes
the switch reversible in principle and is what bulk assign needs; an Undo control needs its own confirmation
("this will restore 11 switches the owner changed since"), and shipping the record first means nothing is lost
in the meantime.

**Q2 — should the cleanup list be offered automatically after a switch, or found later?** *Recommendation:
shown immediately, and reachable afterwards from the tenant detail.* An operator who switches and walks away
should not be the only route to 19 unsellable products.

**Q3 — should the preview block on a threshold?** For instance, refusing without a typed confirmation when more
than N products would stop selling. *Recommendation: no.* You already ruled that reassignment is allowed with
warnings; a threshold is a block wearing a different hat, and 19 versus 20 is not where the judgement lives.
The counts are the warning.

**Q4 — does this apply to the tenant's own Configuration screen too, or only the operator console?** *
Recommendation: both.* The owner has the same power and less context; if anything they need the counts more.
The preview endpoint already exists on both paths, so it is the same work twice at almost no cost.
