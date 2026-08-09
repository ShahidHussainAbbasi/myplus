# Sell screen (`sellDiv`) — UI/UX review for keyboard-first operation

**Branch:** `feature/UI-UX`
**Date:** 2026-08-09
**Scope:** the POS sale entry path — `#sellDiv` in `businessDashboard.html:1473-1806`, its handlers in
`js/business/business.js`, the shared helpers it depends on in `js/main.js`,
`js/common/focus-flow.js`, `js/common/searchable-selects.js`, and `js/business/park.js`.

**Question asked:** *why does a sale take so long, how do we make it mouse-free with Enter-key
navigation, and what else clears a queue of waiting customers?*

---

## 1. The path a cashier actually walks today

One cart line, manual (non-scan) entry:

| # | Control | Id | Cost |
|---|---------|-----|------|
| 1 | Item picker | `#sellItemDD` | opens on keypress, filters, Enter selects |
| 2 | *(wait)* | — | **two sequential XHRs** — `/productStock` then `quoteSellFormPrice()` |
| 3 | Description | `#sellItemDesc` | **readonly — dead tab stop** |
| 4 | QTY | `#sellItems` | typed |
| 5 | Bonus | `#sellBonus` | rarely used, always in the way |
| 6 | Stock | `#sellStock` | **readonly — dead tab stop** |
| 7 | Expiry | `#bexpDate` | **readonly — dead tab stop** |
| 8 | S/U Price | `#sellSellRate` | usually pre-filled, tabbed past |
| 9 | Total | `#sellTotalAmount` | **readonly — dead tab stop** |
| 10 | Discount | `#sellDiscount` | usually blank, tabbed past |
| 11 | Discount type | `#sellDiscountTypeDD` | rarely used |
| 12 | Receiveable | `#sellrm` | **readonly — dead tab stop** |
| 13 | **Add to Cart** | `#addInviceItem` | **mouse only — no key binding** |

Then, once per sale: customer mode toggle → `#sellCustomerDD` → `#sellPayMethod` →
`#sellRec` → *(dead stops `#sellCh`, `#sellDueThis`)* → **Complete Sale** (`#addSell`, mouse only).

---

## 2. Findings

### F1 — There is no Enter-key navigation anywhere in the form
The only Enter binding on the whole screen is the inline `onkeydown` on `#sellScan`
(`businessDashboard.html:1496`). Every other field requires Tab or the mouse, and **both action
buttons are click-only**: `#addInviceItem` is bound with `.click()` (`business.js:162`) and `#addSell`
likewise. A grep for `keydown`/`keypress` across `js/business/*.js` returns only minified vendor
bundles — the application code contains no keyboard handling at all.

### F2 — Enter is currently inert, so wiring it up is low-risk
`<form id="Sell">` (`:1483`) declares `action="/" method="POST"` but contains **no `type="submit"`
button** — `#addInviceItem` is `type="button"`, the other two are `type="reset"`. With more than one
text field and no submit button, the HTML implicit-submission rule does nothing. So Enter today is a
*wasted* keystroke rather than a dangerous one, and there is no "Enter reloads the page" regression
waiting to be triggered when we bind it.

### F3 — Readonly display fields sit in the tab order: 7–11 dead stops per sale
`readonly` is not `disabled`: a readonly input stays focusable and tabbable. `sellDiv` holds 16
readonly inputs. Excluding those inside `display:none` containers, a plain cash sale still tabs
through **7** fields nobody can type into — `#sellItemDesc`, `#sellStock`, `#bexpDate`,
`#sellTotalAmount`, `#sellrm` in the line form, plus `#sellCh` and `#sellDueThis` at checkout. Select
a customer and `#sellPrevDue` / `#sellNewTotalDue` join them (**9**); a customer with a credit limit
adds `#sellCreditLimit` / `#sellCreditAvailable` (**11**).

The five line-form stops are paid **per line**. A 5-line sale wastes ~25 keystrokes there plus ~7 at
checkout — **over 30 keystrokes of pure navigation overhead on a single sale.** This is the cheapest
real win on the screen and needs nothing but `tabindex="-1"`.

### F4 — The pickers ARE keyboard-usable; the earlier assumption was wrong
`searchable-selects.js` applies bootstrap-select with `data-live-search` to essentially every
dropdown, and `#sellItemDD` carries it in the markup too. **bootstrap-select v1.6.2's `keydown`
handler opens the menu on any alphanumeric key and focuses the search box** (verified in
`bootstrap/js/bootstrap-select.min.js`), so Tab → type → Enter already selects an item.

*This is worth stating plainly because it removes a whole line of work:* the item and customer
pickers do not need replacing for keyboard operation. What they lack is what happens **after**
selection — focus lands back on the picker button, and the next Tab walks into a dead readonly field
(F3).

### F5 — Two sequential round-trips per manually-entered line
Selecting an item fires `onChangeSelect` (`main.js:365`) → `loadStock()` (`business.js:1856`) →
`GET /productStock?productId=`, whose success handler then calls `quoteSellFormPrice(value)` — a
second call. The code already mitigates the *visible* part of this by filling the rate immediately
from the option's `data-price` (`business.js:1875-1881`), so the price appears without waiting. But
stock, description, batch and the buyer-specific price all still arrive late.

### F6 — The scan path is already the fast path, and is under-used
`sellScanAdd()` (`business.js:256`) resolves the code via `lookupProduct` and hands the resulting
`ProductRef` straight to `scanAddToCart()` (`:272`), which pushes the cart line directly. **One
round-trip, no form fill, no dead tab stops.** Compare F5's two. The fast path exists; the screen
just doesn't steer anyone into it.

Supporting detail: `#sellScan` is the first tabbable field in `sellDiv`, so `focus-flow.js`'s
`revealSection()` → `focusFirstField()` already lands there on section switch — but only at
≥992px and non-touch, and nothing re-focuses it when the cashier has moved away.

### F7 — Scanning cannot set a quantity (the biggest throughput gap)
`scanAddToCart` always adds **qty 1**, incrementing to 2, 3… on repeat scans. Selling 12 of an item
means twelve scans or abandoning the scan box for the manual form and all of F3's dead stops. Every
mainstream POS solves this with a multiplier (`12 * <scan>`). **This is the single highest-value
change for a queue.**

### F8 — No tender shortcut
`#sellRec` ("Amount Received") must be typed even for exact cash, which is the majority of
transactions in most shops. `calculateChange()` (`business.js:2227`) already derives change and due
from the cart total, so the number needed for "exact" is sitting in `#sellTotal` — it simply is never
offered.

### F9 — Park/Resume is fully built and effectively unreachable
`park.js` implements `parkCurrentSale()`, `parkedSales`, `resumeParked()` and `discardParked()`, and
`#parkSaleBtn` sits next to Complete Sale. There is **no keyboard binding**, and Resume lives on a
separate `#ParkedDiv` screen. This is the correct answer to "a customer is holding up the line" —
they step aside, the queue moves — and it is one keypress away from being usable.

### F10 — `loadStock()` opens with six dead labelled statements
`business.js:1857-1862`:
```js
bpurchaseDiscount: 0
bpurchaseDiscountType: "%"
...
```
These parse as **labelled statements**, not assignments. They do nothing. Harmless at runtime, but
they read as initialisation and will mislead the next person in this function.

### F11 — Invalid self-closed `<button/>`
`businessDashboard.html:1622`: `<button type="reset" class="resetForm" style="display:none"/>`.
HTML does not permit self-closing on `<button>`; the parser leaves the element open and absorbs
following markup until a closing tag rebalances it. It sits at the end of the form so the visible
damage is nil today, but it is a latent layout trap.

---

## 3. What this means

The screen is not slow because it is missing features — **the two fastest paths it has (scan-to-cart
and park/resume) are already built.** It is slow because:

1. nothing puts the cashier on the fast path or keeps them there (F6, F9);
2. the fast path can't express "12 of these" (F7);
3. the slow path is padded with keystrokes that do nothing (F3);
4. and no keyboard route exists to the two buttons that end a line and end a sale (F1).

All four are additive fixes. **None requires changing how a sale is priced, saved or posted**, which
is why this can ship behind flags without touching the sale's correctness.

---

## 4. Constraints the design must respect

- **C1 — Current behaviour stays the default.** Every change lands behind a per-tenant setting that
  is **OFF** by default, so an untouched org sees exactly today's screen.
- **C2 — Settings are owner *and* admin.** `SettingsController:39` already permits
  `ROLE_OWNER or ADMIN_PRIVILEGE` to write settings, but the UI screen `#ConfigDiv`
  (`businessDashboard.html:2219`) is gated `ROLE_OWNER` alone. Widening the screen aligns it with the
  authorisation the server already grants — a UI/server mismatch fix, not a policy change.
- **C3 — Don't disturb the shared `onChangeSelect` path.** `main.js:365` serves Purchase as well as
  Sell; any picker change must be opt-in per screen.
- **C4 — Don't fight `searchable-selects.js`.** It re-`refresh`es every picker on `ajaxComplete`.
  Rebuilding a select orphans handlers bound to the original element — the exact bug recorded on the
  education dashboard (sidebar dead until the dropdown was used once).
- **C5 — Shortcut keys must not collide** with browser/OS/screen-reader bindings, and must be
  suppressed while a modal or a confirm dialog is open.
- **C6 — No duplicate functions.** New behaviour goes in one module; shared helpers stay shared.

---

## 5. Proposed packages

| Pkg | Contents | Findings closed |
|-----|----------|-----------------|
| **P1 — Keyboard flow** | `tabindex="-1"` on dead readonly fields; Enter walks Item→Qty→Price→*Add to Cart*; focus returns to the scan box after each add; Esc clears the in-progress line; scan box auto-focus on entering the screen | F1, F3, F6 |
| **P2 — Queue throughput** | Qty multiplier in the scan box (`12*CODE`); shortcut keys for Complete Sale / Park / Resume / Clear Cart; exact-cash key filling `#sellRec` from the cart total | F7, F8, F9 |
| **P3 — No-barcode speed** | Hot-key grid of top-N products for loose items; remove the second round-trip on the manual line where the data is already in hand | F5 |
| **Incidental** | F10 dead labelled statements, F11 self-closed button | F10, F11 |

Design, settings keys and Mermaid diagrams: `slices/uiux-P1-P3-keyboard-pos.md`.
