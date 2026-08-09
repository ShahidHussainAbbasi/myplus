/**
 * Product form — "already registered" panel + org-wide duplicate-NAME check.
 *
 * Two gaps this covers:
 *   1. The registered-product list (#tableProduct) has always been ON the Product screen, but the form opens in
 *      a fixed full-viewport .crud-overlay that covers it — so a new product was entered with no sight of what
 *      already existed. The form now carries its own scrollable panel, filtered live by Name / SKU / Barcode.
 *   2. Uniqueness was enforced on SKU only, and SKU is OPTIONAL — so the duplicate that actually happens (same
 *      name, no code) was caught by nothing at any layer. /productNameCheck → catalog /products/name-check now
 *      answers on focus-out of Name. It ADVISES: a duplicate name stays legal (same product, different pack or
 *      maker), only a duplicate SKU is refused.
 *
 * The check is ORG-wide, not per-user (ProductRepository.SCOPE leads with organizationId = :orgId) — the
 * operator about to create a twin is usually not the one who created the original. Proving the cross-USER half
 * needs a second account in the same org, which the fixtures do not provide; the org-level assertion here is
 * that the check reads the same tenant-scoped set every other product read does.
 *
 * Run headed.
 */
describe('Product form — already-registered panel + duplicate-name check', () => {
  beforeEach(() => { cy.loginAsBusiness() })

  // ── The server-side check ────────────────────────────────────────────────────

  it('productNameCheck reports an existing product by name, and names the match', () => {
    cy.seedProduct({ name: 'DupName_' + Date.now(), sellingPrice: 7 }).then(({ productId, name, sku }) => {
      cy.request('/productNameCheck?name=' + encodeURIComponent(name)).then((r) => {
        expect(r.body.success, JSON.stringify(r.body)).to.eq(true)
        expect(r.body.exists, 'the seeded name is reported as taken').to.eq(true)
        expect(r.body.id).to.eq(productId)
        expect(r.body.name).to.eq(name)
        expect(r.body.sku).to.eq(sku)
        expect(r.body.active).to.eq(true)
      })
    })
  })

  it('productNameCheck is case- and whitespace-insensitive', () => {
    cy.seedProduct({ name: 'CaseProd_' + Date.now() }).then(({ productId, name }) => {
      cy.request('/productNameCheck?name=' + encodeURIComponent('  ' + name.toUpperCase() + '  ')).then((r) => {
        expect(r.body.exists, 'differing case/spacing is still the same product name').to.eq(true)
        expect(r.body.id).to.eq(productId)
      })
    })
  })

  it('productNameCheck returns exists:false for a name nobody has used', () => {
    cy.request('/productNameCheck?name=' + encodeURIComponent('NeverUsed_' + Date.now())).then((r) => {
      expect(r.body.success).to.eq(true)
      expect(r.body.exists).to.eq(false)
    })
  })

  it('excludeId stops a product being flagged against itself when edited', () => {
    cy.seedProduct({ name: 'SelfEdit_' + Date.now() }).then(({ productId, name }) => {
      // Without excludeId the product IS its own namesake...
      cy.request('/productNameCheck?name=' + encodeURIComponent(name))
        .then((r) => expect(r.body.exists).to.eq(true))
      // ...but re-saving it under the same name must not warn.
      cy.request('/productNameCheck?name=' + encodeURIComponent(name) + '&excludeId=' + productId)
        .then((r) => expect(r.body.exists, 'editing a product does not flag its own name').to.eq(false))
    })
  })

  it('a DEACTIVATED namesake is still reported (it owns the name downstream), flagged inactive', () => {
    cy.seedProduct({ name: 'DeadName_' + Date.now() }).then(({ productId, name }) => {
      cy.request({
        method: 'POST', url: '/deactivateProduct', headers: { 'Content-Type': 'application/json' },
        body: { checked: String(productId) }, failOnStatusCode: false,
      }).then((r) => expect(r.body.success).to.eq(true))

      cy.request('/productNameCheck?name=' + encodeURIComponent(name)).then((r) => {
        expect(r.body.exists, 'a deactivated namesake is not "free"').to.eq(true)
        expect(r.body.id).to.eq(productId)
        expect(r.body.active, 'reported as inactive so the form can say so').to.eq(false)
      })
    })
  })

  it('a duplicate name is a WARNING, not a rejection — the save still succeeds', () => {
    const shared = 'Twin_' + Date.now()
    cy.seedProduct({ name: shared, sellingPrice: 4 }).then(() => {
      cy.request('/productNameCheck?name=' + encodeURIComponent(shared))
        .then((r) => expect(r.body.exists).to.eq(true))
      // Same name, different SKU → allowed (same product, different pack/maker is legitimate).
      cy.seedProduct({ name: shared, sellingPrice: 9 }).then(({ productId }) => {
        expect(productId, 'a second product may carry the same name').to.be.a('number')
      })
    })
  })

  // ── The panel ───────────────────────────────────────────────────────────────

  it('getUserProduct carries barcode — without it the panel could never match a scanned code', () => {
    const bc = '999' + Date.now()
    cy.seedProduct({ name: 'BarcodeRow_' + Date.now(), barcode: bc }).then(({ productId }) => {
      cy.request('/getUserProduct?q=-1').then((r) => {
        const mine = (r.body.collection || []).find((p) => p.id === productId)
        expect(mine, 'seeded product in collection').to.exist
        expect(mine.barcode, 'barcode is carried to the client').to.eq(bc)
      })
    })
  })

  it('opening New Product lists what is already registered', () => {
    cy.seedProduct({ name: 'PanelSeed_' + Date.now(), sellingPrice: 21 }).then(({ productId, name }) => {
      cy.visit('/businessDashboard')
      cy.window().then((w) => w.showProducts())
      cy.get('#newProduct').click()
      cy.get('#ProductModal').should('have.class', 'open')

      cy.get('#prodExistingWrap').should('be.visible')
      cy.get('#prodExistingList .crud-existing-row', { timeout: 10000 }).should('have.length.greaterThan', 0)
      cy.get('#prodExistingCount').should('not.have.text', '')
      // The seeded product is reachable in the panel (it may be past the row cap, so filter to it first).
      cy.get('#prodName').type(name)
      cy.get(`#prodExistingList .crud-existing-row[data-id="${productId}"]`).should('exist').and('contain', name)
    })
  })

  it('typing narrows the panel; a term nothing matches says so instead of showing an empty list', () => {
    cy.seedProduct({ name: 'Narrow_' + Date.now() }).then(({ productId, name }) => {
      cy.visit('/businessDashboard')
      cy.window().then((w) => w.showProducts())
      cy.get('#newProduct').click()
      cy.get('#prodExistingList .crud-existing-row', { timeout: 10000 }).should('have.length.greaterThan', 0)

      // A matching fragment narrows to the seeded product.
      cy.get('#prodName').clear().type(name)
      cy.get('#prodExistingList .crud-existing-row').should('have.length', 1)
        .and('have.attr', 'data-id', String(productId))

      // A fragment nothing carries → no rows, but an explicit message (never a silent blank).
      cy.get('#prodName').clear().type('zzz_no_such_product_' + Date.now())
      cy.get('#prodExistingList .crud-existing-row').should('have.length', 0)
      cy.get('#prodExistingMsg').should('be.visible').and('not.have.text', '')
    })
  })

  it('leaving the Name field on a duplicate flags the field and highlights the existing row', () => {
    cy.seedProduct({ name: 'BlurDup_' + Date.now(), sellingPrice: 15 }).then(({ productId, name }) => {
      cy.visit('/businessDashboard')
      cy.window().then((w) => w.showProducts())
      cy.intercept('GET', '**/productNameCheck*').as('nameCheck')
      cy.get('#newProduct').click()
      cy.get('#prodExistingList .crud-existing-row', { timeout: 10000 }).should('have.length.greaterThan', 0)

      cy.get('#prodName').type(name).blur()
      cy.wait('@nameCheck').its('response.body.exists').should('eq', true)

      cy.get('#prodName').should('have.class', 'alert-danger')
      cy.get(`#prodExistingList .crud-existing-row[data-id="${productId}"]`).should('have.class', 'is-flagged')

      // Retyping clears the flag — the warning belongs to the name that was checked, not to the field forever.
      cy.get('#prodName').type('_v2')
      cy.get('#prodName').should('not.have.class', 'alert-danger')
    })
  })

  it('a novel name blurs clean — no flag, no highlighted row', () => {
    cy.visit('/businessDashboard')
    cy.window().then((w) => w.showProducts())
    cy.intercept('GET', '**/productNameCheck*').as('nameCheck')
    cy.get('#newProduct').click()
    cy.get('#prodExistingWrap').should('be.visible')

    cy.get('#prodName').type('Fresh_' + Date.now()).blur()
    cy.wait('@nameCheck').its('response.body.exists').should('eq', false)
    cy.get('#prodName').should('not.have.class', 'alert-danger')
    cy.get('#prodExistingList .crud-existing-row.is-flagged').should('not.exist')
  })

  it('"edit this one" loads the existing product into the form instead of creating a twin', () => {
    cy.seedProduct({ name: 'EditInstead_' + Date.now(), sellingPrice: 42 }).then(({ productId, name }) => {
      cy.visit('/businessDashboard')
      cy.window().then((w) => w.showProducts())
      cy.get('#newProduct').click()
      cy.get('#prodExistingList .crud-existing-row', { timeout: 10000 }).should('have.length.greaterThan', 0)

      // Operator starts typing the name that already exists, then takes the offered row.
      cy.get('#prodName').type(name)
      cy.get(`#prodExistingList .crud-existing-row[data-id="${productId}"] .js-edit-existing`).click()

      // The form is now EDITING that product — the hidden id is what makes Submit an update, not an insert.
      cy.get('#productId', { timeout: 10000 }).should('have.value', String(productId))
      cy.get('#prodName').should('have.value', name)
      cy.get('#prodPrice').should('have.value', '42')
      // ...and the product being edited is no longer offered against itself.
      cy.get(`#prodExistingList .crud-existing-row[data-id="${productId}"]`).should('not.exist')
    })
  })

  it('the panel never claims "nothing registered" when the list could not be loaded', () => {
    cy.visit('/businessDashboard')
    cy.window().then((w) => w.showProducts())
    // Break the fetch the panel depends on, then open the form.
    cy.intercept('GET', '**/getUserProduct*', { statusCode: 500, body: {} }).as('brokenList')
    cy.get('#newProduct').click()
    cy.wait('@brokenList')

    cy.get('#prodExistingList .crud-existing-row').should('have.length', 0)
    // An error, not silence and not "no products yet" — the operator must not be told the name is free
    // on the strength of a call that failed.
    cy.get('#prodExistingMsg').should('be.visible').and('have.class', 'text-danger')
  })
})
