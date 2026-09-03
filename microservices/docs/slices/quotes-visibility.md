# Quotes: restrict visibility to order bookers and their management (task #27)

**Status:** IMPLEMENTED, awaiting gate — `cypress/e2e/business/quote-visibility.cy.js`.
Raised by the user: *"dealerPricing — Quotes should be visible to only order booker created by
distributer/marketplace owner/admin"*.

---

## 1. What gates Quotes today

| Layer | Gate |
|---|---|
| Nav entry + `#QuoteDiv` | `data-capability="dealerPricing"` only |
| `getUserQuotes`, `getQuote`, `addQuote`, `sendQuote`, `submitQuoteForApproval`, `acceptQuote`, `rejectQuote`, `convertQuote` | **none** |
| `approveQuote` | `ROLE_OWNER` / `ADMIN_PRIVILEGE` / `SUPER_PRIVILEGE` |

So **any user in a tenant with `dealerPricing` can see, create, send and convert quotes.** Only *approval* is
privileged — which is deliberate and correct (approving your own discount is the thing worth stopping), but
it leaves everything else open.

## 2. What the ask means, and the ambiguity in it

"Visible to only order booker created by distributor/marketplace owner/admin" reads two ways:

**(a)** Only the **ORDER_BOOKER** role may use Quotes — plus the owner/admin who manage them.
**(b)** A booker sees only the quotes **they** created; the owner/admin see all.

These are different controls and both may be wanted. (a) is *who reaches the screen*; (b) is *whose rows they
see*. **⚠ I need the ruling before building** — (b) alone would leave a booker able to see a quote by id even
if it were not listed for them.

My reading is that **(a) is the ask and (b) is implied**, because a quote is a rep's own working document —
but I would rather ask than assume, since getting it wrong either hides work from someone who needs it or
exposes another rep's pricing.

## 3. What already exists to build on

- **`ROLE_ORDER_BOOKER`** exists (`booker.marketplace@myplus.com`, OMS O7 D2), and its defining property is
  that it carries **no `ADMIN_PRIVILEGE`** — it is exactly the "field rep" role this ask describes.
- **`requestUtil.callerSeesWholeOrg()`** already expresses "owner/admin see the whole org; a plain user sees
  only their own" — the same rule `visibleSells()` and `visiblePurchases()` use. **(b) is that rule applied to
  quotes**, not new machinery.
- `SalesQuote` carries `userId`, so "mine" is answerable without a schema change.

## 4. Design, if (a) + (b)

**(a) Reach** — `@PreAuthorize` on the quote endpoints for
`ROLE_ORDER_BOOKER`, `ROLE_OWNER`, `ADMIN_PRIVILEGE`, `SUPER_PRIVILEGE`, plus the same list as
`sec:authorize` on the nav entry and `#QuoteDiv`.

⚠ **The screen gate is presentation, never protection.** `data-capability` and `sec:authorize` hide a menu;
they do not stop a request. The endpoints are what actually enforce this, and the gate must assert the
ENVELOPE on a refused call, not the absence of a menu item.

**(b) Rows** — `getUserQuotes` narrows by `callerSeesWholeOrg()`: whole org for owner/admin, own rows for a
booker. And `getQuote` needs the same rule per record — the anti-IDOR lesson from #15: a list that filters
while a by-id read does not is not a restriction, it is an inconvenience.

## 5. What must NOT change

- **`approveQuote` stays owner/admin.** A booker must not approve their own discount, and that is the one
  control already correct here.
- **A booker must still be able to work**: create, send, submit for approval, and see the outcome. Restricting
  visibility must not leave a rep unable to see the quote they just sent — that would be the #23 mistake in a
  different screen.

## 6. Cypress cases (to write BEFORE implementing)

1. A booker reaches the Quotes screen and sees their own quotes.
2. A booker does **not** see a quote created by another user — asserted on the LIST and on a by-id read.
3. Owner and admin see all quotes in the org.
4. A plain user with neither role is refused — on the ENVELOPE (HTTP 200 + `success:false`), never on the
   HTTP status.
5. `approveQuote` still refuses a booker.
6. A booker can still create, send and submit for approval — the "must not break their work" guard.
7. Cross-tenant: another tenant's quote is never visible whatever the role.
8. The menu entry is absent for a user who cannot use it (presentation), AND the endpoint refuses them
   (protection) — both, because the first without the second is theatre.

## 7. Question for the user

**Is it (a), (b), or both?** And should a booker see quotes raised by *other bookers in the same tenant* — some
distributors run a shared pipeline where reps cover for each other, others treat a rep's quotes as private.


---

## 8. Ruling and what was built

**User ruled (b): a booker sees only the quotes they created; owner and admin see all.** Row-level visibility,
not screen-level reach — so anyone with `dealerPricing` may still USE quotes; they simply see their own.

### What changed

| Change | Where |
|---|---|
| `findOwnScoped(orgId, userId)` | `SalesQuoteRepo` |
| `findOwnByIdScoped(id, orgId, userId)` | `SalesQuoteRepo` |
| `list()` branches on `callerSeesWholeOrg()` | `SalesQuoteService` |
| `load(id)` branches the same way | `SalesQuoteService` |

### Why it is small

Nothing was invented. `requestUtil.callerSeesWholeOrg()` is the SAME helper `visibleSells()` and
`visiblePurchases()` already use, so a tenant answers "who sees everything" in one place instead of
re-deciding it per screen. `SalesQuote.userId` already exists and is already stamped at creation
(`SalesQuoteService:113`), so "mine" was answerable without a schema change.

### ⚠ Two things the by-id read forced

**`load(id)` had to change too.** Filtering the list alone would have been cosmetic: quote ids are sequential,
so an unfiltered by-id read is an open door behind a tidy front room. This is the same lesson the credit-note
endpoints taught in #15 — the scope predicate belongs in every read, not the one that renders a screen.

**A foreign quote reads as NOT FOUND, not FORBIDDEN.** Distinguishing them would tell a prober which ids are
real.

### What deliberately did NOT change

- **`approveQuote` stays owner/admin.** It was the one control already correct here, and case 5 asserts this
  slice did not loosen it.
- **A booker can still raise, open and send their own quotes** — case 4. Restricting visibility must not stop
  the person doing the work, which would be the #23 defect on another screen.
- **Screen reach is unchanged.** `data-capability="dealerPricing"` still decides who sees the menu. Hiding a
  menu is presentation; the endpoints are the protection, and they are what the gate asserts.
