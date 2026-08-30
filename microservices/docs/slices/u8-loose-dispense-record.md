# U8 — dispensing loose against a prescription

**Status: DONE + GREEN 2026-08-30 — Cypress gate 10/10, including five that execute the mapping itself (§8).** The loose dispense now records what the patient
actually received. Branch: `feature/pack-loose-selling`.
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

## 4. ✅ ANSWERED — a script can only be in tablets, by construction

The open question was *"is `rxQty` written in tablets or in packs?"*. The owner settled it on 2026-08-30, and
the answer removes the question rather than choosing a side:

> "prescriptions never explain tablets or packs. A doctor always writes medicine names and dose duration."

A doctor writes *"Panadol 500mg, 1 tablet twice daily × 7 days"*. The quantity is therefore **derived** —
dose × frequency × duration — and that arithmetic can only ever produce a **count of tablets**. It cannot
produce a count of packs. The screen's own `dosage` / `frequency` / `duration` fields exist for exactly that
calculation.

**So there was never a shop-by-shop convention to respect.** There is one unit a prescription can be in, and
the pack case was simply wrong in the same direction as the loose one:

| dispensed | recorded before | recorded now |
|---|---|---|
| 15 tablets loose | 1 | **15** |
| 2 packs of 10 | 2 | **20**, capped at the 15 prescribed |

⚠ **Converting a pack line needs its pack size on the cart line**, which only LOOSE lines carried. Both add
paths now stamp `packSizeSnapshot` for a divisible product — the manual add from the till's cached pack
rules, the scan from the `ProductRef` the lookup already returns. Harmless on the way to the server:
`packSizeSnapshot` is server-populated and ignored inbound, so this is a record aid, never a trusted input.

*A question that looked like a preference turned out to have an arithmetic answer. Asking was still right —
guessing "tablets" for the wrong reason would have produced the same code with none of the confidence.*

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

---

## 7. Gate result

**6/6 green**, including the two that matter clinically: a 15-tablet script records **15** and closes, and a
filled script **cannot be dispensed a second time** — the repeat this defect permitted.

The pack case was gated at today's behaviour with a comment saying it was a question, not an endorsement —
and when the answer came (§4) that pinned case was the thing that flipped, deliberately and in one place.

*That is how to hold an undecided question: pin it, label it, do not pretend it is settled. Then when it is
settled, the test tells you exactly what to change.*

---

## 8. ⚠ The gate was testing the wrong layer

Run 2 failed on the pack case: it recorded **2** where 20 was expected. The fix was correct; **the gate could
not reach it.**

Every case posted straight to `/dispensePrescription` with a hand-written `quantity`. That proves the SERVER
records and caps what it is told, and says nothing about whether the till tells it the right thing — and the
defect lived in the browser's mapping. My test sent `quantity: 2` and the server faithfully recorded 2.

**So the six "green" cases of run 1 proved less than they appeared to.** They were real assertions about real
behaviour; they simply were not assertions about the thing that was broken.

The mapping is now extracted as `dispenseItemsFrom(cart)` and exported, and the gate asserts it directly:

| cart line | recorded |
|---|---|
| `{quantity: 1.5, soldUnit: LOOSE, soldQuantity: 15, packSizeSnapshot: 10}` | **15** |
| `{quantity: 2, packSizeSnapshot: 10}` | **20** |
| `{quantity: 6}` — indivisible | **6** |
| `{productId: 9}` — malformed | **0**, never `NaN` on a clinical record |

The API cases are kept, relabelled for what they actually prove: that the server caps 20 at the 15 prescribed.

> **The lesson, and it is the sharpest one in the programme:** *a gate that cannot execute the code you
> changed is asserting the artefact, not the property.* U8's first gate was written against the endpoint
> because the endpoint was easy to call — and the fix was three layers away from it.
