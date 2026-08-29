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
