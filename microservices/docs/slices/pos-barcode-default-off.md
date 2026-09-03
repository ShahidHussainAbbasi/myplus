# Barcode scanning defaults OFF for every tenant

**Status:** ✅ SHIPPED + GREEN — `cypress/e2e/business/pos-barcode-default.cy.js` passes.

Reported by the user: *"Barcode scanning is off but I can see sellScan"*, then ruled:
**"sellScan should be off by default for all tenants"**.

---

## 1. Why the switch did not switch anything

Not one bug — **five defaults**, each sufficient on its own to put the scan box back:

| Where | Was | Now |
|---|---|---|
| `BusinessSettingsCatalog` | catalog default `true` | `false` |
| `business.js` — key absent from the settings payload | `: true` | `: false` |
| `business.js` — settings call failed | `= true` | `= false` (fail CLOSED) |
| `applyPosBarcodeVisibility()` | `posBarcodeEnabled !== false` | `=== true` |
| `businessDashboard.html` | `#sellScanRow` rendered visible | `style="display:none"` |

**`!== false` is the one that produced the user's symptom.** It reads an *unset* flag as enabled, so the box
appeared on any screen reached before the settings call returned — including for a tenant who had explicitly
switched barcode scanning off. The same expression was in `pos-keyboard.js` and in the dashboard's focus hook;
all three now read `=== true`.

The template's default visibility mattered for the same reason: markup renders before any JavaScript decides
anything, so a control that is hidden only by JS is visible first.

## 2. Why OFF is the right default

The scan box is the **first field on the sale screen and the first place focus lands**. A shop with no scanner
pays a keystroke on every sale to skip a field it can never use. A shop that scans turns it on once, in
Configuration.

This is the `fail CLOSED` direction the POS keyboard flags already take, and for the same reason: guessing a
feature ON for a tenant that does not use it is a cost paid on every transaction, while guessing OFF costs one
visit to a settings screen.

## 3. ⚠ Blast radius, stated honestly

**A tenant that had explicitly enabled barcode scanning keeps it** — an `org_setting` override outranks the
catalog default, and case 6 asserts exactly that. This slice changes what an *unconfigured* tenant receives.

**A tenant that was relying on the default being ON will lose the scan box** and must switch it on. That is
the user's ruling, applied as asked; it is called out here because it is the one visible consequence.

`pos.barcode.enabled` also governs the **product form's Barcode field**. That coupling predates this slice and
is unchanged — but it means a tenant who turns scanning off also stops being asked to type EANs. Worth
knowing; not worth splitting into two settings unless someone asks.

## 4. Gate

`cypress/e2e/business/pos-barcode-default.cy.js` — 6 cases.

- **Case 1** asserts the **catalog** default (`defaultValue`), not the effective value. Asserting `value`
  would pass on any override a previous spec left behind.
- **Case 2** asserts from the **screen**. The flag reading `false` proves nothing about the page — the whole
  defect was that the two disagreed.
- **Case 3** asserts focus does not land in the hidden box. A hidden field that still takes focus leaves a
  cashier typing into nothing, which is worse than a visible one: there is no clue where the keystrokes went.
- **Case 5 is the over-correction guard.** Defaulting a feature off is one line away from deleting it — a shop
  that scans must still get its box.
- `after()` restores the setting. It is tenant-wide, and a spec that left it ON would put a scan box on the
  sale screen for every spec that ran afterwards.
