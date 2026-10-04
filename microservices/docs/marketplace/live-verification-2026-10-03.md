# MKT live verification — 2026-10-03

What was run against a **live stack** (not stubs), what it found, and what was fixed. Slices MKT-0a, 1b, 1c, 1d, 1e,
and (section 6) MKT-1e2, (section 7) MKT-1f.

## The stack

MySQL 8 in Docker (all 29 marketplace Flyway migrations — V29 added for MKT-1e2 applied under `ddl-auto=validate`), Redis, and the services
as jars: eureka, config, gateway, auth, notification, catalog, inventory, business, finance, audit, party, pharma,
marketplace; the monolith on :8080. Cypress 13.17.0 headed (Electron) in the official `cypress/included` image on the
host network.

## 1. Cypress gates — 48 / 48 in one combined run

One run, final build, 2026-10-03 13:06:28 UTC:

| Gate | Passed |
|---|---|
| `mkt-0a-capability-entitlement.cy.js` | 8 / 8 |
| `mkt-1b-product-match.cy.js` | 9 / 9 |
| `mkt-1c-offers.cy.js` | 11 / 11 |
| `mkt-1d-public-catalogue.cy.js` | 10 / 10 |
| `mkt-1e-checkout-acceptance.cy.js` | 10 / 10 |

## 2. The recorded manual walk

`cypress/e2e/marketplace/walk/mkt-walk.cy.js` (opt-in, `--env walk=1`) performs every manual case of the built
slices **step by step, as the manual page tells a person to**: each step's action is done on the screen, its expected
result is asserted, and the screen is captured; developer-tools steps record the request and the response. Each case
ends with its cleanup, performed and recorded the same way. The output (`cypress/walk/<id>.json` + screenshots) is
what the published page and `manual-cases.json` are built from, so the page can only state what was walked.

A step that has no screen yet is recorded as such — written from the implementation flow, with the test that proves
it (one today: M-1e-09 step 2, switching cash on delivery off).

Before a recording, `walk-reset.sql` resets the one shop that plays "never applied" (`owner.audit@`): accepting the
agreements is a permanent record by design, so the product itself has no way to undo it.

Result: **39 / 39 cases passed** in one recorded run on the final build (finished 2026-10-03 14:29:45 UTC): MKT-0a 5, 1b 7, 1c 9, 1d 8, 1e 10. The pictures and recorded calls are on the published manual-test page.

## 3. What the live runs found

| # | Found by | Defect | Fix | Test now |
|---|---|---|---|---|
| 1 | V28 on MySQL | the commas of two `KEY` lines sat inside a `--` comment: error 1064, the service could not start | commas before the comments | all 28 migrations applied on a scratch MySQL schema and rerun idempotently; the service started under `ddl-auto=validate`. FlywayMigrationTest **6/6 on real MySQL (Testcontainers)** |
| 2 | gate 1b, walk M-1b-01 | the identity key glued words to numbers ("Galaxy A32 Pro" → `A32PRO`), then — after the first fix — a model code to a unit ("A32 W70934" → `A32W70934`, W = watts) | only listed units, and a unit must END the word | ProductIdentityKeyTest `unitsOnly` |
| 3 | gate 1c | a 404's sentence was dropped by the monolith's proxy (93 call sites in 13 files) | `ProxyErrors` reads `DownstreamNotFoundException.getBody()` | ProxyErrorsTest |
| 4 | gate 1c | "Save" and "Save and send for approval" did nothing: `onclick="mktOfferSave(…)"` inside a form resolved to the button named `mktOfferSave` | bound in JS; repo audit: 0 other cases | gate 1c-01, walk M-1c-01 |
| 5 | gate 1e | after Accept the order vanished from "Waiting for you"; the seller never saw it go through | the row is redrawn in place from the server's answer | gate 1e-01, walk M-1e-01 |
| 6 | gate 1e | a late Accept on an EXPIRED order said "someone else changed this" | outcome checked before the version (accept and reject) | MarketplaceOrderFlowTest `expiredBeatsStaleVersion` |
| 7 | walk M-1c-01 | at 1366 px "My offers" (944 px) and the proposals table (923 px) overflowed a 900 px box | headers wrap | the walk asserts the table fits |
| 8 | walk M-1c-07 | the operator's Deactivate ignored the server's answer | shows the server's sentence | — |
| 9 | walk M-1d-04 | a page still loading wrote the old city into the box being typed in ("KarachiLahore") | never overwrite a focused box | walk M-1d-04 |
| 10 | walk M-1d-07 | muted text 4.43:1, under WCAG AA (axe) | `--muted` #475569 (7.05:1) | walk M-1d-07 (axe) |
| 11 | walk M-1d-08 | a search containing braces was read as a URI template: raw 500, "InternalError" on the page | values strictly encoded as URI variables (`encode().buildAndExpand`); a 5xx shows a sentence | MarketplacePublicControllerTest `strictEncoding`, `braceSearchRelayed` |
| 12 | wire trace, walk M-1e-05 | a lost answer (504, timeout) read as "no": the page dropped its attempt key and a second press placed a SECOND order | the proxy answers `outcome: UNKNOWN`; the page keeps the key | MarketplacePublicControllerTest ×3; walk M-1e-05 (504 injected after the server placed the order → one order) |
| 13 | walk M-1e-11 | a lapsed security token made Spring redirect the anonymous shopper to /login: "Save failed", never "This page expired" | `redirect: 'manual'`; a redirect reads as an expired page | walk M-1e-11 |
| 14 | walk M-1e-09 | the multi-seller checkout's COD refusal had no test | — | MarketplaceOrderFlowTest `codOffRefused` |

Also corrected: case texts that described what the product did not do (M-0a-02: the switch is greyed out with "Not in
plan", not refused on save; M-1e-10: an IMEI must have been received on a purchase to be sold), and gate-spec errors
(a substring check, a CSRF test that the suite's own `cy.request` override silently made valid, stock fixtures, and a
row lookup by price that matched an older offer).

## 4. Suites

`marketplace-service`: **296 run, 295 passed, 0 skipped** — Testcontainers ran against real Docker for the first time
(they were skipped before). The 1 failure, `OrderServiceTest.processing_a_return_returns_stock_and_refunds_a_card_order`
(the older single-store order path: `inventoryClient.returnStock` is never called), **fails identically on the base
commit 90540dd0**, before any MKT-1e change. *Closed in §6: the test was stale since O1; 315/315 now.*

Monolith: MarketplacePublicControllerTest 6/6, ProxyErrorsTest 3/3. The six message bundles hold 2,867 keys each
and agree; each also carries 5 duplicate keys (`ui.js.always`, `ui.js.download`, `ui.js.loading`, `ui.js.reason`,
`ui.plan`), already present on both sides before this work.

## 5. Open

- **GAP:** a seller cannot switch cash on delivery off from any screen. `order.payment.codEnabled` lives in
  marketplace-service; the Configuration screen merges business-service and auth settings only.
- **GAP (go-live):** customer-facing terms, returns, complaint and COD pages do not exist yet (M-0a-05).
- **UX idea:** offer the IMEIs in stock as choices at Accept instead of free typing.

## 6. MKT-1e2 — customer account, online payment (sandbox), My orders, cancel

**Gates: 57 / 57 in one combined run on the final, merged build (2026-10-03 19:14:44 UTC)** — 0a 8/8, 1b 9/9,
1c 11/11, 1d 10/10, 1e 10/10, **1e2 9/9**. The way there: the first combined run on the 1e2 build (16:28 UTC) was
55/57 — 1b-03/04 found a real defect (row 15). A later run was 55/57 again because the restarted stack lacked
pharma-service (1b-09 and 1c-03 save a prescription flag through it): an environment fault, not code; the restart
script now starts it.

**Recorded walk:** M-1e2-01..06 (account, lock, My orders + claim, cancel, pay online + refund once, declined card)
passed **6 / 6**; the full walk was then re-recorded in one run (see the manual page for its time and count).

**Forged identity, probed live through the gateway (:8765) and the monolith:** with no identity, with forged staff
headers (`X-User-Id`, `X-User-Roles: ROLE_ADMIN`, `X-Organization-Id`, `X-User-Email`), with those plus a guessed
`X-Internal-Secret`, with a random `X-Mkt-Session`, and with a forged `MKT_SESSION` cookie through the monolith — every
account route (`me`, `orders`, `orders/{no}/cancel`) answered **"Sign in to continue."** (5 / 5 probes). A cancel
POST without the page's CSRF token is redirected to `invalidSession.html`. Another customer's order number answers
"No such order." — the same as an order that does not exist (MarketplaceOrderFlowTest, line 575).

| # | Found by | Defect | Fix | Test now |
|---|---|---|---|---|
| 15 | regression run, gate 1b-03 | the operator's DECIDED lists (Matched, Rejected, Needs correction) were sorted oldest first; once Matched passed one page (79 rows), the decision just made was the one the operator could not see | waiting proposals stay oldest first (worked in order); decided lists are newest first — the same index read backwards, no migration | MarketplaceCatalogServiceTest `queueOrder`; gate 1b 9/9 |
| 16 | design trace (RULE 0, every writer) | a CARD order's sale reached the seller's books as COD: the rider could collect cash for an order already paid | the seller's sale carries `MARKETPLACE` for card orders; `paymentRef` only then | MarketplaceOrderFlowTest (card); gate 1e2-07 |
| 17 | design trace | a charge whose answer was lost could never be refunded (no provider id) | the PENDING fact is written before the provider is called; `reconcile()` looks the charge up by its idempotency key | MarketplacePaymentServiceTest `reconcileLostThenRefund` |
| 18 | design trace | the orphan rule could cancel an order already paid, keeping the money | every cancelling path (reject, expiry, orphan, customer) refunds, exactly once (`refund:` + charge id) | MarketplacePaymentServiceTest `refundOnce`; gate 1e2-07 |

Walk-only corrections (the walk's own timing, not the product): reading the order number before the confirmation was
drawn; counting a leftover order as a new one; clicking Accept while a filter change was still reloading the list.

**marketplace-service, full suite, clean build, Testcontainers on real Docker: 315 run, 315 passed, 0 skipped**
(FlywayMigrationTest 6/6 with V29). The failure reported in §4 is closed: `OrderServiceTest.processing_a_return_…`
still expected the pre-O1 inventory return (`returnStock("resv-1")`), but since O1 a storefront order is a sale in
the store's books with no reservation — a return voids that invoice, which returns stock and revenue together. The
code was right; the test now asserts `reverseSale(invoice)` once, `returnStock` never (or stock would come back
twice), and books REVERSED.

**Merge with feature/expense-management (b625773a):** no conflicts; marketplace files untouched by it; six bundles
agree key for key. The branch did not compile `education-service` (`NotifyAsyncConfig` closed its Javadoc twice) —
fixed, NotifyAsyncConfigTest 2/2.

**Unit:** MarketplaceCustomerServiceTest 6/6, MarketplacePaymentServiceTest 6/6, MarketplaceOrderFlowTest 26/26,
MarketplaceCatalogServiceTest 14/14 (clean build). Monolith MarketplacePublicControllerTest 9/9. Six message bundles:
2,901 keys each, identical key sets.

**Open (1e2):** a real payment provider by configuration (the sandbox stays the default); SMS proof of phone; payouts
(MKT-1g).

## 7. MKT-1f — support cases and marketplace returns

**V30** applied to a structural copy of the live schema twice (idempotent), then live: `now at version v30`;
marketplace-service started under `ddl-auto=validate`.

**Gates: 69 / 69 in one combined run on the final build (2026-10-03 22:27:32 UTC)** — 0a 8, 1b 9, 1c 11, 1d 10,
1e 10, 1e2 9, **1f 12**. The way there: 1f's first live run was 11/12 (row 22); the first combined run was 66/69
(row 21).

| # | Found by | Defect | Fix | Test now |
|---|---|---|---|---|
| 19 | slice trace (5 writers of the store order's return states) | a shopper could request a return straight on the seller's store order (`/storefront/return`: sequential id + phone), bypassing MaxTheService (R8.2) | refused for marketplace orders, after the phone check so it never reveals that an order exists | gate 1f-09, walk M-1f-07 |
| 20 | slice trace | the seller's own "Process return" voided the sale in their books while the customer's online payment was never refunded (the store order is PENDING, so its card refund never ran) and the marketplace never heard | refused for marketplace orders; a marketplace return raises a CREDIT NOTE on the invoice (`returnLines`, quarantine when faulty) and then refunds, once | MarketplaceSupportServiceTest ×4, gate 1f-04, walk M-1f-03 |
| 21 | combined regression, gate 1c-05 | the operator's APPROVED offer list was oldest first; past one page (122 rows) the offer just approved was not on it — the class of row 15 | audit of all 12 ascending list queries: work queues keep oldest first, per-order lists are bounded, the two unbounded history lists (decided offers, resolved cases) read newest first | MarketplaceOfferServiceTest `queueOrder`, MarketplaceSupportServiceTest `resolvedNewestFirst` |
| 22 | gate 1f-04 (first live run) | the customer's view of a return named "MaxTheService support" as the cost bearer's organisation — a party that is not the bearer | the customer sees the bearer's role (it explains a fee they pay), never the organisation; the operator sees both | gate 1f-04 |
| 23 | slice trace (readers of `SellerOrderView`) | the 1e2 gate filtered the seller's rows on `paymentMode`, a field the view did not have — an assertion that could never fail | the view carries `paymentMode` (and `storeOrderId`, `deliveredAt`) | gate 1e2-08 now meaningful |
| 24 | slice trace | the seller's Accept answer said "deliver and collect the cash" on orders already paid online | a card order's answer says PAID ONLINE: do not collect cash | — |
| 25 | slice trace (G-16) | marketplace-service wrote no audit row for any marketplace action (R22.4) | adopts `common-audit` (`AuditEmitter`, V30 `audit_outbox`); every support/return action filed under the seller, actor type stated | gate 1f-12, MarketplaceSupportServiceTest `decisionAudited`, walk M-1f-05 |
| 26 | slice trace | a lost card refund for a return would never be retried: `reconcile()` routed every pending refund to `refundIfCancelled`, which ignores delivered orders | refunds keyed `return:` are routed to `refundReturn` | MarketplaceSupportServiceTest `lostRefundNoSecondCreditNote` |
| 27 | recorded walk M-1f-04 | My orders drew itself twice on load; a prompt "Get help" click lost its form to the second draw | loads run one at a time | walk M-1f-04 |

| 28 | live check of the audit outbox after the regression | every CUSTOMER event (case opened, return opened, order cancelled) stayed undelivered — 403 from audit-service, which authenticates only a call with a user id, and a customer is not a staff user | events with no staff user are delivered under user id 0 (never a person); actor type SYSTEM and "by the customer" say who acted. The shared filter is untouched | MarketplaceAuditServiceTest ×2; live: **352 audit rows, all POSTED, 0 failed** |
| 29 | trace of R22.4 across the built slices (G-16) | agreements, seller/match/offer decisions, price limits, accept/reject, customer cancel, policies and settings wrote no audit row | each writes one in the same transaction as the change, under the seller it concerns; a refused action records nothing | MarketplaceOrderFlowTest `ordersAudited`; gates 69/69 on the audited build (22:56:12 UTC) |

**marketplace-service suite: 330 run, 0 failed, 0 skipped** (clean build; FlywayMigrationTest 6/6 to v30 on real MySQL).

Also corrected before build: the return-days edit first added for the gate broke MKT-1c's promise that policies are
never edited — removed; gate 1f-06/07 use new policies instead.

**Unit:** MarketplaceSupportServiceTest 14/14, MarketplaceOfferServiceTest 12/12, order flow 26, payment 6, customer 6,
catalog 14, checkout 14. Monolith MarketplacePublicControllerTest 10/10. Six bundles: 2,960 keys each, identical.

**Rulings applied:** R-MKT-12 (cash on delivery: the rider hands the cash back at pickup), R-MKT-13 (the seller's
rider collects), R-MKT-14 (change of mind per the snapshotted return days; the customer bears the pickup fee,
operator-set, default Rs 250).

**Open (1f):** a support case for an order not in an account (by number + phone) — the data model allows it, the
screens do not yet; write-off accounting beyond quarantine (Phase 1 keeps written-off goods out of sellable stock);
returns feed the settlement ledger in MKT-1g.


## 8. MKT-1g — settlement, payouts, commission in the books (run 2026-10-04)

**The stack:** the same set of services, built from `feature/expense-management` at deba05fd plus this change, on
a fresh MySQL 8 (all 31 marketplace migrations applied under `ddl-auto=validate`). **The clock:** settlement only
happens on a business day, and the run fell on a Sunday, so every JVM (services and monolith) ran under libfaketime
one day ahead (Monday 2026-10-05; `FAKETIME=+1d FAKETIME_FORCE_MONOTONIC_FIX=1`: without the fix flag Java's timed
waits spin). The browser kept the real clock; nothing on these screens depends on it. A second operator,
`ops2@myplus.com`, was added by `walk-reset.sql` because a payout needs two people.

**Gate `mkt-1g-settlement.cy.js`: 6 / 6. All marketplace gates: 75 / 75 in one combined run on the final build
(0a 8, 1b 9, 1c 11, 1d 10, 1e 10, 1e2 9, 1f 12, 1g 6).** The first live run of 1g was 5/6 (row 30); the first combined
run was 74/75 (row 34).

**Walk M-1g-01..06: 6 / 6 recorded** (step by step, each expected result asserted, screens captured, cleanup done).

| # | Found by | Defect | Fix | Test now |
|---|---|---|---|---|
| 30 | gate 1g-06 (first live run) | the trial-balance check read a `balance` field the trial balance does not have (it returns `rows` of `{code, debit, credit}`), so the money assertion could never pass; the journals themselves had posted correctly | reads credit − debit from the rows; the weekend check asks the server (the line's status and date), not the browser's clock | gate 1g-06, walk M-1g-06 |
| 31 | walk M-1g-01 | "Book commission in my organisation" never hid, even with the books already the operator's: `theme.css` gives every `.btn` `display: inline-flex !important`, which beats jQuery's `.hide()` | the button is shown and hidden through a wrapper | walk M-1g-01 |
| 32 | walk M-1g-01 | "Settle what is due now" answered "Saved." | it says how many lines settled, still wait, or are on hold (six bundles) | walk M-1g-01, -02, -03, -06 |
| 33 | gate 1g-04 + walk M-1g-04 | a payout requested by mistake cannot be withdrawn, only approved and paid; the gate leaves one REQUESTED on every run | the walk finishes a left-over payout with the second operator before it starts | — (open, below) |
| 34 | combined regression, gate 0a-07 | on a fresh system the gate that first made Seller A a seller named its shop: the shared helper's default called it "MKT gate seller" when the 1g gate ran first, and 0a-07 (which reads "Shahzad Mobile Shop") failed from then on, since accepting the agreements fixes the name | the helper names each persona's shop the same whichever gate runs first (this environment's name was corrected once by SQL) | gates 75/75 in one run |

**Open (1g):**
- A waiting line's payable date is computed from the T+N setting in force, so raising T+N moves dates sellers were
  already shown (seen in walk M-1g-03). Recommendation: fix the date when the line starts waiting.
- No way to withdraw a payout request (row 33).
- Row 31's cause is app-wide: any `.btn` hidden with `.hide()`/`.toggle()` stays visible. Only this button was
  fixed here; the other call sites need an audit.
- R-MKT-9 (commission invoice and its tax) is still unruled; commission tax is zero.
