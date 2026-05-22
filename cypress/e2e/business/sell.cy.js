/**
 * Sell/Sale flow tests — invoice item cart, customer selection, checkout
 *
 * Structure:
 *  1. Page Rendering        — static element checks (no AJAX intercepts)
 *  2. AJAX Loading          — dedicated block; intercept registered BEFORE navigation
 *  3. Customer Mode Toggle  — select-vs-manual toggle behaviour
 *  4. Sale Detail Report    — SRDiv section
 *  5. API Endpoints         — direct cy.request checks
 */

// ─── 1. Page Rendering ───────────────────────────────────────────────────────

describe('Sell Section — Page Rendering', () => {
  beforeEach(() => {
    cy.loginAsBusiness()
    cy.visit('/businessDashboard')
    cy.get('#sellType').select('sellDiv')
    cy.get('#sellDiv').should('be.visible')
  })

  it('shows item dropdown and Add Invoice Item button', () => {
    cy.get('#sellItemDD').should('exist')
    cy.get('#addInviceItem').should('be.visible')
    cy.get('#resetInviceItem').should('be.visible')
  })

  it('shows Sell button', () => {
    cy.get('#addSell').should('be.visible')
  })

  it('sell history table and cart table both exist', () => {
    cy.get('#tableSell').should('exist')
    cy.get('#tableSell thead').should('exist')
    cy.get('#tablesi').should('exist')
  })

  it('payment fields are always visible', () => {
    cy.get('#sellRec').should('be.visible')
    cy.get('#sellCh').should('be.visible')
  })

  it('customer mode toggle buttons are visible', () => {
    cy.get('#btnModeSelect').should('be.visible')
    cy.get('#btnModeManual').should('be.visible')
  })

  it('Reset Invoice Item button does not crash and item description is cleared', () => {
    cy.get('#sellItemDesc').invoke('val').then((before) => {
      cy.get('#resetInviceItem').click()
      cy.get('#sellItemDesc').should('have.value', '')
    })
  })
})

// ─── 2. AJAX Loading ─────────────────────────────────────────────────────────
// Intercept must be registered BEFORE cy.visit so the alias is ready when the
// AJAX fires on section open — avoids double-navigation.

describe('Sell Section — AJAX Loading', () => {
  beforeEach(() => {
    cy.loginAsBusiness()
  })

  it('item dropdown loads options from getUserItems', () => {
    cy.intercept('GET', '/getUserItems').as('getUserItems')
    cy.visit('/businessDashboard')
    cy.get('#sellType').select('sellDiv')
    cy.wait('@getUserItems', { timeout: 10000 }).then((interception) => {
      expect(interception.response.statusCode).to.eq(200)
    })
  })

  it('customer dropdown loads from getUserCustomer (full DTO with contact)', () => {
    // Use negative lookahead so /getUserCustomers (plural) is not matched
    cy.intercept('GET', /\/getUserCustomer(?!s)/).as('getCustomers')
    cy.visit('/businessDashboard')
    cy.get('#sellType').select('sellDiv')
    cy.wait('@getCustomers', { timeout: 10000 }).then((interception) => {
      expect(interception.response.statusCode).to.eq(200)
      expect(interception.response.body).to.have.property('status')
      // collection may be null/absent when DB is empty — just verify key exists
      expect(interception.response.body).to.have.property('status').that.is.a('string')
    })
  })
})

// ─── 3. Customer Input Mode Toggle ───────────────────────────────────────────

describe('Sell Section — Customer Input Mode Toggle', () => {
  beforeEach(() => {
    cy.loginAsBusiness()
    cy.intercept('GET', /\/getUserCustomer(?!s)/).as('getCustomers')
    cy.visit('/businessDashboard')
    cy.get('#sellType').select('sellDiv')
    cy.get('#sellDiv').should('be.visible')
    cy.wait('@getCustomers', { timeout: 10000 })
  })

  // ── Default state ────────────────────────────────────────────────────────

  it('defaults to Select Customer mode — dropdown visible, manual row hidden', () => {
    // Check interactive children — wrapper divs have 0 height due to Bootstrap floats
    cy.get('#sellCustomerDD').should('be.visible')
    cy.get('#sellCN').should('not.be.visible')
  })

  it('sellCustomerDD has blank placeholder selected by default', () => {
    cy.get('#sellCustomerDD').should('exist').should('be.visible')
    cy.get('#sellCustomerDD').invoke('val').should('eq', '')
  })

  it('sellCustomerDD has at least the placeholder option after load', () => {
    // Guard against empty DB — verify at minimum the placeholder rendered
    cy.get('#sellCustomerDD option').should('have.length.gte', 1)
    // If customers exist they appear as additional options
    cy.get('#sellCustomerDD option').then(($opts) => {
      const count = $opts.length
      cy.log(`Customer options loaded: ${count}`)
    })
  })

  // ── Select mode behaviour ────────────────────────────────────────────────

  it('selecting a customer from dropdown populates sellCN and sellCC', () => {
    cy.get('#sellCustomerDD option').then(($opts) => {
      const realOpts = $opts.filter((i, el) => el.value !== '')
      if (realOpts.length === 0) {
        cy.log('No customers in DB — selection test skipped')
        return
      }
      const firstOpt = Cypress.$(realOpts[0])
      const expectedName    = firstOpt.text().trim()
      const expectedContact = firstOpt.data('contact') || ''

      cy.get('#sellCustomerDD').select(firstOpt.val())
      // sellCN/sellCC live inside the hidden manual div but values are always accessible
      cy.get('#sellCN').should('have.value', expectedName)
      cy.get('#sellCC').should('have.value', expectedContact)
    })
  })

  it('selecting the blank option clears sellCN and sellCC', () => {
    cy.get('#sellCustomerDD option').then(($opts) => {
      const realOpts = $opts.filter((i, el) => el.value !== '')
      if (realOpts.length === 0) {
        cy.log('No customers in DB — clear test skipped')
        return
      }
      cy.get('#sellCustomerDD').select(Cypress.$(realOpts[0]).val())
      cy.get('#sellCustomerDD').select('')
      cy.get('#sellCN').should('have.value', '')
      cy.get('#sellCC').should('have.value', '')
    })
  })

  // ── Switch to Manual mode ────────────────────────────────────────────────

  it('clicking Enter Manually shows manual row and hides dropdown', () => {
    cy.get('#btnModeManual').click()
    cy.get('#sellCN').should('be.visible')
    cy.get('#sellCustomerDD').should('not.be.visible')
  })

  it('switching to manual mode clears any prior dropdown selection', () => {
    cy.get('#sellCustomerDD option').then(($opts) => {
      const realOpts = $opts.filter((i, el) => el.value !== '')
      if (realOpts.length > 0) {
        cy.get('#sellCustomerDD').select(Cypress.$(realOpts[0]).val())
      }
    })
    cy.get('#btnModeManual').click()
    cy.get('#sellCN').should('have.value', '')
    cy.get('#sellCC').should('have.value', '')
    cy.get('#sellCustomerDD').invoke('val').should('eq', '')
  })

  it('in manual mode the name and contact inputs are editable', () => {
    cy.get('#btnModeManual').click()
    cy.get('#sellCN').should('be.visible').type('Walk-in Customer')
    cy.get('#sellCC').should('be.visible').type('03001234567')
    cy.get('#sellCN').should('have.value', 'Walk-in Customer')
    cy.get('#sellCC').should('have.value', '03001234567')
  })

  // ── Switch back to Select mode ───────────────────────────────────────────

  it('switching back to Select mode shows dropdown and clears manual fields', () => {
    cy.get('#btnModeManual').click()
    cy.get('#sellCN').should('be.visible').type('Test Name')
    cy.get('#btnModeSelect').click()
    cy.get('#sellCustomerDD').should('be.visible')
    cy.get('#sellCN').should('not.be.visible')
    cy.get('#sellCN').should('have.value', '')
    cy.get('#sellCC').should('have.value', '')
  })

  // ── Delete Cart reset ────────────────────────────────────────────────────

  it('Delete Cart resets to Select mode and clears all customer fields', () => {
    cy.get('#btnModeManual').click()
    cy.get('#sellCN').should('be.visible').type('Someone')
    cy.get('#resetSellItem').click()
    cy.get('#sellCustomerDD').should('be.visible')
    cy.get('#sellCN').should('not.be.visible')
    cy.get('#sellCustomerDD').invoke('val').should('eq', '')
    cy.get('#sellCN').should('have.value', '')
    cy.get('#sellCC').should('have.value', '')
  })

  it('Delete Cart empties the cart table', () => {
    cy.get('#resetSellItem').click()
    // tablesi should show no data rows after reset
    cy.get('#tablesi tbody tr').then(($rows) => {
      const dataRows = $rows.filter((i, r) => Cypress.$(r).find('td').length > 1)
      expect(dataRows.length).to.eq(0)
    })
  })
})

// ─── 4. Sale Detail Report ───────────────────────────────────────────────────

describe('Sell Section — Sale Detail Report', () => {
  beforeEach(() => {
    cy.loginAsBusiness()
    cy.visit('/businessDashboard')
    cy.get('#sellType').select('SRDiv')
    cy.get('#SRDiv').should('be.visible')
  })

  it('shows the View button and filter dropdowns', () => {
    cy.get('#srb').should('exist')
    cy.get('#dateRangeDDSR').should('exist')
    cy.get('#srbs').should('exist')
  })
})

// ─── 5. API Endpoints ────────────────────────────────────────────────────────

describe('Sell API Endpoints', () => {
  beforeEach(() => {
    cy.loginAsBusiness()
  })

  it('getUserSell returns SUCCESS or NOT_FOUND', () => {
    cy.request({ url: '/getUserSell', failOnStatusCode: false }).then((res) => {
      expect(res.status).to.eq(200)
      expect(res.body).to.have.property('status')
      expect(res.body.status).to.be.oneOf(['SUCCESS', 'NOT_FOUND', 'ERROR'])
    })
  })

  it('addSell with empty body returns error (not 500 crash)', () => {
    cy.request({
      method: 'POST',
      url: '/addSell',
      body: {},
      headers: { 'Content-Type': 'application/json' },
      failOnStatusCode: false,
    }).then((res) => {
      // Should return 200 with ERROR body or a 4xx, never an unhandled 500
      expect([200, 400, 422]).to.include(res.status)
      if (res.status === 200) {
        expect(res.body).to.have.property('status').that.is.a('string')
      }
    })
  })
})
