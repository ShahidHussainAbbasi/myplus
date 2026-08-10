# UI/UX P5 — the complete Enter chain, and the single-row line entry

**Branch:** `feature/UI-UX` · **Predecessors:** `uiux-P1-P3-keyboard-pos.md`, `uiux-P4-checkout-keyboard-and-settings-ux.md`
**Status:** design agreed 2026-08-10, implementing.

---

## 1. Problem

Three defects in the shipped chain, all found by the user driving a real till:

1. **The dropdowns cost two Enters.** `sellItemDD`, `sellCustomerDD`, `sellPayMethod` and
   `sellDiscountTypeDD` are bootstrap-select widgets — a `<button data-toggle="dropdown">`. Enter on a
   focused button *activates* it, so the first Enter opens the menu and only a second could advance.
   The "double Enter" in the requirement was the widget forcing it, not a preference.
2. **`sellDiscountTypeDD` is not in the chain**, so amount-vs-percent can only be set with the mouse.
3. **The line form is still one field per row**, so a line is composed down ~600px of screen.

## 1b. Standards

| Standard | How it is met |
|---|---|
| **Domain** — a keyboard must not change what a sale means | Every path calls the existing handlers (`#addInviceItem`, `#addSell`). Pricing, credit checks, tax and GL are untouched. |
| **Input-agnostic** (accessibility + touch) | Advancing hooks *selection*, not a keystroke, so mouse, touch and keyboard behave identically. |
| **SOLID/DRY** | One `walk()` for both chains; one `usable()` deciding participation; one `.pos-cell` rule for layout. |
| **Named pattern** | Chain of Responsibility over an ordered field list, filtered by a single predicate. |
| **Testing** | A gate that drives real keystrokes end to end, plus the OFF case for the layout. |

## 2. D-23 — advance on SELECTION, never on the keystroke

```
type to search   → the widget opens its menu          (bootstrap-select's own behaviour)
Enter/click      → the widget selects the option
                 → 'changed.bs.select' fires
                 → the chain advances
```

**Why this and not a double-press detector.** A double-Enter needs a timing window — how long, and what
if the operator pauses, or a scanner sends the second key? It also has no visible state, so a cashier
cannot tell whether the first press registered. Hooking selection removes the timer entirely *and*
makes the mouse path behave the same, which a keystroke patch cannot.

**Guard:** `changed.bs.select` also fires when JS sets a value (`loadStock`, `loadCategories`,
`loadManufacturers`). Only a *user* selection advances — bootstrap-select passes `clickedIndex` for
real interactions, and a programmatic `.val()+refresh` does not.

## 3. The chain

```
LINE      item → qty → price → discountType → discount → commit → back to entry point
CHECKOUT  customer → payMethod → (visible extras) → received → [due date] → complete
```

- **Every VISIBLE field is a stop** (user decision #5). A field hidden by tenant configuration, by the
  row layout, or because it does not apply is skipped — that is `usable()`, unchanged.
- **Repeat** returns to the scan box when barcode scanning is on, otherwise the item picker (#4).
- Trade discount is visible by default, so it *is* a stop. A shop that never grants one switches off
  `pos.invoice.tradeDiscountEnabled`; that is what the setting is for.

## 4. D-24 — the customer requirement, narrowed not removed

Mobile and due date were already made optional. The customer check now applies **only when the sale
leaves a balance**.

- Fully paid (cash, card, exact) → **no customer needed**; a walk-in is rung anonymously.
- Any balance (credit, partial) → **a customer is still required**.

**Why the carve-out survives:** a receivable against nobody cannot be chased, aged or collected. Every
other field made optional is an inconvenience when missing; this one makes the money unrecoverable.
The user confirmed: *"yes needs a customer"*.

## 5. D-25 — the single row, rebuilt on `.pos-cell`

Two earlier attempts failed because `display:contents` promoted labels and fields into one flex row as
independent siblings, so a caption was never tied to its control and alignment was faked with
`align-self` plus a negative margin.

`.pos-cell` wraps each label+field pair. It is `display:contents` **by default** — invisible to layout,
so the classic stacked form is unaffected — and becomes a flex column only under `.pos-rowentry`.
Because each cell is indivisible, the row **wraps** instead of switching off on small screens: one
layout from a 24" till down to a phone.

`.pos-fullrow` (scan box, FEFO notice) uses flex `order` so the scan box leads and the notice follows
the fields, instead of splitting the strip. `order` affects painting only — tab order and
`formToJSON` are untouched.

**Ships default OFF** (`pos.entry.compactRow`). The layout has burned twice; it is enabled per tenant
once seen, not imposed on every till by a deploy.

## 6. Gate

`cypress/e2e/business/pos-enter-chain.cy.js`:
- Enter walks item → qty → price → discountType → discount, one press per field
- selecting in a dropdown advances (no second press)
- a hidden field is skipped
- fully-paid sale completes with **no customer**; a sale with a balance still demands one
- the compact row: OFF changes nothing; ON keeps every field present and submitting

## 7. Open

`loadCategories` still has the edit-path trap fixed for manufacturer — a category missing from the
list is silently reassigned on save. Untouched, still outstanding.
