# ONB-2 — assigning and reassigning a business type: analysis

**Status:** ANALYSIS, shared for review. No design, no code.
**Raised:** 2026-09-02, from three reports that are one defect:
*"logged in as POS/retail and I see pharmacy things"* · *"logged in as marketplace and I see mobile-shop
things"* · *"Shahzad was created as retail and is now a pharmacy — how do I switch him?"*
**Follows:** [`onb-1-business-type-at-onboarding.md`](onb-1-business-type-at-onboarding.md) — which fixed this
for **new** tenants only.

Live data read from the Docker MySQL (`myplus-mysql`, `myplusdb_auth`) on 2026-09-02.

---

## 1. One cause, three symptoms

```
39 of 41 organizations resolve to "everything on"

  no org.shape row   37   →  Shape.byCode(null) = GENERAL
  org.shape=general   2   →  GENERAL
  a real shape        2
```

`Shape.GENERAL`'s preset is **every capability**, and `businessDashboard.html` has **34**
`[data-capability]` sections. So a tenant with no shape — or on `general` — is shown every vertical's screens
at once: a POS counter sees dispensing and expiry, a marketplace back-office sees IMEI and installments.

Nothing is broken and nothing leaks. **These tenants were never asked what they are**, and "everything" is the
documented fallback for not knowing.

### The accounts you are logging in with

| Login | Org | Shape | What you see |
|---|---|---|---|
| `demo.business@` | 6 | **none** | everything — incl. pharmacy |
| `owner.business@` | 13 | **general** | everything — incl. pharmacy |
| `demo.marketplace@` | 23 | **none** | everything — incl. serial/IMEI, installments |
| `owner.pharma@` | 15 | **none** | everything |
| `owner.pesticide@` | 45 | **general** | everything — the original report |
| `owner.marketplace@` | 20 | `distribution` | ✅ correctly narrowed |
| `owner.mobile@` | 44 | `retail` | ✅ correctly narrowed |

The two that behave are the two with a real shape. That is the whole finding.

### ⚠ `general` is as bad as no shape, and that is now a problem rather than a safety net

C4 made `GENERAL` mean *everything* deliberately: it is what kept the capability rollout inert, because every
existing tenant resolved to it and nothing changed on the deploy. That was correct then.

It is now the thing producing these reports, and it is **indistinguishable from "never asked"** in behaviour
while being distinguishable in the data. A tenant on `general` looks like a deliberate choice and behaves like
an unanswered question. §4 proposes what to do about it.

---

## 2. A flaw in what I shipped yesterday, which I should own

`SetupDataLoader.ensureShape` (ONB-1) writes a fixture's shape **only when no row exists**:

```java
if (orgSettingRepository.findByOrganizationIdAndSettingKey(orgId, key).isPresent()) return;
```

I wrote that as *"self-healing without being bossy"*. The consequence, visible in the table above:
**`owner.pesticide@` is on `general`, so the seeder skips it and will never correct it to `pharmacy`** — the
exact tenant whose wrong screens started this whole thread.

The reasoning was wrong for this case. These are **fixtures**, defined by the loader; `SetupDataLoader`'s own
contract is *"self-healing on every startup so a restart always yields a working login"*. A fixture's shape is
part of its definition, not a user preference to be preserved. It should be enforced, not skipped.

---

## 3. What is missing to fix the other 39

The control exists — ONB-1 put a **Business type** selector on the tenant detail, `ROLE_ADMIN`, any tenant,
with a confirmation. What is missing is everything that makes using it on 39 tenants practical:

| | Gap | Why it matters at 39 tenants |
|---|---|---|
| **G1** | The tenant row shows plan, suspension, lapsed trial and *"no business type"* — but **not the shape** | An operator cannot see that org 20 is distribution and org 44 is retail without opening each one. The list cannot be scanned |
| **G2** | No way to **filter** to the ones needing attention | The worklist has to be found by eye, and there is no way to know when it is finished |
| **G3** | `general` is not flagged | The *"no business type"* badge reads the raw `shapeSet`, so the two tenants deliberately parked on `general` look done. They are not |
| **G4** | One tenant at a time | 39 tenants × (search, open, select, confirm, back) is an afternoon. Most of them are POS shops that want the same answer |

---

## 4. Switching a tenant that already has history — the Shahzad case

Reassigning is not the same problem as assigning, and this is where the real risk is. Retail → pharmacy, for a
tenant that has been trading:

| Capability | Retail | Pharmacy | Effect on existing data |
|---|---|---|---|
| `serialTracking` | on | **off** | 🔴 **his IMEI handsets become unsellable** |
| `installments` | on | **off** | 🟠 open plans still collectable, but invisible |
| `batchTracking` / `expiryTracking` / `fefoAllocation` | off | **on** | 🟠 existing stock has no batch or expiry |
| `conditionGrading` | on | off | used/refurbished grading disappears |
| `looseSelling` | off | on | nothing to migrate |

**🔴 The blocking one, verified in code.** `SerialUnitService.validateForSale` calls
`assertEnabled(SERIAL_TRACKING)` whenever a product carries `requires_serial = true`. Every handset in
Shahzad's catalogue has that flag from SER-1. The moment he becomes a pharmacy, **selling any of them is
refused** — *"This is not switched on for your business."* — and the message does not say which product or why.

C6 anticipated it and left the way out open: **clearing** a product's serial flag is permitted even without the
capability, precisely so nobody is stranded. But it is per-product, and nothing tells him which products need
it.

**🟠 The quiet one, also verified.** `InstallmentController`'s seven endpoints carry **no capability check**, so
collecting, reminders and repossession keep working after the capability goes off — correct, a customer's debt
does not evaporate because the shop changed trade. But `BusinessDashboardController:179` gates the
**installmentsDue tile** on the capability, so **the money owed to him disappears from his dashboard while the
debt remains**. He can still collect if he navigates there; nothing will remind him to.

> **The rule that is wrong, and it is a shipped defect rather than new work:** a capability should stop a tenant
> **taking on new** commitments. It should never stop them **seeing existing ones**. The same mistake is
> available on every future tile.

**🟠 The silent one.** Pharmacy turns on FEFO and expiry over stock that has neither, so nearest-expiry
allocation has nothing to sort on, and per `project_stock_sellable_vs_onhand` expired/held rows inflate on-hand
and produce false *"Insufficient stock"*. No error names the cause.

---

## 5. What I would build

| | Work | Fixes |
|---|---|---|
| **ONB-2a** | **Shape badge on the tenant row**, beside the plan badge | G1 |
| **ONB-2b** | **Filter: "Needs a business type"** — counts `general` **and** unset | G2, G3 |
| **ONB-2c** | **Fixture shapes enforced**, not skipped, in `SetupDataLoader` | §2 |
| **ONB-2d** | **Bulk assign** — select several tenants, set one type, one confirmation naming the count | G4 |
| **ONB-2e** | **The switch preview counts DATA, not just switches** — *"14 products require a serial and will not be sellable"*, *"3 open plans, ₨84,000 outstanding"* | §4, the 🔴 |
| **ONB-2f** | **A cleanup list after the switch** — the affected products, with bulk-clear | §4, the 🔴 |
| **ONB-2g** | **Reads stop being capability-gated; only writes are** — starting with `installmentsDue` | §4, the 🟠 defect |

**ONB-2g is a defect fix and I would do it whether or not the rest is approved.**

---

## 6. Questions before I design

**Q1 — should `general` remain selectable?** It is the honest answer for a genuinely general trader, and it is
also how 39 tenants ended up showing everything. *Recommendation: keep it, but rename it on screen to
**"General — show every feature"** so choosing it is visibly a decision, and count it in the "needs attention"
filter.* Removing it would leave no option for a business that really is general.

**Q2 — bulk assign (ONB-2d): worth it?** *Recommendation: yes, but last.* 2a–2c make the 39 visible and
correctable; bulk makes it quick. If most of your 39 are POS shops, bulk turns an afternoon into a minute — but
it is also the most dangerous control in the console, so it should be built after the preview in 2e exists.

**Q3 — for Shahzad specifically, do you want the switch BLOCKED while data conflicts exist, or allowed with
warnings?** *Recommendation: allowed with warnings.* He genuinely is changing trade; the product's job is to
make it safe and visible, not to refuse it. Blocking would leave him with no path at all.
