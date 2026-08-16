# Full-suite repair pass — 2026-08-15

**Branch:** `feature/UI-UX`  **Mode:** Docker (22 containers)  **Suite:** 211 specs; this pass covers
`business/` + `ui/` + `pharmacy/` (141).

Goal was "run everything and fix until green". This records what was actually found, what was fixed,
what is blocked on a rebuild, and what is still open. Nothing here was committed.

---

> ## ✅ RE-VERIFIED — 12 repaired specs, 85 tests, **85 passing, 0 failing** (`All specs passed!`)
>
> `period-close` · `multi-location` · `team-admin-reassign` · `org-config` · `gl` · `order-return` ·
> `commerce-gaps` · `negative` · `contract-price-charged` · `price-rules-screen` · `vender` ·
> `catalog-product`.
>
> Of these, `period-close` green proves §1.2, `gl` green proves §1.1, and `multi-location` green proves
> both halves of §3.2 (the `replace:true` grants AND the session-cache drop). **`contract-price-charged`
> passed untouched** — it was collateral from the locked books of §3.1, never a defect, which is why the
> plausible "the cart lost its line" theory was deliberately not acted on.
>
> Earlier totals from the same pass: 53 request-driven specs → 48 passed / 162 tests green; the first
> sweep → 60 specs / 313 tests green.

> **DEPLOYED 17:19–17:21 local.** monolith, business-service and auth-service were rebuilt and restarted
> with these changes. Verified live, not assumed:
> * `flyway_schema_history` shows **V6 `refresh token per session` success=1**; `refresh_tokens` now has a
>   plain `idx_refresh_tokens_user`, the unique on `user_id` gone, the unique on `token` kept.
> * Two logins as one account: **both refresh successfully** (session 1 was rejected before the fix), and
>   the user holds exactly **5** rows — the session cap working.
> * Tax Codes renders **"Rate %"** (`ui.ratePercent`); deployed `business.js` carries `ui.js.statementFor`.
> * PERF-1 compression is live and effective (`business.js` 216,619 → 63,403, 71%). PERF-2 content-hashed
>   URLs and PERF-4 lazy export are also deployed — the dashboard loads **no eager pdfmake/vfs_fonts**.

## 1. Product defects found (all fixed; deployed and verified — see the note above)

### 1.1 A refresh token was per-USER, not per-session — a second login killed the first device

`RefreshToken.user` was `@OneToOne`, giving `UNIQUE(user_id)`: exactly one refresh token per user, and
`createRefreshToken` overwrote its token string on every login. Lookup is `findByToken(String)`, so the
previously signed-in device's token simply stopped existing. The access token lives **15 minutes**
(`jwt.access-token-expiration-ms: 900000`), so the older session did not fail immediately — it failed a
quarter of an hour later with "Invalid refresh token".

**Proved, not inferred:** two logins as one account, then refresh with session 1's token → rejected;
session 2 → fine.

*Business impact:* a till and the owner's phone cannot both stay signed in. Whichever signed in first
silently loses the ability to refresh. Normal for retail; invisible to every unit test.

*Why it stayed hidden:* `GatewayClient.refreshAccessToken()` returned `false` silently from all three
failure paths, and callers such as `GlController` turn the resulting 401 into a bare
`{"status":"ERROR"}`. Symptom and cause sat in different files with nothing connecting them.

**Fix** — the standard model, one row per session:
`@ManyToOne` + `V6__refresh_token_per_session.sql` (creates the plain index BEFORE dropping the unique,
because MySQL refuses to drop the last index covering an FK column; resolves names through
`information_schema` since the unique key was Hibernate-named). `findByUser` →
`findByUserOrderByExpiryDateAsc` (the Optional form would throw `NonUniqueResultException` on a second
device). New row per login with oldest-first eviction beyond `jwt.max-sessions-per-user` (default 5).
All four public signatures unchanged ⇒ **no caller edited**. `refreshAccessToken()` now logs at every exit.

**Migration verified on a scratch copy of the real DDL**: unique-on-user_id gone, plain index present,
unique-on-token preserved, two rows for one user insert, re-run idempotent.

*Deliberately deferred, each its own slice:* rotation-on-use; per-session logout (today logging out on a
phone signs out the till); reuse detection.

### 1.2 A business refusal poisoned the caller's transaction

Voiding an invoice in a closed period answered
`{"status":"ERROR","message":"Transaction silently rolled back because it has been marked as rollback-only"}`
instead of the intended `FAILED — …the period is closed`.

`SellController.voidSell` is `@Transactional` and calls `SaleVoidService.voidInvoice`, also
`@Transactional`, which therefore participates in the caller's transaction. The guard throws
`PeriodClosedException` (a RuntimeException), the inner proxy marks the shared transaction rollback-only,
the controller catches it and returns its tidy response — and Spring throws `UnexpectedRollbackException`
at commit. **The controller's catch block was dead code for its stated purpose.** It affected all three
reasons a void is refused: already-void, return-already-recorded, period-closed.

*The tell:* the three sibling endpoints (`addSell`, `updateSell`, `saleReturn`) are `@Transactional`, catch
the same exception, and work — because they call `periodLockGuard.assertOpen` **inline in the controller**,
where no inner proxy exists to mark anything. Identical code one call deeper behaves differently.

**Fix:** `@Transactional(noRollbackFor = { VoidRefused.class, PeriodClosedException.class })`. Correct
because both are thrown by guard clauses before the method writes anything.

⚠ Attribute name depends on the annotation: Spring's → `noRollbackFor`; `jakarta.transaction` →
`dontRollbackOn`. **This codebase uses both.**

### 1.3 Duplicate i18n keys silently overrode live labels

All six bundles were perfectly key-aligned (0 missing / 0 extra) while **8 keys were declared twice in
each file**. Last definition wins, so later slices had quietly overwritten earlier labels:

| Key | Was | Overridden by | Symptom |
|---|---|---|---|
| `ui.rate` | `Rate %` | `Rate` (quote slice) | Tax Codes header + placeholder lost the `%` |
| `ui.js.statement` | `Statement — ` | `Statement` (student portal) | dialog read "StatementAcme Traders" |
| `label.name` | `Name` | `" name"` (stray, product block) | donator / doctor / hospital showed " name" |

**Fix:** one key per meaning, by RENAMING the duplicate (`ui.ratePercent`, `ui.js.statementFor`) so every
language keeps its own translation; the five harmless identical duplicates removed. Only English carried
the trailing space in `"Statement — "`, so the separator is now supplied by the JS join — a trailing space
is invisible in review and stripped by editors, so it cannot be a contract.

Verified after: 0 missing, 0 extra, **0 duplicates**, each new key present once in all six.

**Key parity is not enough — check `sort | uniq -d` on every bundle.**

---

## 2. Infrastructure defect (fixed and already live)

`notification-service` could never start in Docker. It was the only DB-backed service in
`docker-compose.yml` missing both the `*db-env` anchor and `depends_on: mysql`, so `${DB_HOST:localhost}`
resolved to localhost *inside its own container*; Flyway retried "Connection refused" forever and the
service never registered with Eureka. Added both; container recreated (no rebuild needed) and **healthy in
24.8s**. Four specs depend on it.

---

## 3. Test-hygiene defects (spec-side; these corrupt whole runs)

### 3.1 A failing spec left the books LOCKED for everything after it

`period-close.cy.js` reopened the books **inside the test body**, so the failure in §1.2 meant
`setLock(null)` never ran. The lock is SERVER state — `testIsolation` resets the browser, never the server
— so every later sale-writing spec failed for a reason of this spec's making, starting with
`pos-enter-chain`'s "a FULLY PAID sale completes with no customer named". **One real defect became a wave
of unrelated red.** Cleared the stale row; added an `after()` that always reopens and re-authenticates
first (teardown must re-login, or it 302s to /login and silently leaves the switch set).

Same shape fixed in `org-config.cy.js` (left `pos.receipt.showTaxBreakdown` false, which
`receipt-tax-breakdown.cy.js` asserts) and `team-admin-reassign.cy.js` (left cashier.a with no stores).

### 3.2 multi-location inherited grants it never established

Cashier A held **two** store grants — one pointing at a store that no longer exists — because the spec
granted ADDITIVELY while claiming to be idempotent. `addLocationClaims` only auto-resolves an active store
when a user holds **exactly one** grant, so the sale was written `store_id NULL`; T1 read `Number(null)`
as 0. T6b then failed **as a consequence** — an unstamped row is legacy-shaped and T8's deliberate
NULL-fallback makes it readable by any store admin, so it was never a real IDOR.

Fixed with the product's existing `replace: true` (authority-checked: an admin may only revoke stores they
hold). **And** a session-cache drop afterwards — location data lives in JWT claims minted at login, and
`cy.session(cacheAcrossSpecs)` would otherwise restore a token minted under the old grants, changing the
database while changing nothing observable.

### 3.3 An illegal state transition the server has refused since O2

`order-return.cy.js` drove `NEW → DELIVERED`. `FulfilmentStatus.ALLOWED` permits `NEW → {PACKED,
CANCELLED}`, and O5b made SHIPPED **derived** from parcels, so DELIVERED is reachable only from SHIPPED /
PARTIALLY_SHIPPED. Replaced with the legal route: PACKED → `/shipOrder` → DELIVERED. Its assertion was also
blind (`expect(success).to.not.eq(false)` printing "expected false to not equal false"); every step now
prints the body. A sweep confirmed the other `SHIPPED`/`DELIVERED` call sites are deliberate refusal
assertions, not drift.

### 3.4 A seeded sale cannot name its own price

`commerce-gaps` G2 correctly derived a debt large enough to top the dashboard's top-ten, but put the figure
on the **sale line**. `addSell` re-quotes every line from the catalog and price rules (B2B-P2), so the
invoice was raised at the product's real value: the customer owed 234 against a top-ten floor of 300, and
the dashboard read 0 while the debt genuinely existed — and the request still returned SUCCESS, so nothing
announced the discard. The figure now goes on the **product**.

**General form: when seeding through a server that owns a calculation, set the INPUTS it reads, never the
OUTPUT you want.**

### 3.5 bootstrap-select drift

`searchable-selects.js` converts effectively every `<select>` into a bootstrap-select (skipping only nav
headings, `[data-no-search]`, DataTables-owned and `_length`), hiding the native element. Fixed
`negative`, `price-rules-screen`, `sell`, `vender`. The distinction that matters: `{force:true}` where the
intent is *set a value*; `.next('.bootstrap-select')` where it is *the operator can see this*.

`catalog-product` typed into a modal the instant `.open` was added — mid-transition; now waits for the
field, matching the file's own idiom.

It reaches further than clicks and typing: `ui/responsive.cy.js` MEASURED a `<select>`'s width. A hidden
element measures **0**, so "#rxMedicine is usably wide on a tablet: expected 0 to be at least 120" read as
a cramped tablet layout when the rendered control was the right size. Now measured via a `usableWidth()`
helper that prefers the `.bootstrap-select` wrapper and falls back to the element. The `<input>` fields in
the same loops were never affected — which is the tell that it was the selects, not the layout.

### 3.6 ⚠ UNRESOLVED — the product grid reports header 14 / row 13

`product-crud`, `product-last-rates` and `product-manufacturer` all fail on the product grid's column
count. **I changed my mind twice here and neither earlier explanation survived**, so it is recorded as
open rather than closed:

* First read: a genuine column shift (the thing `business.js` warns about in a comment). Ruled out — the
  row builder pushes exactly **14** entries for the 14-column header, the DEPLOYED `business.js` is
  **byte-identical** to the tree (216,619 bytes), the grid renders correctly, and a real shift makes
  DataTables throw *"Requested unknown parameter"*, which never appeared.
* Second read: DataTables Responsive dropping cells at the 1280px viewport, so compare visible-to-visible.
  Ruled out — that produced **12 vs 8**, a wider gap than the original, i.e. the two `:visible`
  populations do not correspond either.

Both assertions now compare **all `th` vs all `td`** — the only coherent basis, since the hazard is array
length and CSS visibility is irrelevant to it. On this environment that states the problem plainly:
**header 14, row 13.** Deliberately NOT loosened to go green: these cases exist precisely to catch a
row/header mismatch, so tuning them would disable the guard. Needs devtools on a headed run to see which
cell is absent at runtime.

---

## 3b. ⚠ NOT FIXED — a live money defect: the storefront quotes tax the books never record

Found by `storefront-checkout` and `storefront-coupon`. **Deliberately not patched** — it is a
revenue-recognition decision, and editing the specs to expect the wrong figure would bury it.

**What the data says** (marketplace `orders`, all placed today):

| order | sub_total | tax_total | shipping_fee | discount | **total** | should be |
|---|---|---|---|---|---|---|
| Checkout Buyer | 20 | **2** | 0 | 0 | **20** | 22 |
| Coupon Buyer | 20 | 0 | 0 | **2** | **20** | 18 |
| SagaBuyer | 40 | 0 | **5** | 0 | **40** | 45 |

`total` equals `sub_total` every time. The components are stored correctly and then ignored — each row is
internally inconsistent (20 + 2 ≠ 20).

**Mechanism, traced end to end.** `CheckoutService.quote` and `.place` compute the grand total with the
*same* formula (`subtotal − discount + tax + shipping`), and `place` sets it on the DTO. But
`OrderService.placePublic` then stores `.total(charged)` where
`charged = sale.getGrandTotal()` — **the order adopts the INVOICE's total**, and the invoice is 20:
org 23's `INV-000061` is `sub_total 20, tax_total 0.00, grand_total 20`.

**Why the invoice has no tax — and it is the BOOKS that are right.**

> ⚠ An earlier draft of this section blamed the books (`taxCodeId` vs `tax_rate`) and proposed the wrong
> remedy. Reading further disproved it. Recording the correction because the fix direction inverts.

`SagaSellService` **does** read the catalog rate — `product.getTaxRate()` — exactly as the multi-rate
design intended ("tax classes → `ProductRef.taxRate` so the hot path is UNCHANGED"). It then passes it to
`TaxService.taxForLine`, which is gated on a **per-tenant switch**:

```java
if (setting == null || !Boolean.TRUE.equals(setting.getEnabled())) { /* whole amount net, zero tax */ }
```

and the default for a missing row is `enabled(false)`. The `tax_setting` table holds **exactly one row —
org 6**. Org 23 has none, so tax is OFF for that tenant and a zero-tax invoice is **correct, intended
behaviour**.

**The storefront is the side that is wrong.** `CheckoutService.totals()` computes
`net × item.taxRate / 100` unconditionally, with no reference to the org's tax switch — a **second,
independent tax engine**. Its own comment claims "both quote() and place() reach this one method, which
is what keeps a shown price and a charged price identical"; that holds inside marketplace, but the
charged price ultimately comes from the invoice, which uses the other engine. The guarantee is broken one
layer up.

Note the near-miss: the shipping fee on the very next line **does** take the tenant
(`shippingPolicy.feeFor(option, subtotal, org)`) — O3's own lesson that *a policy read on a public path
must name its tenant*. Tax was simply never brought under that rule.

**Consequences:** a shop with tax OFF still shows online shoppers a tax line and quotes 22, then invoices
20 — the customer is over-quoted and the books are fine. Separately, `SaleRecordRequest` carries **no
shipping-fee and no order-level discount field**, so delivery income and coupon discounts cannot reach the
books at all (hence shipping 5 / discount 2 with `total == sub_total`).

**The fix** is no longer a choice between two engines — it is to delete one:
1. marketplace resolves tax through the **same org-aware policy** the books use (a trade-contract op, or
   the org's tax setting read via `common-settings`, mirroring how `shippingPolicy` already takes `org`);
2. and the contract gains shipping + order-level discount, or online delivery income stays off the P&L.

Both still deserve their own slice and gate — this is revenue recognition — but the direction is settled:
**one tax engine, tenant-aware, shared.**

## 3c. FIXED — a CRUD modal's close **×** was unclickable, PERMANENTLY (not for 200ms)

> ⚠ **Correction.** This section first said the trap lasted only the ~200ms of the entrance animation and
> that a human would never hit it. **Wrong, and the error mattered** — it downgraded a live, always-present
> defect to a test artefact. Proof: a test that waits for the animation to reach `playState === 'finished'`
> and only then clicks **still fails**, covered by `.app-sidebar__toggle`.
>
> The reason is the fill-mode. An element whose animation affects opacity creates a stacking context while
> that animation is running **or FILLING**, and `animation-fill-mode: both` fills for as long as the element
> exists. `.formDiv` therefore held a **permanent** stacking context, so `.crud-overlay` (z-index 1050) was
> trapped inside a section painted below `.app-sidebar` (z-index 1040) forever — the close × sat under the
> sidebar's collapse button and `elementFromPoint`, which real clicks and Cypress both use, returned the
> sidebar. **A shopkeeper could not close that modal with the ×.**
>
> **Fix:** drop `both` from `.formDiv`'s animation in `theme.css`. The fade is unchanged (the keyframes end
> at opacity 1, the element's natural value) and the stacking context is released when the animation ends.
> ⚠ CSS ships inside the jar → needs a monolith rebuild.
>
> Two lessons: **"only during the animation" is wrong whenever a fill-mode is set**; and a filled animation
> stays in `getAnimations()` for ever, so waiting for that list to EMPTY never succeeds — check `playState`.

### (original diagnosis, kept for the mechanism)

Found by `product-crud` ("New opens the Product form modal…"): Cypress refused the click because
`<button class="crud-x">×</button>` "is being covered by" `<button class="app-sidebar__toggle">`. The
screenshot shows the modal rendered correctly, with its × at the top-left — horizontally overlapping the
sidebar.

**Mechanism** (`theme.css`): `.formDiv { animation: sectionIn .2s ease both; }` where
`@keyframes sectionIn` animates **opacity**. An element with a running opacity animation **creates a
stacking context**, so for those 200ms the section becomes the containing block for its
`position:fixed` descendants — trapping `.crud-overlay` (z-index **1050**) *inside a section that itself
sits below* `.app-sidebar` (z-index **1040**). The sidebar therefore paints over the modal's left edge,
and `elementFromPoint` — which is what both Cypress and a real click use — returns the sidebar toggle.

**The rule directly above it already guards against this for `transform`:** *"a `transform` on .formDiv
would make it the containing block for any position:fixed descendant (the shared CRUD modal overlays),
trapping them inside the section instead of the viewport."* The author avoided `transform` and reached
for an opacity fade, which has the same effect while it is running.

**Impact:** transient and timing-dependent — a human rarely clicks within 200ms of a section appearing,
which is why it surfaces on a loaded box and in tests rather than in use. But it is a real layering flaw,
not a test artefact.

**Not fixed here** because every remedy carries real risk and belongs to whoever owns the layout:
dropping/retiming the entrance animation changes UX; adding a `z-index` to `.formDiv` makes the trapping
permanent rather than transient; rendering the modals outside `.formDiv` is the correct end state but
touches every CRUD modal in the dashboard. Confirming it needs a headed browser where the computed
stacking can be read in devtools.

## 3d. Pharmacy insurance co-pay field — REMOVED by decision (2026-08-16)

`#sellInsured` (the insurer-covered amount on the sale screen, P12 slice 59) had been **commented out in
the template while every consumer of it stayed**: `$("#sellInsured")` is still read by `business.js`
(change calculation), `main.js` (the sale payload) and twice by `pos-keyboard.js` (a declared stop on the
checkout Enter chain). jQuery answers `undefined` for a missing element, so `val() * ONE || 0` quietly
settled on 0 — a pharmacist could pick the INSURANCE tender and had nowhere to say what the insurer
covered, so the whole amount fell to the patient. **Three tests across two specs** had been red on it:
`pharmacy/insurance-copay.cy.js` (1) and `business/vertical-fields.cy.js` (2 — including the *negative*
"POS/retail does not show it" case, which still required the element to exist and be vertical-gated).

It was briefly restored during this pass, then **removed outright on the owner's decision**. The
commented block is deleted rather than left as cruft.

* **The INSURANCE tender stays.** A pharmacy can still settle against an insurer; it simply does not
  split the amount on this screen. Removing the tender as well would be a larger product change.
* **The three tests now assert ABSENCE** (`should('not.exist')`) rather than being deleted, so the
  decision stays visible — if the field returns, they fail and whoever brings it back has to say why.
* ⬜ **Residual:** the four JS readers are now dead references. All are guarded (`|| 0`, and the Enter
  chain skips absent fields) so they are harmless, but removing them touches sale-total arithmetic and
  deserves its own change rather than riding along with a test repair.

## 4. Still open

* **`contract-price-charged`** — "the line is still in the cart: expected undefined to exist".
  `requoteSellCart` mutates in place and never drops lines, so the add most likely never happened. That is
  a guess; not edited on it. Needs an isolated re-run.
* **`pos-quickpick` / `pos-sale-endtoend` stalls.** First read as a wedged renderer; **that was wrong** —
  "zero requests to the monolith" proves nothing (keyboard-only tests are silent on the wire by design) and
  "no output" only means no case has finished. Measured: passing cases took 33s / 15s / 18s against 1–3s
  healthy, i.e. **~10× slow**, with container CPU at only ~10% (so the host, not the stack). A later 32-minute
  gap on one case whose siblings took ~20s does look like a genuine wedge. Needs a headed run where the
  screen can be seen. **Time a known-fast spec first to tell slow from frozen.**
* **`gl.cy.js`** failed on an expired JWT — expected to clear once §1.1 is deployed.
* **`period-close`** cannot pass until business-service carries §1.2.

---

## 5. Rebuild + verify

```bash
mvn -pl commerce-contracts install -DskipTests          # install, never just package
mvn -pl business-service,auth-service -am clean package -DskipTests
mvn clean install -DskipTests                            # monolith: i18n, template, GatewayClient
```
Rebuild the **images** (Docker mode: `mvn package` alone leaves containers on old code), then restart
business-service, auth-service (V6 applies at startup) and the monolith.

Then re-run the fixed set:

```
npx cypress run --headed --browser chrome --spec "cypress/e2e/business/period-close.cy.js,cypress/e2e/business/multi-location.cy.js,cypress/e2e/business/order-return.cy.js,cypress/e2e/business/commerce-gaps.cy.js,cypress/e2e/business/negative.cy.js,cypress/e2e/business/catalog-product.cy.js,cypress/e2e/business/gl.cy.js,cypress/e2e/business/contract-price-charged.cy.js"
```

`period-close` **must** run before anything that writes a sale, and its new `after()` must be observed to
reopen the books — that is the guard against §3.1 recurring.
