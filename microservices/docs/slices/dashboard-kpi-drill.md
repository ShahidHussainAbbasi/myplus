# Dashboard KPI cards drill into their detail list

**Status:** ✅ SHIPPED + GREEN — `cypress/e2e/business/dashboard-kpi-drill.cy.js` passes.

Asked for by the user: *"by clicking on kpi-card user should be redirected to the detail list like click on
dashVenders or Vendors it should land on vendor list same click on Vendor / Supplier"*.

---

## 1. What was wrong

A KPI card states a number a person immediately wants to interrogate — *43 vendors, which forty-three?* — and
the card was a dead end. The only route to the answer was to read the number, then find the same thing again
in a menu.

Worse, `.kpi-card:hover` already lifted the tile. **The cards looked interactive and did nothing** — the most
annoying kind of non-control, because the feedback says "clicked" and the screen says otherwise.

## 2. Design

One delegated handler, and the destination lives in the **markup**:

```html
<div data-widget="venders" data-drill="registrationType:VenderDiv" …>
```

| Card | Destination |
|---|---|
| Companies / Venders / Customers | `registrationType:<Section>Div` |
| Products, Stock value | `fn:showProducts` |
| Sales this month, Revenue this month | `sellType:SRDiv` |
| On Terms | `fn:showInstallments` |

**Why the target is an attribute, not a lookup table in JS:** a table keyed by widget name puts the card and
its destination in two files that have to be kept in step, and the next person to add a tile updates one of
them.

## 3. ⚠ There are TWO navigation kinds, and missing one would have failed silently

Most sections are options on a `<select>`. **Products and Installment plans have no option at all** — they are
opened by a function (`showProducts()` / `showInstallments()`).

A drill that only knew about selects would have done nothing on the Products card: no error, no console
message, and the hover lift still firing. The card would have read as unresponsive rather than unwired, on one
of the tiles an owner looks at daily. `dashDrill` therefore handles `fn:` as a first-class form, and the gate
asserts every navigation kind rather than only the easy one.

## 4. Details that are part of the requirement, not polish

- **Scroll to top after navigating.** The sections sit above the fold on a tall dashboard; without this the
  screen changes off-screen and the click reads as having done nothing — the exact complaint being fixed.
- **Keyboard parity.** `role="button"` + `tabindex="0"` + Enter/Space, with Space's default scroll prevented.
  A control only a mouse can reach is not a control, and this app already ships a keyboard-first POS.
  ⚠ The attributes are applied by the same script that handles the key, so the affordance cannot exist
  without the behaviour that honours it.
- **`cursor: pointer`, scoped to `[data-drill]`.** A tile with no destination keeps the default cursor rather
  than promising a navigation that will not happen.
- **A missing destination does nothing, quietly.** A capability- or privilege-gated section may not exist for
  this user; the card is a shortcut, never the only route, so it must not half-navigate.

## 5. Gate

`cypress/e2e/business/dashboard-kpi-drill.cy.js` — 9 cases. Every one asserts **the destination is visible**,
not that a handler ran and not that a select changed value: a card that switches a hidden select has taken
nobody anywhere. Case 4 (Products) is the near-miss; case 6 covers the second select; case 8 the keyboard.
