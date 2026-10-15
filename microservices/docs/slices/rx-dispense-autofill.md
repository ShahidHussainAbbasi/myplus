# RX-FILL — Dispense fills the cart from the prescription

**Status:** BUILT 2026-10-09 with the defaults below (Q1 round UP, Q2 + allowed above with an amber note, Q3 fill the rest and list the missing). Gate: `prescriptions-tab-walk.cy.js` 18/18 (RX-07..RX-18). Deployed on dev (monolith only). Not committed.
**Asked for:** pressing **Dispense** puts the prescribed medicines in the cart as lines; the counter adjusts each with
**+ / −** after asking the patient, then **Complete Sale**, **Park** or **Clear Cart**.

## 1. Today (traced, 2026-10-09)

| Step | What happens now | Where |
|---|---|---|
| Dispense | Sets `window.dispensingPrescriptionId`, shows the banner, runs the safety check. **Cart stays empty**; the counter re-picks every medicine. | `pharma.js dispenseFromPrescription` |
| + / − on a line | Exists, but only when `pos.counter.enabled` (restaurant counter) is on, and it **does nothing on a LOOSE (tablet) line**. | `business.js sellCartRow`, `counter.js step` |
| Complete Sale | Sale posts with `prescriptionId`; the sold lines are then recorded against the script (fixed in 7ac05ab5). | `main.js`, `pharma.js dispensePrescription` |
| Park | Saves customer + lines + tenders. **No prescription id.** The link is also left set after parking. | `park.js parkCurrentSale` |
| Resume | Rebuilds the cart. **No prescription link**, so a prescription-only line is refused and nothing records against the script. | `park.js rebuildCartFromResumed` |
| Clear Cart | `resetCart()`. **Leaves the link and the banner set.** | `businessDashboard.html #resetSellItem` |

### Findings

- **F1 (defect, traced in code, not yet run):** after **Clear Cart** or **Park** during a dispense, the prescription link
  survives. The next sale, possibly a different customer, is declared against that script: its prescription-only
  medicines pass the server guard, and whatever it sells is recorded on the script and, for controlled medicines,
  on the register under the script's patient.
- **F2 (defect, traced):** a parked dispense resumes as an ordinary sale. Its prescription-only lines are refused, and
  nothing records against the script.
- **F3 (open question, pre-existing):** a script is written in **tablets**; a cart line can be **packs**. The dispense
  already maps LOOSE lines to tablets (U8), but a PACK line records packs as if they were tablets
  (`pharma.js dispenseItemsFrom` comment). Auto-fill makes this unavoidable: it must decide how to put
  "15 tablets" in the cart.

## 2. Proposal

```mermaid
sequenceDiagram
  participant C as Counter
  participant T as Till
  participant PH as pharma-service
  C->>T: Dispense (Rx #42)
  T->>PH: checkSafety (SEVERE → confirm, Cancel stops here)
  T->>T: cart not empty? → ask: replace / cancel
  loop each prescribed line with something left
    T->>T: add OUTSTANDING qty (tablets → loose or packs, see 2.2), capped at sellable stock
  end
  T-->>C: lines shown "Rx: 15 left · in cart 15", with + / −
  C->>T: + / − after asking the patient
  alt Complete Sale
    T->>T: sale + dispense record (as today)
  else Park
    T->>T: parked WITH prescriptionId; link cleared
  else Clear Cart
    T->>T: cart AND link cleared, banner hidden
  end
```

### 2.1 What fills the cart
- One line per prescribed item with `quantity − dispensedQuantity > 0`; finished lines are skipped and said so.
- Quantity = what is **left**, so a second visit fills only the remainder.
- **Capped at sellable stock**, with a note ("only 8 in stock of 15 left"), never a line the server will refuse.
- Price, batch (FEFO/per-batch) and tax come from the same add path as a scan (`scanAddToCart`), not a new one.
- The cart is not empty when Dispense is pressed → ask **Replace** or **Cancel**. Never merge silently: it would
  charge an unrelated basket to the script.

### 2.2 Tablets vs packs (needs the owner's answer, see Q1)
- Product sellable loose → the line is **LOOSE in tablets** (15 tablets), exact.
- Product not sellable loose → **whole packs rounded up** (15 tablets of a 10-pack = 2 packs), shown as
  "2 packs (20 tablets) for 15 prescribed". The dispense records **15**, the server's cap.

### 2.3 + / − on dispense lines
- Shown on every line **while dispensing**, whatever `pos.counter.enabled` says.
- Steps in the line's own unit (a tablet on a loose line, a pack on a pack line). Fixes the loose gap in `counter.js`.
- **+** beyond what is left: allowed with an amber "more than prescribed" note (Q2); the record stays capped and warns, as now.
- **−** to zero removes the line (the patient takes it another day; the script stays PARTIALLY_DISPENSED).

### 2.4 Park / Clear Cart / Cancel (fixes F1 and F2)
- **Park** stores `prescriptionId` in the parked cart, then clears the link and banner.
- **Resume** restores the link and the banner.
- **Clear Cart** and banner **cancel** clear cart, link and banner together.
- **Complete Sale** is unchanged.

## 3. Questions for the owner
1. **Q1:** a medicine that cannot be sold loose, prescribed 15 tablets, 10 per pack: fill **2 packs** (rounded up) or
   **1 pack** (rounded down, the rest another day)?
2. **Q2:** may the counter go **above** the prescribed quantity with + (extra bought over the counter), or is +
   stopped at what is left? Always stop for **controlled** medicines?
3. **Q3:** a script with an expired or out-of-stock item: fill the others and list the missing ones (proposed), or refuse?

## 4. Slices and gates
| Slice | Content | Gate |
|---|---|---|
| RX-FILL-0 | F1 + F2: Park/Resume carry the link; Clear Cart and cancel clear it | Cypress: park → next sale NOT charged to the script; resume → dispense recorded |
| RX-FILL-1 | Auto-fill outstanding lines (2.1, 2.2), replace-or-cancel prompt | Cypress: Dispense → lines and quantities = remaining; second visit = remainder only; stock cap |
| RX-FILL-2 | + / − on dispense lines incl. loose | Cypress: + / − re-prices, loose steps in tablets, 0 removes, dispense records the stepped quantity |

Prior art (general knowledge, not researched this session): pharmacy dispensing systems such as PioneerRx and Rx30 fill
from the prescription queue with the drug and remaining quantity pre-loaded, and the pharmacist edits the quantity
dispensed (a partial fill). How the leading local Pakistani pharmacy POS handles it was not checked.
