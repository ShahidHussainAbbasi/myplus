# Working MaxTheService without a mouse — the complete reference

**Status:** REVIEW. Written from the shipped code on 2026-09-04, not from the slice designs — where the two
disagreed, the code won and the disagreement is recorded below.
**Slices:** `uiux-P1-P3-keyboard-pos.md` · `P4` checkout · `P5` enter-chain · `P6` purchase · `P7` registration.
**Manual walk:** the MyPlus Test Book, §"Working without a mouse".

---

## 1. What was actually built

Four independent mechanisms, deliberately separate — a shop can have any of them without the others:

| # | Mechanism | File | Governs |
|---|---|---|---|
| 1 | **Focus flow** | `common/focus-flow.js` | where the cursor lands when a screen opens or a field is rejected |
| 2 | **Enter-chain engine** | `common/enter-chain.js` | "what is the next usable field", for any form that opts in |
| 3 | **Registration forms** | `common/keyboard-forms.js` | all 21 CRUD modals, bound by naming convention |
| 4 | **The till** | `business/pos-keyboard.js` | the sale screen: two-phase chain, F-keys, quick-pick |

**They layer.** 2 is the engine; 3 and 4 are two policies over it. 1 is consulted by all of them for the one
question *"is this worth putting a cursor in?"* — a question that was answered in three places once, and the
copies drifted until a picker that was in the sale chain had no way out of it.

---

## 2. The four switches, and what each really does

All four are **tenant settings**, in Configuration. None is a platform decision.

| Setting | Default | Off means |
|---|---|---|
| `ui.keyboard.formNav.enabled` | **on** | registration forms behave as before: Tab only |
| `ui.keyboard.enterSubmits` | **on** | Enter past the last field does NOT save — **Ctrl+Enter still does** |
| `pos.keyboard.enabled` | **on** | the sale line goes back to the tall stacked form |
| `pos.keyboard.shortcuts.enabled` | **off** | no F-keys, and a scanned code is taken literally |

Two properties worth stating because they are easy to get backwards:

* **`ui.keyboard.*` fail OPEN.** An absent value means ON. *"The keyboard is additive, and losing it silently
  is a worse failure than a tenant who wanted it off keeping it."*
* **`pos.keyboard.*` fail CLOSED.** A config-read hiccup must not arm function keys on an untrained till.

⚠ **`enterSubmits` off never makes a form un-submittable from the keyboard.** Ctrl+Enter always saves. The
setting exists for teams worried a stray Enter saves a half-typed record — not to force a mouse.

---

## 3. The sale screen

### 3a. The line, in one row

```
Customer → Item → Serial → Qty → Price → Disc-type → Discount → [Enter] adds the line
```

* **The customer comes first** (task #13). A sale rung against a customer the system did not know it had was
  priced once and re-priced when it learned — choosing first makes the first price the right price.
* **A walk-in is not slowed down.** Leave the picker blank and **double-Enter** skips it — **to the goods**,
  which is the next thing on a sale. That escape is what makes putting the customer first safe to impose at
  all; without it every cash sale gains a mandatory stop.
* ⚠ **Skipping the customer used to land on `sellRec`** — the Received box — with an empty cart, past every
  field that puts anything in it. The rule was right when it was written and stopped being right underneath
  itself: the customer picker was once the *first field of checkout*, where "no customer named, take me to the
  amount" is exactly correct. Task #13 moved it to the head of the line chain and the branch was not
  revisited. **Reported from the counter; fixed 2026-09-04.**
* ⚠ **Serial sits between Item and Qty**, and this was **missing from the chain until this review**. SER-3d
  moved the box in front of QTY on screen but the walk still went Item → Qty, so a keyboard-only cashier
  could not reach it — and for a serial-tracked product the server then refuses the sale. See §7.
* It must come **before** the quantity, because entering a serial **locks the quantity to 1**. Walking
  Item → Qty → Serial would put the cashier in a box whose value the next field overwrites.

### 3b. Checkout

**Enter on an empty item picker means "no more lines"**, and the cursor goes to the **first field of the
checkout** — the payment method, which is what the chain rule says comes next:

```
empty item picker → Payment method → Received → Trade discount → Due date → Complete Sale
                          └─ AFTER_METHOD decides: Received for cash/card/wallet/bank/split,
                             Due date for CREDIT, because a credit sale takes no money now
```

⚠ **The chain USED TO DISAGREE WITH THE SCREEN, and its own comment claimed otherwise.**

| | order |
|---|---|
| on screen | payMethod → store credit → **Received** → **trade discount** → due date → insured |
| in the chain | payMethod → trade discount → store credit → insured → **Received** → due date |

So Enter left the payment method, jumped past Received to a discount box further down the page, and then
back up. It survived for as long as it did because `AFTER_METHOD` sends a cash sale straight to Received and
hides the worst of it — the walk only looks wrong once you are past that jump. **Corrected to the DOM
order.** Same rule the sale line follows: the keyboard order and the visual order are one thing, not two that
have to be kept in step.

**The landing is a preference list, not a target:** payment method → Received → trade discount → whatever
else the checkout still shows → complete the sale. Every entry is checked, so a shop that hid one lands on
the next thing it does have rather than nowhere.

⚠ **An earlier cut landed on Received directly**, arguing that the method dropdown is never empty — it opens
on the tenant's `pos.tender.default` — so stopping there answers nothing. That was overruled, and the reason
is better than the optimisation:

* **The rule is "the next available field in the chain",** and after the line phase that is the payment
  method. A jump that skips ahead is a second, unstated ordering — the same kind of special case that made
  the customer picker leap to the money and strand a cashier on an empty cart.
* **It disabled a rule that already existed.** `AFTER_METHOD` routes the cursor onward from the method:
  Received for cash, card, wallet, bank transfer and split; **due date for CREDIT**, because a credit sale
  takes no money now. Landing past the method sent a credit sale to Received — a box it has no business in.
  One stop on a pre-filled dropdown buys that routing; removing the stop cost it.

The customer is deliberately **not** here any more. Leaving it in both phases made the cashier name the buyer
twice per sale.

### 3b-i. The two rules, and the audit that enforces them

> **RULE 1 — the chain order IS the screen order.** One thing, not two kept in step. Reorder in the
> markup and the walk follows; never with CSS `order`, never by rewriting a list to disagree with the page.
>
> **RULE 2 — a named field is a preference, never a target.** Whether it is on screen is the tenant's
> decision. Check it, move to the next available one; never focus nothing, and never leave an id for a
> field that no longer exists.

**Corollary, learned twice:** a jump that skips *ahead* of the next field is a second, unstated ordering.
It made the customer picker leap to the money and strand a cashier on an empty cart, and a later attempt to
skip the payment method silently disabled `AFTER_METHOD` — the rule that sends a CREDIT sale to its due date
rather than to a Received box it has no business in. If a stop feels wasted, the answer is a routing rule
that says why, not a jump that says nothing.

**Enforced:** `cypress/e2e/business/keyboard-chain-order.cy.js` reads the chain arrays out of the shipped
file and compares them with the DOM — order, ghost ids, landing preferences, tender targets, pickers. It is a
static audit, not a walk: the behaviour is still section 11 of the Test Book, by hand. Prose in a header is
what all three defects already had.

#### Audit result, 2026-09-04

| Chain | Rule 1 (screen order) | Rule 2 (no ghosts) |
|---|---|---|
| sale line | ✅ | ✅ |
| checkout | ✅ *(was wrong — fixed)* | ✅ *(`sellInsured` removed)* |
| landing preferences | — | ✅ all in the checkout chain |
| tender targets | — | ✅ all in the checkout chain |
| pickers | — | ✅ all in a chain, so Enter can leave each |
| purchase | ✅ *derived from the DOM — cannot drift* | ✅ |

| Jump | Preference order | Last resort |
|---|---|---|
| entry point | customer picker → manual name → the goods | — |
| the goods | scan box → item picker → next in the line chain | the checkout fields |
| lines finished | **payment method → Received → trade discount** → rest of the checkout | complete the sale |

⚠ **The goods fallback reaches the checkout fields DIRECTLY, never through `goToCheckout()`** — that function
sends an empty cart back to the goods, and the only way to reach the fallback is with no goods field on
screen, which an empty cart then guarantees. Calling it would be a loop with no exit that hangs the tab on a
keystroke: the same shape as the customer → item → customer loop task #13 left behind.

### 3c. Function keys — off by default

| Key | Alias | Does |
|---|---|---|
| **F2** | Alt+S | complete the sale |
| **F3** | Alt+P | park it |
| **F4** | Alt+R | resume a parked sale |
| **F8** | Alt+E | tender the exact amount |
| **F9** | Alt+C | clear the cart — **asks first** |
| **Alt+1..9** | — | ring up a quick-pick tile |

* The **Alt aliases are not decoration**: kiosks, some browsers and most Linux desktops swallow function keys.
* **F1, F5, F11, F12 are deliberately never bound** — the browser or OS owns them, and an override either
  fails silently or angers the user.
* **F9 confirms.** It sits next to F8, and a mis-hit would otherwise destroy a part-rung sale in silence.
* A field the tenant switched off is **not reachable by shortcut either**, or a shop could reach a control it
  had removed.

### 3d. Scanning a quantity

With shortcuts on, scanning `12*CODE` adds twelve. Off, the same string is taken literally as a code.

---

## 4. Goods-in (the purchase form)

```
invoice → batch → vendor → item → serial → condition → qty → cost → sell → date → expiry → paid
```

* **Enter past the last field = Save & Add Another.** A delivery rarely has one line. **Ctrl+Enter** saves and
  closes; **Esc** closes.
* ⚠ **This walk is READ FROM THE DOM**, not enumerated. `EnterChain.fieldsIn('#Purchase')` takes the form in
  document order, so hidden fields, read-only boxes and anything the tenant switched off fall out for free.

**That difference is the single most useful lesson in this document.** SER-3d moved the serial box on both
screens. The purchase form picked it up with **no code change at all**. The sale screen, whose chain is a
literal list of ids, silently left it out and produced a keyboard dead end on a field the server requires.
**Deriving beats enumerating**, and that is the evidence.

---

## 5. Registration forms — all 21, by convention

`keyboard-forms.js` binds every CRUD modal from the shape they already share:

```
<div id="<Entity>Modal" class="crud-overlay">  … fields …  <button id="add<Entity>">
```

Nothing in that file knows any entity's name. A modal that does not fit says so **in its own markup**:

* `data-kbd-custom` — this modal owns its chain (the purchase form)
* `data-kbd-submit="#selector"` — the submit control, when it is not `#add<Entity>`

⚠ The container is the **modal**, not the `<form>`. Receive Payment and Pay Vendor have no `<form>` at all —
they are hand-built dialogs. Deriving from the modal covers both shapes with one rule.

**Per form:** Enter advances · Shift+Enter goes back · Enter on the last field saves (if `enterSubmits`) ·
Ctrl+Enter always saves · Esc closes.

---

## 6. Focus — where the cursor goes, and where it deliberately does not

| Helper | When |
|---|---|
| `revealSection(el)` | a section is shown: scroll it under the sticky header, then focus its first field |
| `focusFirstField(el)` | a modal opens |
| `focusInvalid(el)` | validation rejects a field: scroll to it and put the cursor in it |

Four restraints, each deliberate:

* ⚠ **No auto-focus on touch or below 992px.** Focusing an input there yanks up the on-screen keyboard and
  hides half the page. Those devices still get the scroll.
* **Scrolling accounts for the sticky header**, or the thing scrolled to sits underneath it.
* **`.no-autofocus`** opts a field or container out — the sale screen's barcode box has its own rules.
* **`prefers-reduced-motion` gets an instant jump**, not a glide.

⚠ **bootstrap-select is the recurring trap.** The plugin hides the original `<select>` and renders a button in
its place, so measuring the `<select>` always answers "invisible" — which silently removed every picker from
any walk built on it. `visualEl()` measures what the plugin actually shows. Any new keyboard code that asks a
`<select>` whether it is visible is wrong before it is written.

---

## 7. What this review found

| Finding | State |
|---|---|
| ⚠ **`sellSerials` was missing from the sale Enter-chain** — visual order and keyboard order disagreed, and the field the server requires was unreachable without a mouse | **fixed in this review** |
| ⚠ **Skipping an empty customer jumped to the Received box**, past the whole goods phase, on an empty cart. A rule that was correct while the customer lived in checkout and was never revisited when it moved | **fixed — reported from the counter** |
| **The landing order needed stating explicitly**, so a hidden field falls through instead of stranding the cursor | **done:** payment method → Received → trade discount → rest → complete. ⚠ My first cut skipped the payment method as an "empty stop" and thereby disabled `AFTER_METHOD`, which sends a CREDIT sale to its due date rather than to Received. Overruled by the owner on the chain rule, and he was right |
| ⚠ **`focusGoodsEntry()` focused the item picker unconditionally.** A tenant who had hidden it got a call that focused nothing: cursor unmoved, Enter apparently dead, no error | **fixed:** checked, then the next usable field in the chain |
| ⚠ **The checkout chain disagreed with the screen** while its own comment claimed otherwise — Enter jumped past Received to a discount box lower down, then back up | **fixed:** reordered to the DOM order |
| ⚠ **`sellInsured` was a GHOST** — in the chain for weeks after the field was removed from the screen (P12, slice 59). Harmless, because `usable()` never focuses a missing element, but a chain naming a field nobody can see is a chain nobody can read | **removed.** The template's own comment had said "harmless, but dead — worth a sweep" |
| The two rules were understood but never written down or enforced | **fixed:** stated at the top of `pos-keyboard.js` and audited by a gate |
| The dead ternary behind it (`val() ? 'sellPayMethod' : 'sellRec'`) — `skipAhead` only ever runs on an EMPTY picker, so the true branch could never execute | removed with the fix. A ternary that can only take one path is a rule nobody can check |
| The purchase walk comment described a stale field order | **fixed in this review** |
| `data-kbd-skip` is implemented in `focus-flow.js` and used by **no markup** | left as is — read-only fields are already skipped, so it is an unused escape hatch, not a defect |
| The sale chain is a literal list while the purchase chain is derived | **not changed.** The sale screen's policy (skip-ahead, two phases, pickers) is genuinely sale-specific; converting it is its own slice, and this review is not the place to rewrite the till |

---

## 8. Not yet true, and worth saying plainly

* **The BEHAVIOUR still has no automated gate** — no spec presses Enter and follows the cursor. The new audit
  proves the chains are *structurally* right (order, ghosts, targets); that they *feel* right is section 11 of
  the Test Book, walked by hand. Both halves are needed: the structural defects were unreadable, and the
  behavioural ones were unprovable.
* **The keyboard has never been walked in Arabic or Urdu.** Enter and Tab are direction-agnostic, but "next
  field" in a right-to-left layout is a claim nobody has tested.
* **Quick-pick tiles are off by default** and therefore rarely exercised; Alt+1..9 is the least-walked path
  in this document.
* **There is no on-screen key reference** beyond the one-line hint on the sale screen. A shop that turns
  shortcuts on learns them from this page or not at all.
