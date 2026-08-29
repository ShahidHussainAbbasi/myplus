# U8 — dispensing loose against a prescription

**Status: FIXED, gate outstanding.** Branch: `feature/pack-loose-selling`.
Follows the U0–U7 programme. Raised by an end-to-end review, not by the plan.

---

## 1. What the review got wrong, and what it found instead

The review reported *"a pharmacy cannot dispense loose"*. **That was wrong.** Dispensing is not a separate
cart: a prescription is *written* on the pharmacy screen, but the sale goes through the ordinary sale screen,
and `dispensePrescription(invoiceNo)` runs after `addSell` reading `window.data` — the same cart U3 works on.
**A pharmacist could already dispense fifteen tablets loose.**

What the review found instead is worse, and it is in the clinical record rather than the till.

## 2. ⚠ The defect: a controlled register under-recording what left the counter

```js
// before
var items = (window.data || []).map(function (d) {
    return { productId: Number(d.productId), quantity: Number(d.quantity) || 0 };
});
```

`d.quantity` on a **loose** line is **packs** — `1.5` for fifteen tablets. And in `DispenseService`:

```java
int give = Math.min(room, line.getQuantity());   // int
```

So:

| | |
|---|---|
| prescribed | **15 tablets** |
| dispensed at the counter | **15 tablets** — stock falls 1.5 packs, the customer pays for 15 |
| recorded against the script | **1** |
| apparently still owed | **14** |
| consequence | the prescription stays OPEN and **a repeat dispense is permitted** |

**The stock was right and the money was right. Only the clinical record was wrong** — which for a controlled
substance is a register understating what left the counter by 93%, and an audit trail that invites a second
dispense of a script already filled.

*This is the U6 shape a third time: the payload correct, the other side wrong. Here the "other side" is a
regulatory record rather than a shelf.*

## 3. The fix

A dispense is recorded **in pieces**, because a prescription is written in pieces — `soldQuantity` is exactly
what the patient received, in the unit the script uses.

```js
var pieces = (soldUnit === 'LOOSE' && soldQuantity > 0) ? soldQuantity : quantity;
```

No server change: `DispenseService` already caps at what the script can account for, and now receives a
number in the same unit the script is written in.

## 4. ⚠ What is deliberately NOT fixed — and why it is a question, not an omission

**Two packs of ten against a 15-tablet script still record `2`, not `20`.**

That mismatch **predates loose selling entirely** and turns on something only the shop can answer: *is
`rxQty` written in tablets or in packs?* Every clinical convention says tablets — a script reads "30 tablets,
1 BD × 15 days" — and the screen's own `dosage` / `frequency` / `duration` fields say the same. But:

* for a product with **no pack size** — which is most of the catalogue and every product before U1 — pieces
  and packs are the same number and nothing is ambiguous;
* for a **divisible** product it diverges, and changing it would rewrite how every pack dispense is recorded.

**Guessing would corrupt the register in the opposite direction** — over-recording a dispense is as wrong as
under-recording one, and harder to spot. So the loose case (created by this programme, and unambiguous) is
fixed now, and the pack case is raised for a decision.

> **The question for the shop:** when your pharmacist writes a quantity on a prescription, is that a count of
> tablets or a count of packs? If tablets, the pack case needs the same conversion and the cart line needs to
> carry `packSize` to do it.

## 5. Gate — to add to `sell-loose.cy.js` or its own spec

1. ⭐ **dispense 15 tablets against a 15-tablet script → the script closes.** `dispensedQuantity` is 15, not 1,
   and the prescription's status is complete.
2. ⭐ **a second dispense against that script is refused or records nothing** — the repeat this defect allowed.
3. **a partial loose dispense** — 5 tablets against 15 leaves 10 owed, not 14.
4. **a pack dispense is unchanged** — the regression that protects §4's undecided case.
5. **an indivisible product is unchanged** — pieces and packs are the same number.

## 6. Why this was not caught by U1–U7

Every gate in the programme asserted the **till**: money, stock, receipt, cart, shelf. None of them looked at
what a *different subsystem* recorded about the same sale. The dispense record is written by a second call,
after `addSell` succeeds, by a screen that was never in the loose feature's scope.

*A feature is finished when everything that reads its output is right — not when everything that writes it
is.*
