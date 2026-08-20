/**
 * PERF-8 — the product picker stops downloading the product master.
 * Design: microservices/docs/slices/perf-8-product-picker.md
 *
 * WHAT THIS GUARDS. Before this slice every section open fetched the tenant's whole catalogue — all 23
 * fields of every product, deactivated ones included — in 3 requests, to fill one <select>. It now reads a
 * three-field, active-only projection ONCE and caches it.
 *
 * The cases below assert the PROPERTIES that make that safe, in order of what would hurt most if wrong:
 *
 *   1. A product added is immediately pickable  — a stale cache means an operator cannot sell what they
 *      just created, and concludes the system lost it. This is the case that matters most.
 *   2. Deactivated products are absent          — offering something unsellable at a till is worse than
 *      omitting it.
 *   3. It is org-scoped                          — with a positive control, per the standing rule.
 *   4. Re-opening a section issues NO request    — the cache is real, not decorative.
 *
 * Run headed:
 *   npx cypress run --spec cypress/e2e/business/product-picker.cy.js --headed --no-exit
 */
const uniq = () => `${Date.now()}${Math.floor(Math.random() * 1000)}`

/** ApiResponse -> PageResponse -> content, as the monolith proxy passes it through. */
const rows = (body) => {
  const page = (body && body.data) ? body.data : body
  return (page && Array.isArray(page.content)) ? page.content : []
}

const picker = () =>
  cy.request('/catalogProductPicker?page=0&size=2000').then((r) => rows(r.body))

describe('PERF-8 — product picker', () => {
  beforeEach(() => {
    cy.loginAsBusiness()
  })

  // ── the projection ────────────────────────────────────────────────────────────────────────────────────

  it('returns only the three fields a picker needs — not the whole product', () => {
    picker().then((list) => {
      expect(list.length, 'the tenant has products to pick').to.be.greaterThan(0)

      const p = list[0]
      expect(Object.keys(p).sort()).to.deep.eq(['id', 'name', 'sellingPrice'])

      // The wide columns that made this 538 bytes per product must not be here. `description` alone is
      // varchar(2000), and four timestamps and the stamped rate fields rode along with it.
      expect(p).to.not.have.property('description')
      expect(p).to.not.have.property('createdAt')
      expect(p).to.not.have.property('lastPurchaseRate')
    })
  })

  it('offers ACTIVE products only — filtered in SQL, not hidden in the browser', () => {
    const name = `PickerActive ${uniq()}`

    cy.seedProduct({ name }).then(({ productId }) => {
      // POSITIVE CONTROL: it is pickable while active, so its absence below means deactivation, not
      // a seeding failure.
      picker().then((list) => {
        expect(list.find((p) => p.id === productId), 'a new product is pickable').to.exist
      })

      // `checked` is a comma-separated STRING, not an array — the contract product-crud.cy.js uses.
      // The first draft passed an array, the call quietly did nothing, and the assertion below then
      // read as a picker defect. A fixture that does not fail loudly turns its own bug into someone
      // else's: assert the write succeeded before asserting anything about its effect.
      cy.request({
        method: 'POST', url: '/deactivateProduct', headers: { 'Content-Type': 'application/json' },
        body: { checked: String(productId) }, failOnStatusCode: false,
      }).then((r) => {
        expect(r.body.success, `deactivate failed: ${JSON.stringify(r.body)}`).to.eq(true)
        picker().then((list) => {
          expect(list.find((p) => p.id === productId),
            'a deactivated product must never be offered at a till').to.be.undefined
        })
      })
    })
  })

  // ── tenancy ───────────────────────────────────────────────────────────────────────────────────────────

  it('is org-scoped', () => {
    const name = `PickerScoped ${uniq()}`

    cy.seedProduct({ name }).then(({ productId }) => {
      // POSITIVE CONTROL FIRST — an absence assertion is not evidence until the mechanism is shown live.
      picker().then((mine) => {
        expect(mine.find((p) => p.id === productId), 'the owner can pick it').to.exist
      })

      cy.asOtherTenant((headers) =>
        cy.request({
          method: 'GET',
          url: 'http://localhost:8765/api/catalog/products/picker?page=0&size=2000',
          headers,
          failOnStatusCode: false,
        }).then((r) => {
          expect(rows(r.body).find((p) => p.id === productId),
            'another tenant cannot see it').to.be.undefined
        }))
    })
  })

  // ── ⭐ the cache must never be stale ───────────────────────────────────────────────────────────────────

  it('a product added is IMMEDIATELY pickable — the cache is invalidated on write', () => {
    const name = `PickerFresh ${uniq()}`

    cy.visit('/businessDashboard')
    cy.waitForAppReady()

    // Warm the cache, so what follows tests invalidation rather than a cold start.
    cy.window().then((w) => new Cypress.Promise((resolve) => w.ProductPicker.load(resolve)))

    cy.then(() => cy.seedProduct({ name })).then(({ productId }) => {
      // seedProduct writes through the API, not the screen, so nudge the same hook a form save fires.
      // (The hook itself is proved by the UI case below; this keeps the two concerns separate.)
      cy.window().then((w) => w.ProductPicker.invalidate())

      cy.window().then((w) => new Cypress.Promise((resolve) => w.ProductPicker.load(resolve)))
        .then((list) => {
          expect(list.find((p) => p.id === productId),
            'a product created a moment ago must be sellable now').to.exist
        })
    })
  })

  it('saving a product through the SCREEN drops the cache by itself', () => {
    const name = `PickerHook ${uniq()}`

    cy.visit('/businessDashboard')
    cy.waitForAppReady()
    cy.window().then((w) => new Cypress.Promise((resolve) => w.ProductPicker.load(resolve)))

    // The property: after a successful catalogue write, the cache is cold again — so the next picker
    // read goes to the server. Asserted on the module's own state rather than on a request count,
    // because THAT is what a stale picker would actually be.
    cy.window().then((w) => {
      return new Cypress.Promise((resolve) => {
        w.jQuery.ajax({
          type: 'POST', url: w.serverContext + 'addProduct', contentType: 'application/json',
          data: JSON.stringify({ name, sku: 'PK-' + uniq(), sellingPrice: 10 }),
          complete: resolve,
        })
      })
    })

    cy.window().then((w) => new Cypress.Promise((resolve) => w.ProductPicker.load(resolve)))
      .then((list) => {
        expect(list.find((p) => p.name === name),
          'the ajaxComplete hook invalidated the cache after addProduct').to.exist
      })
  })

  // ── the cache is real ─────────────────────────────────────────────────────────────────────────────────

  it('switching sections re-uses the cache — one request, not one per open', () => {
    cy.intercept('GET', '**/catalogProductPicker*').as('pick')

    // NOTE: the section is switched IN PAGE, not via cy.openSellSection — that helper calls cy.visit,
    // and a page reload legitimately clears a per-page-load cache. The first draft used it and then
    // asserted the cache had survived, which contradicted the design under test rather than checking
    // it. What the cache actually buys is an operator moving between screens without reloading, which
    // is what this now exercises.
    cy.visit('/businessDashboard')
    cy.waitForAppReady()

    cy.get('#sellType').select('sellDiv', { force: true })
    cy.get('#sellItemDD option', { timeout: 10000 }).should('have.length.greaterThan', 1)

    cy.get('@pick.all').then((first) => {
      // ONE request for the whole catalogue — this is what removes the two-wave head+tail pattern
      // that left a gap where the app looked idle but was not.
      expect(first.length, 'one request, not a head plus a parallel tail').to.eq(1)

      // Away and back, no reload.
      cy.get('#registrationType').select('CustomerDiv', { force: true })
      cy.waitForAppReady()
      cy.get('#sellType').select('sellDiv', { force: true })
      cy.get('#sellItemDD option', { timeout: 10000 }).should('have.length.greaterThan', 1)

      cy.get('@pick.all').then((second) => {
        expect(second.length, 'the second open is served from cache — no new request').to.eq(1)
      })
    })
  })

  it('the old fat picker read is no longer used to fill a <select>', () => {
    // /catalogProducts still exists and still serves the product LIST and the quarantine name map —
    // this asserts the SELL screen no longer reaches for it, which is where the 670 KB went.
    cy.intercept('GET', '**/catalogProducts*').as('fat')
    cy.intercept('GET', '**/catalogProductPicker*').as('lean')

    cy.visit('/businessDashboard')
    cy.waitForAppReady()
    cy.openSellSection('sellDiv')
    cy.get('#sellItemDD option', { timeout: 10000 }).should('have.length.greaterThan', 1)

    cy.get('@lean.all').then((lean) => {
      expect(lean.length, 'the picker read happened').to.be.greaterThan(0)
      cy.get('@fat.all').then((fat) => {
        expect(fat.length, 'the sell screen no longer downloads the full catalogue').to.eq(0)
      })
    })
  })
})
