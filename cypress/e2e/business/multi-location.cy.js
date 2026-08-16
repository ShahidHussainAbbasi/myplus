/**
 * Multi-location (Stores) — role×location visibility. The slice gate for P1–P3 of
 * microservices/docs/multi-location-stores-branches-design.md (§5 test table T1–T8).
 *
 * Fixture (built once, as the owner): two stores + three seeded members granted to them —
 *   Store A -> cashier.a        Store B -> admin.store, cashier.b
 * The owner self-grants to BOTH stores (addStore does that), so the owner has no single active
 * store and their sales are stamped store_id = NULL — which is exactly the legacy row shape T8 needs.
 *
 * Grants only reach business-service through a freshly issued JWT, so every grant happens BEFORE the
 * member's first login. Sales are identified by productId (one product per store), which survives
 * whatever the sell DTO/entity serialisation does.
 */
const uniq = () => `${Date.now()}${Math.floor(Math.random() * 1000)}`

// GenericResponse (business) and ApiResponse (auth) disagree on the payload key, and GenericResponse
// carries BOTH `object` and `collection` with the unused one serialised as null — a list lands in
// `collection`. So take the first key that actually holds an array, never the first key that exists.
const rows = (body) => {
  for (const key of ['collection', 'object', 'data']) {
    if (Array.isArray(body && body[key])) return body[key]
  }
  return []
}
const byProduct = (list, productId) => list.filter((r) => Number(r.productId) === Number(productId))

// One line, one product, unique customer — the smallest sale the saga accepts.
const sell = (productId, tag) =>
  cy.request({
    method: 'POST', url: '/addSell', headers: { 'Content-Type': 'application/json' },
    body: {
      customer: { name: `CY_ML_${tag}_${uniq()}`, contact: '0300ML', paidAmount: 100, dueAmount: 0 },
      sales: [{ productId, quantity: 1, sellRate: 100, totalAmount: 100, netAmount: 100 }],
      paidAmount: 100, dueAmount: 0, grandTotal: 100,
    },
    failOnStatusCode: false,
  }).then((r) => {
    expect(r.body.status, `addSell ${tag}: ${JSON.stringify(r.body)}`).to.eq('SUCCESS')
    return r.body.object   // invoiceNo
  })

describe('Multi-location: stores, grants and role×location visibility', () => {
  const F = {}   // storeA, storeB, prodA, prodB, ids + sellIds, shared across tests

  before(() => {
    cy.loginAsOwner()

    // Stores are created once and reused across runs — keyed by name, so a re-run doesn't pile them up.
    cy.request('/getStores').then((r) => {
      const existing = rows(r.body)
      const ensure = (name) => {
        const hit = existing.find((s) => s.name === name)
        if (hit) return cy.wrap(hit.id)
        return cy.request({
          method: 'POST', url: '/addStore', headers: { 'Content-Type': 'application/json' },
          body: { name, code: name.replace(/\s/g, '').toUpperCase() },
        }).then((a) => {
          expect(a.body.status, `addStore ${name}: ${JSON.stringify(a.body)}`).to.eq('SUCCESS')
          return a.body.object.id
        })
      }
      ensure('CY Store A').then((id) => { F.storeA = id })
      ensure('CY Store B').then((id) => { F.storeB = id })
    })

    // Seeded members -> ids, then grant each their store. Idempotent, so re-runs are safe.
    cy.request('/team/users').then((r) => {
      const team = rows(r.body)
      const idOf = (email) => {
        const m = team.find((u) => u.email === email)
        expect(m, `seeded member ${email} missing — rebuild + restart auth-service (SetupDataLoader)`).to.exist
        return m.userId
      }
      F.ownerId = idOf('owner.business@myplus.com')
      F.adminId = idOf('admin.store@myplus.com')
      F.cashierAId = idOf('cashier.a@myplus.com')
      F.cashierBId = idOf('cashier.b@myplus.com')

      // Assert the grant landed: the monolith proxy swallows a failed grant into {status:'ERROR'}, which
      // used to surface much later as an unrelated "product not found" when the member finally sold.
      //
      // `replace: true` makes this the member's COMPLETE set of stores rather than an addition, and that
      // is load-bearing for T1 — not tidiness.
      //
      // T1 relies on the single-store convenience in AuthService.addLocationClaims: an active store is
      // resolved automatically only when the member holds EXACTLY ONE grant ("with several stores and no
      // choice made, writes stay unstamped until the user picks one"). The additive form left every grant
      // an earlier run had made in place, so cashier A accumulated a SECOND grant pointing at a store id
      // that no longer exists (stores are recreated per environment; `user_location_access.location_id`
      // is polymorphic across modules and therefore cannot carry a foreign key to clean itself up).
      // Two grants ⇒ no auto-active store ⇒ the sale was written with store_id NULL, and T1 failed with
      // "expected 0 to equal 3" — `Number(null)`. T6b then failed as a CONSEQUENCE, because an unstamped
      // row is legacy-shaped and T8's deliberate NULL-fallback makes it readable by any store admin.
      //
      // So the precondition this case depends on was never established, only assumed.
      const grant = (userId, storeId, roleAtLocation) => cy.request({
        method: 'POST', url: '/assignStores', headers: { 'Content-Type': 'application/json' },
        body: { userId, storeIds: [storeId], roleAtLocation, replace: true },
        failOnStatusCode: false,
      }).then((r) => {
        expect(r.body && r.body.success, `grant store ${storeId} to user ${userId}: ${JSON.stringify(r.body)}`).to.eq(true)
      })
      grant(F.adminId, F.storeB, 'ADMIN')
      grant(F.cashierAId, F.storeA, 'USER')
      grant(F.cashierBId, F.storeB, 'USER')

      // The grants above only reach a member once they MINT A NEW TOKEN.
      //
      // `accessibleLocationIds` / `activeLocationId` are JWT CLAIMS, written by AuthService.addLocationClaims
      // at login. `cy.session(..., { cacheAcrossSpecs: true })` keeps one login per account for the whole
      // run, so a member who signed in during an EARLIER spec (contact-360 uses cashier.a, and it sorts
      // before this file) would be restored here holding a token minted under the OLD grants — and the
      // re-grant would change the database while changing nothing this spec can observe.
      //
      // Dropping the cache makes the logins below mint fresh tokens. It costs one login per account and is
      // the only way the fixture and the token can agree; the alternative, `cacheKeyExtra`, would have to be
      // threaded through every `cy.loginAsCashierA()` call site in this file to work at all.
      //
      // The ACTIVE browser session is untouched, so the owner's requests either side of this keep working.
      cy.then(() => Cypress.session.clearAllSavedSessions())
    })

    // One product per store so every sale below is identifiable in a list response.
    cy.seedProduct({ name: `CY_ML_A_${uniq()}`, stock: 50 }).then(({ productId }) => { F.prodA = productId })
    cy.seedProduct({ name: `CY_ML_B_${uniq()}`, stock: 50 }).then(({ productId }) => { F.prodB = productId })

    // The owner holds BOTH stores => no single active store => store_id NULL (the T8 legacy shape).
    cy.then(() => sell(F.prodA, 'OWNER')).then((inv) => { F.invOwner = inv })

    // Store A: a sale by its cashier.  Store B: one by the cashier, one by the admin.
    cy.then(() => { cy.loginAsCashierA() })
    cy.then(() => sell(F.prodA, 'CASHIER_A')).then((inv) => { F.invCashierA = inv })

    cy.then(() => { cy.loginAsCashierB() })
    cy.then(() => sell(F.prodB, 'CASHIER_B')).then((inv) => { F.invCashierB = inv })

    cy.then(() => { cy.loginAsStoreAdmin() })
    cy.then(() => sell(F.prodB, 'ADMIN_B')).then((inv) => { F.invAdminB = inv })
  })

  // T1 — a store-granted cashier's write is stamped with their active store (P2b), and they can read it back.
  it('T1: a sale is stamped with the writer\'s active store', () => {
    cy.loginAsCashierA()
    cy.request('/getAllSell').then((r) => {
      const mine = byProduct(rows(r.body), F.prodA).filter((s) => Number(s.userId) === Number(F.cashierAId))
      expect(mine.length, 'cashier A sees their own store-A sale').to.be.greaterThan(0)
      mine.forEach((s) => expect(Number(s.storeId), 'store_id stamped from the active store').to.eq(Number(F.storeA)))
    })
  })

  // T2 — the owner is never narrowed by grants: whole org, every store.
  it('T2: owner sees sales from both stores', () => {
    cy.loginAsOwner()
    cy.request('/getAllSell').then((r) => {
      const all = rows(r.body)
      expect(byProduct(all, F.prodA).length, 'store A sales visible to owner').to.be.greaterThan(0)
      expect(byProduct(all, F.prodB).length, 'store B sales visible to owner').to.be.greaterThan(0)
    })
  })

  // T3 — an admin IS store-constrained: their store's sales (all cashiers), never the other store's.
  it('T3: admin at Store B sees Store B only, not Store A', () => {
    cy.loginAsStoreAdmin()
    cy.request('/getAllSell').then((r) => {
      const all = rows(r.body)
      const storeB = byProduct(all, F.prodB)
      expect(storeB.length, 'admin sees store-B sales').to.be.greaterThan(0)
      // ...including a cashier's, not just their own — an admin sees the whole store.
      expect(storeB.some((s) => Number(s.userId) === Number(F.cashierBId)), 'admin sees cashier B\'s sale').to.eq(true)
      // ...and nothing stamped Store A.
      const leaked = all.filter((s) => s.storeId != null && Number(s.storeId) === Number(F.storeA))
      expect(leaked.length, `store-A rows leaked to the store-B admin: ${JSON.stringify(leaked.map((s) => s.sellId))}`).to.eq(0)
    })
  })

  // T4 — a cashier sees only their OWN sales, even inside their own store (shift/till model).
  it('T4: cashier at Store B sees only their own sales', () => {
    cy.loginAsCashierB()
    cy.request('/getAllSell').then((r) => {
      const all = rows(r.body)
      expect(byProduct(all, F.prodB).length, 'cashier B sees their store-B sale').to.be.greaterThan(0)
      const notMine = all.filter((s) => s.userId != null && Number(s.userId) !== Number(F.cashierBId))
      expect(notMine.length, `other users' sales leaked to a cashier: ${JSON.stringify(notMine.map((s) => s.sellId))}`).to.eq(0)
    })
  })

  // T5 — the hierarchy is server-enforced: an admin creates USERs in their own store, never an ADMIN.
  it('T5: admin creates a USER (inheriting their store) but cannot create an ADMIN', () => {
    cy.loginAsStoreAdmin()
    const email = `cy.member.${uniq()}@myplus.com`
    cy.request({
      method: 'POST', url: '/team/users', headers: { 'Content-Type': 'application/json' },
      body: { firstName: 'CY', lastName: 'Member', email, role: 'USER' },   // no storeIds => inherits the admin's
      failOnStatusCode: false,
    }).then((r) => {
      const created = (r.body && (r.body.data || r.body)) || {}
      expect(created.email, `USER creation failed: ${JSON.stringify(r.body)}`).to.eq(email)
      expect(String(created.role)).to.eq('USER')
    })

    cy.request({
      method: 'POST', url: '/team/users', headers: { 'Content-Type': 'application/json' },
      body: { firstName: 'CY', lastName: 'Escalate', email: `cy.admin.${uniq()}@myplus.com`, role: 'ADMIN' },
      failOnStatusCode: false,
    }).then((r) => {
      const body = JSON.stringify(r.body)
      expect(body, `an admin was allowed to create an ADMIN: ${body}`).to.match(/[Oo]nly an owner can create an admin/)
    })
  })

  // T6 — a cashier cannot open another store's invoice by id.
  it('T6: cashier cannot open a sale from another store (IDOR)', () => {
    cy.loginAsOwner()
    cy.request('/getAllSell').then((r) => {
      const storeASale = byProduct(rows(r.body), F.prodA)
        .find((s) => Number(s.userId) === Number(F.cashierAId))
      expect(storeASale, 'store-A sale to probe with').to.exist

      cy.loginAsCashierB()
      cy.request({ url: `/getSellInvoice?sellId=${storeASale.sellId}`, failOnStatusCode: false }).then((res) => {
        expect(res.body.status, `cashier B opened a store-A sale: ${JSON.stringify(res.body)}`).to.eq('NOT_FOUND')
      })
    })
  })

  // T7 — the recent-sales list a cashier's dashboard actually calls leaks nothing either.
  it('T7: /getUserSell as a cashier returns own rows only', () => {
    cy.loginAsCashierB()
    cy.request('/getUserSell?q=-1').then((r) => {
      const all = rows(r.body)
      // Positive first — an empty list would otherwise satisfy the leak check for the wrong reason.
      expect(byProduct(all, F.prodB).length, 'cashier B sees their own sale here').to.be.greaterThan(0)
      const notMine = all.filter((s) => s.userId != null && Number(s.userId) !== Number(F.cashierBId))
      expect(notMine.length, `getUserSell leaked other users' rows: ${JSON.stringify(notMine.map((s) => s.sellId))}`).to.eq(0)
    })
  })

  // T8 — rows with no store (legacy, or written before grants existed) stay visible via the NULL fallback.
  it('T8: store_id NULL rows remain visible to a store-scoped admin', () => {
    cy.loginAsStoreAdmin()
    cy.request('/getAllSell').then((r) => {
      const nullStore = rows(r.body).filter((s) => s.storeId == null)
      expect(nullStore.length, 'legacy/unstamped rows still visible under the store filter').to.be.greaterThan(0)
    })
  })

  // T10 (P5b) — an owner holding two stores has NO active store, so their writes are unstamped. Switching
  // gives them one, and the next sale is stamped with it. This is the whole point of the switcher.
  it('T10: switching the active store stamps the next sale with it', () => {
    cy.loginAsOwner()
    cy.request('/getMyStores').then((r) => {
      const mine = rows(r.body)
      expect(mine.length, 'owner can work at both stores').to.be.greaterThan(1)

      cy.request({
        method: 'POST', url: '/switchStore', headers: { 'Content-Type': 'application/json' },
        body: { storeId: F.storeB }, failOnStatusCode: false,
      }).then((s) => expect(s.body.status, `switchStore: ${JSON.stringify(s.body)}`).to.eq('SUCCESS'))

      // The switch re-issued the session token; the sale that follows must land in Store B.
      cy.then(() => sell(F.prodB, 'OWNER_AT_B'))
      cy.request('/getMyStores').then((after) => {
        const active = rows(after.body).find((s) => s.active)
        expect(active && Number(active.id), 'Store B is now active').to.eq(Number(F.storeB))
      })
      cy.request('/getAllSell').then((sr) => {
        const ownerAtB = byProduct(rows(sr.body), F.prodB)
          .filter((s) => Number(s.userId) === Number(F.ownerId) && s.storeId != null)
        expect(ownerAtB.length, 'the owner\'s post-switch sale is stamped').to.be.greaterThan(0)
        ownerAtB.forEach((s) => expect(Number(s.storeId), 'stamped with the switched-to store').to.eq(Number(F.storeB)))
      })
    })
  })

  // P6 — pharma/marketplace reuse the commerce core (they redirect to the same dashboard and their grants map
  // to the BUSINESS module), so they inherit all of this. The thing that actually needs proving is the
  // DEGENERATE case: a vertical with NO stores and NO grants must behave exactly as it did before any of this
  // existed — empty accessible set ⇒ no store filter ⇒ nothing hidden, nothing broken.
  it('P6: a vertical with no store grants is completely unaffected', () => {
    cy.loginAsPharma()
    cy.request({ url: '/getMyStores', failOnStatusCode: false }).then((r) => {
      expect(r.status, 'the store endpoint answers rather than erroring').to.eq(200)
      expect(rows(r.body).length, 'no stores in their org, so nothing to switch between').to.eq(0)
    })
    // Their sales list still works and is not silently emptied by the store filter.
    cy.request({ url: '/getUserSell?q=-1', failOnStatusCode: false }).then((r) => {
      expect(r.status, 'unstored vertical can still read its sales').to.eq(200)
      expect(['SUCCESS', 'NOT_FOUND'], `unexpected status: ${JSON.stringify(r.body)}`)
        .to.include(r.body.status)   // NOT_FOUND only if this demo org genuinely has no sales yet
    })
  })

  // T6b (P2c) — the store rule also holds for a WHOLE-ORG viewer taking an id straight from the client.
  // An admin is not the creator's peer here: the list hides Store A from them, so read-by-id must too.
  it('T6b: admin at Store B cannot open a Store-A sale by id', () => {
    cy.loginAsOwner()
    cy.request('/getAllSell').then((r) => {
      const storeASale = byProduct(rows(r.body), F.prodA).find((s) => Number(s.userId) === Number(F.cashierAId))
      expect(storeASale, 'store-A sale to probe with').to.exist

      cy.loginAsStoreAdmin()
      cy.request({ url: `/getSellInvoice?sellId=${storeASale.sellId}`, failOnStatusCode: false }).then((res) => {
        expect(res.body.status, `store-B admin opened a store-A sale: ${JSON.stringify(res.body)}`).to.eq('NOT_FOUND')
      })
    })
  })
})
