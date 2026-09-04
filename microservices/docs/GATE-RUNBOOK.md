# Gate runbook — how a slice is tested before it is called done

The rule in one line: **gate a feature as the tenant that needs it, across the privilege level that uses it.**

A convenient account proves the mechanism and nothing about the business. This runbook exists because gating
serial/IMEI as `owner.business@` (the POS tenant) passed cleanly while concealing that the `retail` shape
preset does not grant `serialTracking` — so a real mobile shop had its headline feature switched off, and no
test could tell.

---

## 1. Log in as the tenant the feature belongs to

| Tenant | Account | Shape it runs on |
|---|---|---|
| Mobile shop | `owner.mobile@myplus.com` | `retail` + serial, condition, installments |
| Agri-chem / pesticide | `owner.pesticide@myplus.com` | `pharmacy` preset, `rxRequired` switched OFF |
| POS / retail counter | `owner.business@myplus.com` | `retail` |
| Pharmacy | `owner.pharma@myplus.com` | `pharmacy` |
| Distribution / marketplace | `owner.marketplace@myplus.com` | `distribution` |
| School | `owner.education@myplus.com` | — |

All on `Demo@2025!`, each with **its own organization**, seeded behind `app.seed-test-fixtures` **and** an
independent `isProd()` hard block. Cypress commands: `cy.loginAsMobileOwner()`, `cy.loginAsPesticideOwner()`,
`cy.loginAsOwner()`, `cy.loginAsPharmaOwner()`, `cy.loginAsMarketplaceOwner()`, `cy.loginAsEduOwner()`.

**Never reach for whichever account the previous spec used.**

## 2. Set the tenant up the way its owner would

Shape first, then the capabilities the preset does not grant:

```js
cy.setShape('retail')                        // what KIND of counter this is
cy.setCapability('serialTracking', true)     // what THIS shop does
```

That ordering is itself a test — an explicit override must survive a shape that omits it. A preset is a
starting point, never a decision, or changing your profile would silently destroy settings you had chosen.

## 3. Add the cross-tenant case

A **different tenant on the same shape** must not see the feature. This is the assertion a single-account suite
cannot make, and the one that catches the failure that matters: a bug hiding a section for *everybody* passes a
one-tenant gate perfectly.

```js
cy.loginAsOwner(POS)                 // retail as well — same shape, same screens
cy.get('#sellSerials').should('have.class', 'cap-off')
```

## 4. Walk the privilege ladder

Every module seeds four accounts. `admin.` and `user.` are **members of the owner's org**, so a refusal proves
the PRIVILEGE gate rather than org scoping.

| Account | Role | Has |
|---|---|---|
| `owner.<m>@` | `ROLE_OWNER` | everything, uncapped |
| `admin.<m>@` | `ADMIN_ROLE` | + DELETE, ADMIN, VOID_INVOICE |
| `user.<m>@` | `ROLE_<M>_USER` | write/update; **no** delete, **no** admin |
| `demo.<m>@` | `DEMO_ROLE` | full privileges, capped at 50 writes |

At each level assert **both**:

1. **Data populates** — the lists actually load for that role. A screen that renders empty because the read was
   silently refused looks identical to a screen with no data.
2. **UI/UX privileges differ** — delete buttons, void, Configuration and owner-only sections present or absent
   as they should be.

## 5. Restore tenant state in `after()`

Capabilities and shapes are per-tenant **server** state that outlives the run. Restore them — especially on
`owner.business@`, which most other specs log in as. An `after()` hook is courtesy, not a guarantee: a token
expiring mid-run once left a setting on and took the next spec from 6/6 to 1/6 for a reason nothing in that
file could explain. **Establish what you need in `before`; do not inherit it.**

---

## Two failure modes this runbook exists to prevent

**An API-only gate cannot see the screen.** `cy.request` reaches an endpoint whether a control exists or not.
C6 shipped a per-product policy with a column, an endpoint, a server guard and a fully green API gate — and no
checkbox anywhere for a shopkeeper to use. Pair every API assertion with one that the control is present for
the right role and absent for the wrong one.

**Assert the property, not the artefact.** "The tile is hidden" only proves the DOM was tidied; assert the
payload has no key, so the tenant is not paying for what they cannot see. "Status is SOLD" passed while the
invoice number the register exists to record came back `undefined`. And a `scrollWidth` check only ever detects
overflow to the RIGHT — fifteen controls sat off the LEFT edge of a phone screen through 49 green runs.

## 6. Add the slice's manual cases to the Test Book

**A slice is not done when Cypress is green. It is done when the Test Book can walk it by hand.**

**The Test Book:** <https://claude.ai/code/artifact/84fdaeff-84bb-4427-9e37-5f1c3ba845a3>

One page, always the same page. Not a new document per slice and not an appendix — the whole point is a single
place to walk the product end to end, so a second page defeats it.

### What every slice adds

| Add | Why |
|---|---|
| What a person should **see**, in their words | "Print and PDF appear on every quote row", not "`printQuote` is exposed" |
| Every **⚠ case** that records something actually found broken | These are the ones worth repeating after any change near them |
| Anything the slice **could not close**, into *Not yet verified* | An unlisted known defect is one someone rediscovers as a surprise |

### And correct what the slice made wrong

A slice that changes behaviour must fix the existing wording it invalidates. **A page that quietly contradicts
the product is worse than no page, because it is trusted.**

### Why this rule exists

The automated gates have been green through: a credit note that printed no lines; the same note printing no
customer name for every tenant since #15; a scan box that appeared for shops that had switched it off; quote
settings no one could set, leaving the approval step unreachable; and nine features that shipped where nobody
could click them.

**Every one of those was found by a person looking at the screen.** An automated case asserts what someone
thought to assert; the manual walk is what finds what nobody thought of. That is not an argument for fewer
Cypress cases — it is why the two are both required, and why neither closes a slice alone.

## 7. A fixture must pick an ELIGIBLE row, not the first one

`collection[0]` is not a fixture. It is whatever the database happened to return first, and long-lived tenants
accumulate rows that satisfy no requirement.

**What this cost:** `quote-document.cy.js` took `customers[0]` and drew customer **4663 — a legacy row with a
blank name**. Eleven cases went red reporting that the quote had stamped no customer. The product was correct
throughout; the fixture had handed it a customer no document could print. Blank names are no longer creatable
(`/addCustomer` is `@Validated` with `@NotBlank`), so that row will sit at the top of that tenant's list
permanently, waiting for the next fixture that assumes row 0 is usable.

**The rule:** filter for what the feature actually needs, then seed if nothing qualifies.

```js
const usable = (list) => (list || []).filter((c) => c && c.name && String(c.name).trim())
```

### Seeding has its own two traps

1. **The seed must survive a SECOND run.** Unique columns and duplicate-name checks make a fixed name or phone
   number work exactly once; the next run fails with a duplicate message that has nothing to do with the
   feature. Suffix with a timestamp.
2. **Assert the seed took.** A create that silently failed leaves the next read empty and every assertion
   downstream testing nothing — while still passing.

### ⚠ "Eligible" is defined by the FEATURE, and it is easy to get wrong twice

Writing this rule down did not stop it happening again the same day — twice more, in the next slice:

| Fixture asked | Feature actually needed | Result |
|---|---|---|
| a customer exists | a customer **with a name** | 11 red cases; the blank-name legacy row was row 0 |
| a sale return exists | a return **with a credit-note number** | 3 red cases; legacy returns list but are not printable |

Both times the product was correct. A return that predates the note series deliberately has no Print button —
there is no document, and a button that always fails is worse than none. The fixture saw rows, concluded the
tenant was set up, and seeded nothing.

**So the check is never `rows.length > 0`.** It is: *filter to what this feature can actually use; seed if
that set is empty.*

```js
const printable = rows.filter((n) => n && n.documentNo)   // not rows.length
if (printable.length) return cy.wrap(printable.length)
return cy.seedCreditNote()
```

A long-lived tenant accumulates rows that satisfy no requirement. **Every one of them is row 0 to somebody.**

### And its own failure mode

A fixture that asserts existence turns an environment problem into a red feature. Three cases of this same
spec failed on an empty customer list while later cases in the same run raised quotes perfectly well — a
service that had only just restarted was answering early reads with an empty collection. **Seed, never
assert-or-skip.**
