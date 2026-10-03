# MKT live verification — 2026-10-03

What was run against a **live stack** (not stubs), what it found, and what was fixed. Slices MKT-0a, 1b, 1c, 1d, 1e,
and (section 6) MKT-1e2.

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
commit 90540dd0**, before any MKT-1e change. It is pre-existing and reported, not fixed here.

Monolith: MarketplacePublicControllerTest 6/6, ProxyErrorsTest 3/3. The six message bundles hold 2,867 keys each
and agree; each also carries 5 duplicate keys (`ui.js.always`, `ui.js.download`, `ui.js.loading`, `ui.js.reason`,
`ui.plan`), already present on both sides before this work.

## 5. Open

- **GAP:** a seller cannot switch cash on delivery off from any screen. `order.payment.codEnabled` lives in
  marketplace-service; the Configuration screen merges business-service and auth settings only.
- **GAP (go-live):** customer-facing terms, returns, complaint and COD pages do not exist yet (M-0a-05).
- **UX idea:** offer the IMEIs in stock as choices at Accept instead of free typing.

## 6. MKT-1e2 — customer account, online payment (sandbox), My orders, cancel

**Gates, one combined run on the 1e2 build (2026-10-03 16:28 UTC): 55 / 57.** 0a 8/8, 1c 11/11, 1d 10/10,
1e 10/10, **1e2 9/9**; 1b 7/9. The two 1b failures were a real defect (row 15 below); after the fix, **1b 9/9** on
the redeployed build — so every gate of 0a–1e2 has passed on the final code: **57 / 57**.

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

**Unit:** MarketplaceCustomerServiceTest 6/6, MarketplacePaymentServiceTest 6/6, MarketplaceOrderFlowTest 26/26,
MarketplaceCatalogServiceTest 14/14 (clean build). Monolith MarketplacePublicControllerTest 9/9. Six message bundles:
2,901 keys each, identical key sets.

**Open (1e2):** a real payment provider by configuration (the sandbox stays the default); SMS proof of phone; payouts
(MKT-1g).

