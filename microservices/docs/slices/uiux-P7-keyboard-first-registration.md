# UI/UX P7 — Keyboard-first registration, everywhere

**Status:** DESIGN — awaiting consent. Nothing implemented.
**Goal:** every registration form and modal is completable without a mouse, the way the sale and
purchase screens now are.

---

## 1. Review — what is actually out there

| Module | Modals | `.form-horizontal` forms |
|---|---|---|
| Business | 7 — Company, Customer, Vender, Product, Purchase, ReceivePayment, PayVendor | 16 |
| Education | 9 — School, Student, Guardian, Staff, Owner, Subject, Grade, Discount, Vehicle | 16 |
| Welfare | 2 — Donator, Donation | 3 |
| Agriculture | 3 — Land, AgricultureIncome, AgricultureExpense | 3 |
| **Total** | **21** | **38** |

Two of the 38 are done (sale, purchase). The other 36 are mouse-or-Tab only.

### The decisive finding: the hard part already exists

`/js/common/focus-flow.js` already owns the exact predicate a chain needs, and every modal already
calls into it (`crud-modal.js` → `openModal` → `focusFirstField`):

```js
var TYPEABLE = 'input, select, textarea';
function skip(el) {                       // disabled, readOnly, hidden/submit/button/reset,
    ...                                   // .no-autofocus, or not visible
}
function focusTarget(el) { ... }          // bootstrap-select: focus the BUTTON, not the <select>
function mayAutoFocus() { ... }           // desktop only — never on touch/<992px
```

`enter-chain.js` (P6) owns the movement: `walk()`, `focusField()`, `bind()`.

So P7 is not 21 new features. It is **joining two things that already exist** and letting every form
opt in.

---

## 2. The core decision: derive the chain, never hand-list it

P6 gave the purchase form an explicit array of 11 field ids. Repeating that 21 times would be wrong,
and this codebase has already paid for exactly that mistake twice:

- `CHAIN` listed `sellDiscountTypeDD` but the Enter handler's guard did not → the picker became a
  keyboard **dead end** on the most-used screen.
- P6's first chain grouped fields *logically* while the form was laid out differently → Enter jumped
  row 1 → row 8 → row 3.

**A hand-written field list is a second copy of the form, and the copy drifts.**

So the chain is computed at Enter-time from the DOM:

```
chain(container) = [...container.querySelectorAll('input, select, textarea')]
                       .filter(el => !skip(el))          // focus-flow's predicate, unchanged
```

This buys, for free:

- **Order follows the layout**, because the DOM *is* the layout. The P6 lesson, enforced structurally.
- **Tenant field-visibility config works** with no extra code — a hidden field fails `skip()`.
- **New forms get it automatically.** No registration step, nothing to remember.
- **Nothing to drift**, because there is no second list.

### One predicate, not two

`enter-chain.usable()` and `focus-flow.skip()` are today the same rule written twice, and that is the
precise shape of the `sellDiscountTypeDD` bug. P7 makes `focus-flow` the single owner and has
`enter-chain` consume it. The sale and purchase chains keep working — they change what they *call*,
not what they *do*.

---

## 3. The contract

| Key | Action |
|---|---|
| `Enter` | next field |
| `Shift+Enter` | previous field |
| `Enter` on the last field | submit (`#add<Entity>`, derived from `<Entity>Modal`) |
| `Ctrl+Enter` | submit from anywhere |
| `Esc` | close / cancel |
| `Alt+N` | inline-create from a picker (where the form has a `<Field>New` control) |

### Rules that are not negotiable

**A `<textarea>` never advances on Enter.** Enter inserts a newline — that is what the control is for.
Leave it with `Tab` or `Ctrl+Enter`. Getting this wrong makes every address and description field
unusable, and it is the single most common way a keyboard-nav feature ships broken.

**A picker with its menu open keeps its Enter.** The plugin selects the highlighted row and fires
`changed.bs.select`, and *that* advances. Intercepting would advance without recording the choice.

**Radio and checkbox groups advance**, but `Space` still toggles.

**Touch and narrow screens are excluded**, via the existing `mayAutoFocus()`. A phone keyboard has no
Enter-to-next expectation and hijacking it is worse than doing nothing.

---

## 4. Configurable, and customizable without code

Two different needs, answered two different ways.

### Tenant settings (3, not 30)

Added to each module's existing `SettingsCatalog` via the shared `common-settings` lib:

| Key | Default | Why a tenant would change it |
|---|---|---|
| `ui.keyboard.formNav.enabled` | **ON** | Turn the whole thing off for staff trained on Tab. |
| `ui.keyboard.enterSubmits` | **ON** | OFF = Enter never submits, only `Ctrl+Enter` does. For shops nervous about a half-typed record being saved by a stray Enter. |
| `ui.keyboard.requiredOnly` | **OFF** | ON = Enter visits only `[required]` fields; optional ones are reached with `Tab`. A clerk registering 200 students touches 5 fields instead of 18. |

`requiredOnly` is the one worth selling. It is the difference between "supports the keyboard" and
"designed for someone doing this 200 times before lunch".

Three settings, all failing to today's behaviour, is deliberate. Every extra toggle is a support
question and a screen someone has to understand; the value is in the defaults being right.

### Per-form customization — attributes, no code

A form that needs to differ says so in its own markup:

| Attribute | Effect |
|---|---|
| `data-kbd-skip` | never a stop (e.g. a computed box that is technically editable) |
| `data-kbd-order="N"` | override position for the rare form whose visual order ≠ DOM order |
| `data-kbd-submit="#someButton"` | non-standard submit control |
| `.no-autofocus` | already honoured by `focus-flow` — kept as-is |

This is how a form is customized without touching JavaScript, and it is what lets a future vertical
(clinic, school, distributor) tune its own screens.

---

## 5. Where this puts us against the market

Tally, Busy, Odoo and QuickBooks all sell on keyboard speed; in Pakistan/India wholesale, Tally's
keyboard flow is *the* reason shops refuse to switch. Matching it is table stakes for the segment
this product targets.

Table stakes: Enter-to-next, Esc, submit shortcut.
Where this design goes further: **`requiredOnly`**, **inline-create from a picker without leaving the
keyboard** (`Alt+N`), and **per-form attribute customization** — all three configurable per tenant
rather than compiled in.

---

## 6. Phasing — gated, each phase green before the next

| Phase | Scope | Why this order |
|---|---|---|
| **P7.1** | The engine: `focus-flow` owns the predicate, `enter-chain` gains `bindContainer()` (derived chain), sale + purchase migrated onto it | Prove it on the two screens already covered by 42 passing tests. If those stay green, the engine is sound. |
| **P7.2** | Business — 7 modals + the 3 settings | Highest-volume module; validates the derived chain against real variety (pickers, dates, textareas). |
| **P7.3** | Education — 9 modals | Largest count; Student/Guardian are the longest forms in the app. |
| **P7.4** | Welfare + Agriculture — 5 modals | Small, mechanical once the engine is proven. |
| **P7.5** | `requiredOnly` + `Alt+N` inline-create | The differentiators, once the base is solid everywhere. |

**P7.1 is the one that carries the risk.** It touches two working screens. Its gate is the existing
42 tests staying green — a regression there means the engine is wrong, caught before it reaches 19
other forms.

---

## 7. Test plan

Per phase: every modal gets `Enter` walks it, `Shift+Enter` reverses, hidden fields skipped, `Esc`
closes, submit fires once.

Cross-cutting, written once and run against every form:

1. a `<textarea>` does **not** advance on Enter, and the newline lands
2. a hidden field is skipped; unhiding it makes it a stop
3. `Enter` on the last field submits exactly **once** (no double-submit)
4. `enterSubmits=OFF` → Enter on the last field does nothing; `Ctrl+Enter` still submits
5. `requiredOnly=ON` → the walk visits only `[required]`
6. the form still submits correctly with the **mouse** — the keyboard is additive, never a gate
7. touch/narrow viewport → chain inert

Point 6 matters most. Every one of these forms works today; the failure mode to guard against is P7
breaking mouse users to serve keyboard users.

---

## 8. Scope and risk

**Touched:** `focus-flow.js`, `enter-chain.js`, `crud-modal.js`, 4 dashboard templates (attributes +
hint bars), 4 `SettingsCatalog` classes, i18n ×6, ~6 new spec files.

**No DB change. No service contract change.** Settings ride the existing `common-settings` catalog.

**The real risk is regression, not the new code.** 36 forms that work today start responding to a key
they previously ignored. That is why P7.1 is gated on 42 existing tests, and why "still works with the
mouse" is an explicit assertion rather than an assumption.

**Recommendation:** approve **P7.1 only**. It is the whole engineering argument — derived chain, one
predicate, proven against existing tests. If it lands green, P7.2–P7.5 are largely mechanical. If it
does not, we have learned that cheaply and no other form was touched.
