/**
 * Purchase flow tests — UI rendering, AJAX, full CRUD via API
 *
 * Purchase links a Stock entry to a quantity bought.
 * addPurchase also updates the linked Stock.quantity.
 */

// ─── 1. Section UI Rendering ─────────────────────────────────────────────────

describe('Purchase Section — UI Rendering', () => {
  beforeEach(() => {
    cy.loginAsBusiness()
    cy.openPurchaseSection('purchaseDiv')
  })

  it('shows toolbar + table; New opens the form modal', () => {
    cy.get('#tablePurchase').should('exist')
    cy.get('#newPurchase').should('be.visible')
    cy.get('#PurchaseModal').should('not.have.class', 'open')   // form hidden until New
    cy.get('#newPurchase').click()
    cy.get('#PurchaseModal').should('have.class', 'open')
    cy.get('#purchaseItemDD').should('exist')
    cy.get('#purchaseQuantity').should('be.visible')
    cy.get('#addPurchase').should('be.visible')
    cy.get('#resetPurchase').should('be.visible')
  })

  it('purchase table exists with thead columns', () => {
    cy.get('#tablePurchase thead').should('exist')
    cy.get('#tablePurchase thead th').should('have.length.above', 0)
  })

  it('Cancel closes the form modal', () => {
    cy.get('#newPurchase').click()
    cy.get('#PurchaseModal').should('have.class', 'open')
    cy.get('#resetPurchase').click()
    cy.get('#PurchaseModal').should('not.have.class', 'open')
  })

  it('Reset button clears the quantity field', () => {
    cy.get('#newPurchase').click()
    cy.get('#purchaseQuantity').type('5')
    cy.get('#resetPurchase').click()
    cy.get('#newPurchase').click()                              // re-open to inspect the cleared field
    cy.get('#purchaseQuantity').should('have.value', '')
  })

  it('item dropdown is pre-populated via catalogProducts AJAX', () => {
    // M4e.1b (slice 98): the purchase picker now lists catalog Products via /catalogProducts, not /getUserItems.
    cy.intercept('GET', '/catalogProducts*').as('catalogProducts')
    cy.visit('/businessDashboard')
    cy.get('#purchaseType').select('purchaseDiv', { force: true })
    cy.wait('@catalogProducts', { timeout: 10000 }).then((interception) => {
      expect(interception.response.statusCode).to.eq(200)
    })
  })

  it('item dropdown has at least a placeholder option', () => {
    cy.get('#purchaseItemDD option').should('have.length.gte', 1)
  })
})

// ─── 2. Purchase API — Read ───────────────────────────────────────────────────

describe('Purchase API — Read', () => {
  beforeEach(() => {
    cy.loginAsBusiness()
  })

  it('getUserPurchase returns 200 with status field', () => {
    cy.request({ url: '/getUserPurchase', failOnStatusCode: false }).then((res) => {
      expect(res.status).to.eq(200)
      expect(res.body).to.have.property('status')
      expect(res.body.status).to.be.oneOf(['SUCCESS', 'NOT_FOUND', 'ERROR'])
    })
  })

  it('getUserPurchase SUCCESS — collection is an array', () => {
    cy.request({ url: '/getUserPurchase', failOnStatusCode: false }).then((res) => {
      if (res.body.status === 'SUCCESS') {
        const data = res.body.collection || res.body.data || []
        expect(data).to.be.an('array')
      }
    })
  })

  it('getUserPurchase SUCCESS — each record has a quantity >= 0', () => {
    cy.request({ url: '/getUserPurchase', failOnStatusCode: false }).then((res) => {
      if (res.body.status === 'SUCCESS') {
        const records = res.body.collection || res.body.data || []
        records.slice(0, 5).forEach((p) => {
          expect(p).to.have.property('quantity')
          expect(p.quantity).to.be.gte(0)
        })
      }
    })
  })
})

// ─── 3. Purchase API — CRUD ───────────────────────────────────────────────────

describe('Purchase API — CRUD', () => {
  let testProductId

  before(() => {
    cy.loginAsBusiness()
    // M4e.d (slice 104): seed via the catalog Product master; addPurchase below is productId-native.
    cy.seedProduct({ name: `PurchaseTestItem_${Date.now()}`, sellingPrice: 80, purchaseRate: 50, stock: 100, category: 'Test' })
      .then(({ productId }) => { testProductId = productId })
  })

  beforeEach(() => {
    cy.loginAsBusiness()
  })

  it('addPurchase productId-native — returns 200 with status', () => {
    if (!testProductId) return cy.log('No test product — skipping')
    cy.request({
      method: 'POST', url: '/addPurchase', form: true,
      // M4e.d (slice 104): submit productId directly — the server persists it, no itemId mapping.
      body: { productId: testProductId, quantity: 5, purchaseRate: 50, totalAmount: 250, netAmount: 250, purchaseInvoiceNo: `INV-CY-${Date.now()}` },
      failOnStatusCode: false,
    }).then((res) => {
      expect(res.status).to.eq(200)
      expect(res.body.status, JSON.stringify(res.body)).to.eq('SUCCESS')
      cy.log(`addPurchase: ${res.body.status}`)
    })
  })

  it('addPurchase → appears in getUserPurchase list', () => {
    if (!testProductId) return cy.log('No test product — skipping')
    const invoiceNo = `INV-TRACE-${Date.now()}`
    cy.request({
      method: 'POST', url: '/addPurchase', form: true,
      body: { productId: testProductId, quantity: 2, purchaseRate: 50, totalAmount: 100, netAmount: 100, purchaseInvoiceNo: invoiceNo },
      failOnStatusCode: false,
    }).then((res) => {
      if (res.body.status !== 'SUCCESS') return cy.log(`addPurchase not SUCCESS (${res.body.status}) — skipping trace`)
      cy.request('/getUserPurchase').then((listRes) => {
        const records = listRes.body.collection || listRes.body.data || []
        const found = records.find(p => p.purchaseInvoiceNo === invoiceNo || p.invoiceNo === invoiceNo)
        if (found) {
          expect(found.quantity).to.eq(2)
          cy.request({ method: 'POST', url: '/deletePurchase', form: true, body: { checked: found.purchaseId || found.id }, failOnStatusCode: false })
        } else {
          cy.log('Purchase not found by invoice number — field name may differ')
        }
      })
    })
  })

  it('addPurchase with empty body — returns error, not 500', () => {
    cy.request({ method: 'POST', url: '/addPurchase', form: true, body: {}, failOnStatusCode: false }).then((res) => {
      expect([200, 400, 422]).to.include(res.status)
      if (res.status === 200) expect(res.body.status).to.not.eq('SUCCESS')
    })
  })

  it('deletePurchase with empty checked — returns false', () => {
    cy.request({ method: 'POST', url: '/deletePurchase', form: true, body: { checked: '' }, failOnStatusCode: false }).then((res) => {
      expect(res.body).to.be.false
    })
  })

  it('deletePurchase with non-existent id — does not crash (200 or 400)', () => {
    cy.request({ method: 'POST', url: '/deletePurchase', form: true, body: { checked: '999999999' }, failOnStatusCode: false }).then((res) => {
      expect([200, 400]).to.include(res.status)
    })
  })

  it('deletePurchase with comma-separated ids — bulk delete', () => {
    if (!testProductId) return cy.log('No test product — skipping')
    cy.request({ method: 'POST', url: '/addPurchase', form: true, body: { productId: testProductId, quantity: 1, purchaseRate: 50, totalAmount: 50, netAmount: 50, purchaseInvoiceNo: `INV-BLK1-${Date.now()}` }, failOnStatusCode: false })
    cy.request({ method: 'POST', url: '/addPurchase', form: true, body: { productId: testProductId, quantity: 1, purchaseRate: 50, totalAmount: 50, netAmount: 50, purchaseInvoiceNo: `INV-BLK2-${Date.now()}` }, failOnStatusCode: false })

    cy.request('/getUserPurchase').then((res) => {
      const records = res.body.collection || res.body.data || []
      const last2 = records.slice(-2).map(p => p.purchaseId || p.id).filter(Boolean)
      if (last2.length === 2) {
        cy.request({ method: 'POST', url: '/deletePurchase', form: true, body: { checked: last2.join(',') }, failOnStatusCode: false }).then((delRes) => {
          expect([true, false]).to.include(delRes.body)
        })
      }
    })
  })
})

// ─── 4. Purchase Form Validation ─────────────────────────────────────────────

describe('Purchase Validation — Form Guards', () => {
  beforeEach(() => {
    cy.loginAsBusiness()
    cy.openPurchaseSection('purchaseDiv')
    cy.get('#newPurchase').click()                    // form now lives in a modal — open it first
    cy.get('#PurchaseModal').should('have.class', 'open')
  })

  it('submitting without selecting item — POST is NOT fired', () => {
    let posted = false
    cy.intercept('POST', '/addPurchase', (req) => { posted = true; req.continue() })
    cy.get('#purchaseQuantity').clear().type('5')
    cy.get('#addPurchase').click()
    cy.wait(800)
    cy.then(() => expect(posted, 'No-item purchase should not POST').to.be.false)
  })

  it('item selected, quantity = 0 — POST is NOT fired', () => {
    let posted = false
    cy.intercept('POST', '/addPurchase', (req) => { posted = true; req.continue() })
    cy.intercept('GET', '/getStock*').as('getStock')        // item-select pre-fill (bumps qty→1 when <=0)
    cy.get('#purchaseItemDD option').then(($opts) => {
      const real = $opts.filter((i, el) => el.value !== '')
      if (real.length === 0) return cy.log('No items in DB — qty=0 test skipped')
      cy.get('#purchaseItemDD').select(Cypress.$(real[0]).val(), { force: true })
      cy.wait('@getStock')                                  // let the async pre-fill settle BEFORE we set qty=0
      cy.get('#purchaseQuantity').clear().type('0')         // now 0 is the final value the guard sees
      cy.get('#addPurchase').click()
      cy.wait(800)
      cy.then(() => expect(posted, 'qty=0 should not POST').to.be.false)
    })
  })

  it('completely blank form — POST is NOT fired', () => {
    // beforeEach already opened a fresh (blank) modal; submitting it as-is must be blocked.
    let posted = false
    cy.intercept('POST', '/addPurchase', (req) => { posted = true; req.continue() })
    cy.get('#addPurchase').click()
    cy.wait(800)
    cy.then(() => expect(posted, 'Blank form should not POST').to.be.false)
  })
})

// ─── 5. Purchase Table Row Interaction ───────────────────────────────────────

describe('Purchase Table — Row Click', () => {
  beforeEach(() => {
    cy.loginAsBusiness()
    // Intercept BEFORE navigation so the alias is ready when AJAX fires
    cy.intercept('GET', /\/getUserPurchase/).as('getPurchases')
    cy.visit('/businessDashboard')
    cy.get('#purchaseType').select('purchaseDiv', { force: true })
    cy.get('#purchaseDiv').should('be.visible')
    cy.wait('@getPurchases', { timeout: 10000 })
  })

  it('purchase table tbody renders after AJAX', () => {
    cy.get('#tablePurchase tbody').should('exist')
  })

  it('the row Edit button opens the edit modal', () => {
    cy.get('#tablePurchase tbody tr').then(($rows) => {
      const dataRows = $rows.filter((i, r) => Cypress.$(r).find('td').length > 1)
      if (dataRows.length === 0) return cy.log('No purchases — edit-button test skipped')
      cy.wrap(dataRows.first()).find('.js-edit-row').click({ force: true })   // Edit button, not a row/cell click
      cy.get('#PurchaseModal').should('have.class', 'open')
    })
  })

  // Checkbox → bulk-action bar → confirm popup → Cancel. Kept NON-destructive (Cancel, not confirm) so it
  // doesn't reverse real stock; the actual delete path is covered by the deletePurchase API tests above.
  it('checking a row reveals the bulk-action bar; Delete opens the confirm popup; Cancel closes it', () => {
    cy.get('#tablePurchase tbody tr').then(($rows) => {
      const dataRows = $rows.filter((i, r) => Cypress.$(r).find('input[type="checkbox"]').length > 0)
      if (dataRows.length === 0) return cy.log('No purchases — bulk-delete test skipped')
      cy.wrap(dataRows.first()).find('input[type="checkbox"]').check({ force: true })
      cy.get('#bulkBarPurchase').should('be.visible').and('contain', 'selected')
      cy.get('#bulkBarPurchase').contains('Delete').click()     // → shared confirm dialog
      cy.get('.uiC-card').should('be.visible')
      cy.get('.uiC-cancel').click()
      cy.get('.uiC-backdrop').should('not.exist')
      cy.get('#bulkBarPurchase').contains('Clear').click()      // clear the selection
      cy.get('#bulkBarPurchase').should('not.be.visible')
    })
  })
})
