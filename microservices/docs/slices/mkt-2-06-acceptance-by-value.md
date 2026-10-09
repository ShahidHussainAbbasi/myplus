# Slice MKT-2-06 — time to accept by order value

**Status:** BUILT 2026-10-09. Unit-green (marketplace-service 429 tests, 0 failures, 7 new). No migration: the rules are
one row of `mkt_platform_setting`. Gate `mkt-2-06-acceptance-by-value.cy.js` **7/7 on a live stack**. Manual case
M-2-06 recorded (it replaces the design-only case of the same id).

Requirement: **MKT-R10.6** ("terms may vary by product type, order value, location, seller performance, delivery
promise, source"), this slice taking **order value**; with R10.5 (the default stays the configured minutes) and R22.1
(operator only). Depends on MKT-1e (the acceptance window, the countdown, the sweeper), MKT-2a (one part per seller) and
MKT-2b (a part moved to another seller). Out of scope: the other five dimensions of R10.6, each its own rule if asked
for; a rule per seller or per category.

## 1. Document

| | Before | After |
|---|---|---|
| The operator | one number: "Minutes a seller has to accept an order" (5) for every order | Platform → **Marketplace policies** → **Time to accept by order value**: up to 5 rules "Orders above Rs X → N minutes" (1–60). Add a rule, Remove, Save rules |
| A seller | 5 minutes to accept a Rs 300,000 order, the same as a Rs 3,000 one | a part above a rule's amount gets that rule's minutes; the countdown on Incoming marketplace orders and the shopper's "has 14:59 to confirm" start from it |
| A part at or under every amount, or no rules | — | exactly as before: the configured minutes |

**Which rule.** The highest amount the part's value is strictly **above**. Rs 104,000 with rules above 100,000 → 15 and
above 150,000 → 30 gets 15; exactly Rs 100,000 gets the base. A rule may also shorten the window (the operator's
choice, not a floor).

**Which value.** The value of the seller's **part** (`partTotal`: the sum of its lines' totals, before delivery), not the
basket: in a two-seller basket each seller is judged by what it must deliver. A part moved to another seller (2b) is
valued from its moved lines.

**When it is read.** Once, when the part is offered to its seller; the deadline is stored on the part
(`accept_by`). Changing the rules never moves an order already waiting (gate 2-06-05).

### 1a. Trace (RULE 0)

**Writers of `accept_by`: 2, both changed.** `MarketplaceCheckoutService` (the checkout, transaction 2) and
`MarketplaceShortageService.newPart` (2b's move). Both used `settings.acceptMinutes()`; both now use
`acceptMinutesFor(value)`. No other writer (searched `setAcceptBy`).

**Readers of `accept_by`: 4, none changed.** The sweeper's `findByAcceptanceStatusAndAcceptByBefore…` (expires it),
`SellerOrderService` expiry check, the seller's view (`SellerOrderView.acceptBy`) and `secondsLeft` (both countdowns).
They read the stored deadline, so a longer one is honoured everywhere with no change.

**Callers of `acceptMinutes()`: 3.** `operatorAcceptMinutes` (the operator's screen), the rules view (`baseMinutes`) and
`acceptMinutesFor` (the fallback). Its own meaning is unchanged: the minutes for a part above no rule.

**The setting.** Key `checkout.acceptTiers`, value `"100000.00:15;150000.00:30"`. 5 rules at most and amounts up to
Rs 100,000,000 keep it under the column's 255 characters. A value that cannot be read reads as no rules (the base
window), never as an error at checkout. Saved through `save()`, so the change is an audit event like every other
setting.

**The wire.** New `MarketplaceOrderDTOs.AcceptTier(above, minutes)` and `AcceptTiers(baseMinutes, tiers)`. The monolith
relays the JSON untouched (`Map<String,Object>`), so no twin DTO. A rule with no minutes arrives as null and is refused
in a sentence, not as a 500.

**Column types.** None added.

## 2. Design

`domain/AcceptanceByValue`: `of(list)` validates and sorts, `format()`/`parse()`, `minutesFor(value, base)`.
`MarketplaceSettingsService`: `acceptTiers()`, `acceptMinutesFor(value)`, `operatorAcceptTiers()`, `setAcceptTiers()`
(operator only for the last two). Refusals, in a sentence: "At most 5 rules.", "Each rule needs an amount above Rs 0, up
to Rs 100,000,000.", "Each rule's minutes are 1 to 60.", "Two rules have the same amount: Rs 100,000." Saved:
"Acceptance rules saved. They apply to orders placed from now on."

## 3. Screens

| Who | Where | What |
|---|---|---|
| Operator | Platform → Marketplace policies → **Time to accept by order value** | the rules (Orders above, Rs / Minutes to accept / Remove), Add a rule, Save rules, the sentence under them; "No rules: every order gets the minutes above." |
| Seller | Sale → Marketplace → Incoming marketplace orders | unchanged: the countdown starts from the part's window |
| Shopper | the order page | unchanged: "<shop> has 14:59 to confirm" |

## 4. Endpoints

| Method | Path (monolith → service) | Who |
|---|---|---|
| GET | `/platform/mkt/acceptTiers` → `/mkt/operator/settings/accept-tiers` | operator |
| POST | `/platform/mkt/acceptTiers` {tiers:[{above, minutes}]} → the same | operator; an empty list removes the rules |

## 5. Tests

- **Unit** `AcceptanceByValueTest` (4): minutesFor, shorter, refused, roundTrip. `MarketplaceOrderFlowTest` (3):
  windowByPartValue (two sellers in one basket, Rs 104,000 → 15, Rs 51,500 → 5), windowWithoutRules, tiersSaved.
- **Gate** `mkt-2-06-acceptance-by-value.cy.js` (7), `--env '{"mkt":"2-06"}'`: no rules (5 for Rs 156,000); the rules
  saved in the real form; 156,000 → 30, 104,000 → 15, 52,000 → 5 with two rules; the seller's countdown; a change never
  moves a waiting order; refusals (API and screen); a seller refused. Cleanup: rules emptied, window 5, orders rejected.
  It replaces the placeholder `mkt-2-multiseller-oms.cy.js`, whose last case (2-06) asserted a field that never existed;
  its other cases had already moved to the 2a–2c gates, so the file is deleted.
- **Manual** M-2-06.

## 6. Open items

- R10.6's other dimensions (product type, location, seller performance, delivery promise, source) are not built; each
  would be a rule of its own kind, read at the same two writers.
