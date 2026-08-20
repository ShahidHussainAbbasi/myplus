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

/**
 * WHY THE CLICKS IN THIS FILE CARRY `{ timeout: 30000 }`
 *
 * The app shows a global AJAX overlay (`#appAjaxOverlay` → `.ao-box`, /js/common/ajax-overlay.js) on jQuery's
 * ajaxStart and hides it on ajaxStop. When the server is busy — notably when this spec runs inside the full
 * suite rather than alone — the overlay is still up when a test clicks, and Cypress correctly refuses with
 * "covered by another element: <div class='ao-box'>".
 *
 * A separate "wait for the overlay to clear" step is NOT enough, and was tried: the overlay clears, the
 * assertion passes, another request starts, and the click then fails inside its own 4s actionability window.
 * Giving the CLICK the long timeout closes that gap — Cypress re-checks "is it covered?" continuously and
 * clicks the instant the overlay lifts. One mechanism, no window for the state to change behind it.
 *
 * Deliberately NOT `{force:true}`: forcing clicks THROUGH an overlay a shopkeeper cannot click through, so a
 * genuinely stuck spinner would pass this gate and fail in production.
 */

// ─── 1. Page Rendering ───────────────────────────────────────────────────────

describe('Sell Section — Page Rendering', () => {
  beforeEach(() => {
    cy.loginAsBusiness()
    cy.visit('/businessDashboard')
    // sellType is off-screen — force:true required
    cy.get('#sellType').select('sellDiv', { force: true })
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

  it('item dropdown loads options from the product picker', () => {
    // M4e.1b (slice 98): the picker lists catalog Products (value=productId), not /getUserItems.
    // PERF-8 (2026-08-20): the READ moved from /catalogProducts to /catalogProductPicker — a three-field,
    // active-only projection instead of the whole 23-field product master (618 KB -> 77 KB, 3 requests -> 1).
    // This spec asserted the old endpoint by name, so it went red on a change that was entirely intended:
    // the O4 "test asserts a rule the product no longer has" shape.
    cy.intercept('GET', '**/catalogProductPicker*').as('picker')
    cy.visit('/businessDashboard')
    cy.get('#sellType').select('sellDiv', { force: true })
    cy.wait('@picker', { timeout: 10000 }).then((interception) => {
      expect(interception.response.statusCode).to.eq(200)
    })
    // The point is the OPTIONS, not the request — assert what the operator actually gets.
    cy.get('#sellItemDD option', { timeout: 10000 }).should('have.length.greaterThan', 1)
  })

  it('customer dropdown loads from getUserCustomer (full DTO with contact)', () => {
    // Use negative lookahead so /getUserCustomers (plural) is not matched
    cy.intercept('GET', /\/getUserCustomer(?!s)/).as('getCustomers')
    cy.visit('/businessDashboard')
    cy.get('#sellType').select('sellDiv', { force: true })
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
    cy.get('#sellType').select('sellDiv', { force: true })
    cy.get('#sellDiv').should('be.visible')
    cy.wait('@getCustomers', { timeout: 10000 })
  })

  // ── Default state ────────────────────────────────────────────────────────

  /**
   * #sellCustomerDD is a `.selectpicker`. bootstrap-select sets `style="display:none"` on the native <select>
   * and renders its own button in place of it, so asserting the NATIVE element is visible asserts something
   * that is false by design — and would only pass while the plugin had not yet initialised.
   *
   * The widget is what the shopkeeper actually sees, so that is what "the dropdown is showing" must mean. This
   * still fails if Select mode genuinely stops rendering, because the widget lives inside the same mode
   * container — it does not weaken the tests, it points them at the right element.
   */
  const customerDropdown = () => cy.get('#customerSelectMode')

  it('defaults to Select Customer mode — dropdown visible, manual row hidden', () => {
    customerDropdown().should('be.visible')
    cy.get('#sellCN').should('not.be.visible')
  })

  it('sellCustomerDD has blank placeholder selected by default', () => {
    customerDropdown().should('be.visible')
    // .val() reads fine on the plugin-hidden native select — the value is the source of truth either way.
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

      // 108b made this a runtime .selectpicker, so the native <select> is display:none — drive it with force,
      // exactly as this file already does for #sellType.
      cy.get('#sellCustomerDD').select(firstOpt.val(), { force: true })
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
      cy.get('#sellCustomerDD').select(Cypress.$(realOpts[0]).val(), { force: true })
      cy.get('#sellCustomerDD').select('', { force: true })
      cy.get('#sellCN').should('have.value', '')
      cy.get('#sellCC').should('have.value', '')
    })
  })

  // ── Switch to Manual mode ────────────────────────────────────────────────

  it('clicking Enter Manually shows manual row and hides dropdown', () => {
    cy.get('#btnModeManual').click({ timeout: 30000 })
    cy.get('#sellCN').should('be.visible')
    cy.get('#sellCustomerDD').should('not.be.visible')
  })

  it('switching to manual mode clears any prior dropdown selection', () => {
    cy.get('#sellCustomerDD option').then(($opts) => {
      const realOpts = $opts.filter((i, el) => el.value !== '')
      if (realOpts.length > 0) {
        // { force: true }: searchable-selects.js makes this a bootstrap-select, so the native <select>
        // is display:none and Cypress will not act on it unenforced. Setting a value, not asserting
        // visibility — see the note at line 344 in this file.
        cy.get('#sellCustomerDD').select(Cypress.$(realOpts[0]).val(), { force: true })
      }
    })
    cy.get('#btnModeManual').click({ timeout: 30000 })
    cy.get('#sellCN').should('have.value', '')
    cy.get('#sellCC').should('have.value', '')
    cy.get('#sellCustomerDD').invoke('val').should('eq', '')
  })

  it('in manual mode the name and contact inputs are editable', () => {
    cy.get('#btnModeManual').click({ timeout: 30000 })
    cy.get('#sellCN').should('be.visible').type('Walk-in Customer')
    cy.get('#sellCC').should('be.visible').type('03001234567')
    cy.get('#sellCN').should('have.value', 'Walk-in Customer')
    cy.get('#sellCC').should('have.value', '03001234567')
  })

  // ── Switch back to Select mode ───────────────────────────────────────────

  it('switching back to Select mode shows dropdown and clears manual fields', () => {
    cy.get('#btnModeManual').click({ timeout: 30000 })
    cy.get('#sellCN').should('be.visible').type('Test Name')
    cy.get('#btnModeSelect').click({ timeout: 30000 })
    customerDropdown().should('be.visible')       // the widget, not the plugin-hidden native <select>
    cy.get('#sellCN').should('not.be.visible')
    cy.get('#sellCN').should('have.value', '')
    cy.get('#sellCC').should('have.value', '')
  })

  // ── Delete Cart reset ────────────────────────────────────────────────────

  it('Delete Cart resets to Select mode and clears all customer fields', () => {
    cy.get('#btnModeManual').click({ timeout: 30000 })
    cy.get('#sellCN').should('be.visible').type('Someone')
    cy.get('#resetSellItem').click({ timeout: 30000 })
    customerDropdown().should('be.visible')       // the widget, not the plugin-hidden native <select>
    cy.get('#sellCN').should('not.be.visible')
    cy.get('#sellCustomerDD').invoke('val').should('eq', '')
    cy.get('#sellCN').should('have.value', '')
    cy.get('#sellCC').should('have.value', '')
  })

  it('Delete Cart empties the cart table', () => {
    cy.get('#resetSellItem').click({ timeout: 30000 })
    // tablesi should show no data rows after reset
    cy.get('#tablesi tbody tr').then(($rows) => {
      const dataRows = $rows.filter((i, r) => Cypress.$(r).find('td').length > 1)
      expect(dataRows.length).to.eq(0)
    })
  })
})

// ─── 4. Customer Mandatory Validation ────────────────────────────────────────

describe('Sell Section — Customer Mandatory Validation', () => {
  // UPDATED 2026-08-13. These asserted that a customer was ALWAYS mandatory. D-24 (2026-08-10, main.js:461)
  // deliberately narrowed that: the customer is required only when the sale LEAVES A BALANCE, because
  //
  //   "a receivable against nobody cannot be chased, aged or collected"
  //
  // while a fully-paid walk-in needs no name. Clicking Complete Sale on an empty, fully-paid form therefore
  // triggers no validation at all, and these cases were asserting a rule the product no longer has.
  //
  // They now establish the precondition first — CREDIT is the tender that guarantees a balance, and is the one
  // case D-24 records as NOT configurable — and assert the same red border on the same elements.
  beforeEach(() => {
    cy.loginAsBusiness()
    cy.intercept('GET', /\/getUserCustomer(?!s)/).as('getCustomers')
    cy.visit('/businessDashboard')
    cy.get('#sellType').select('sellDiv', { force: true })
    cy.get('#sellDiv').should('be.visible')
    cy.wait('@getCustomers', { timeout: 10000 })
    // The precondition: an on-account sale owes money, so it must name who owes it.
    cy.get('#sellPayMethod').select('CREDIT', { force: true })
  })

  it('addSell blocked in Select mode when no customer chosen — dropdown turns red', () => {
    // Ensure blank dropdown (default state)
    cy.get('#sellCustomerDD').invoke('val').should('eq', '')
    cy.get('#addSell').click({ timeout: 30000 })
    cy.get('#sellCustomerDD').should('have.css', 'border-color').and('include', 'rgb(255')
  })

  it('addSell blocked in Manual mode when sellCN is empty — field turns red', () => {
    cy.get('#btnModeManual').click({ timeout: 30000 })
    cy.get('#sellCN').should('be.visible').should('have.value', '')
    cy.get('#addSell').click({ timeout: 30000 })
    cy.get('#sellCN').should('have.css', 'border-color').and('include', 'rgb(255')
  })

  it('addSell clears dropdown red border once a customer is selected', () => {
    cy.get('#addSell').click({ timeout: 30000 })
    cy.get('#sellCustomerDD').should('have.css', 'border-color').and('include', 'rgb(255')

    cy.get('#sellCustomerDD option').then(($opts) => {
      const realOpts = $opts.filter((i, el) => el.value !== '')
      if (realOpts.length === 0) {
        cy.log('No customers in DB — border-clear test skipped')
        return
      }
      cy.get('#sellCustomerDD').select(Cypress.$(realOpts[0]).val(), { force: true })
      cy.get('#sellCustomerDD').should('not.have.css', 'border-color', 'rgb(255, 0, 0)')
    })
  })

  it('addSell clears manual red border once sellCN is filled', () => {
    cy.get('#btnModeManual').click({ timeout: 30000 })
    cy.get('#addSell').click({ timeout: 30000 })
    cy.get('#sellCN').should('have.css', 'border-color').and('include', 'rgb(255')
    cy.get('#sellCN').clear().type('Walk-in Customer')
    cy.get('#sellCN').should('not.have.css', 'border-color', 'rgb(255, 0, 0)')
  })
})

// ─── 6. Sale Detail Report ───────────────────────────────────────────────────

describe('Sell Section — Sale Detail Report', () => {
  beforeEach(() => {
    cy.loginAsBusiness()
    cy.visit('/businessDashboard')
    cy.get('#sellType').select('SRDiv', { force: true })
    cy.get('#SRDiv').should('be.visible')
  })

  it('shows the redesigned filter toolbar (Period + View)', () => {
    cy.get('#dateRangeDDSR').should('exist')
    cy.contains('#SRDiv button', 'View report').should('be.visible')
    // KPI summary starts hidden until a report is loaded
    cy.get('#srKpis').should('not.be.visible')
  })

  it('has the full industry-standard column set', () => {
    // Slice 106: 13 → 14. The added column is Margin (per-line profit, from the Sale Detail Report rebuild) —
    // identified against the template before touching the number, and asserted by name below, because a count
    // edited to agree with reality without checking WHAT changed tests nothing at all.
    const cols = ['Date', 'Invoice', 'Product', 'Qty', 'List', 'Unit',
                  'Line total', 'Tax', 'Net', 'Customer', 'Contact', 'Payment', 'due', 'Margin']
    cy.get('#tableSellReport thead th').should('have.length', 14)
    cols.forEach((c) => cy.get('#tableSellReport thead').contains(c, { matchCase: false }))
  })

  it('Custom range reveals the start/end date pickers', () => {
    // #dateRangeDDSR is a .selectpicker: bootstrap-select sets style="display:none" on the native <select>
    // and renders a button in its place, so cy.select() refuses to act on it. Driving the native element with
    // { force: true } tests the real wiring (onchange -> toggleSRCustomRange) and is the idiom this file
    // already uses six times for #sellType.
    cy.get('#srStartWrap').should('not.be.visible')
    cy.get('#dateRangeDDSR').select('4', { force: true })
    cy.get('#srStartWrap').should('be.visible')
    cy.get('#srEndWrap').should('be.visible')
    cy.get('#dateRangeDDSR').select('0', { force: true })
    cy.get('#srStartWrap').should('not.be.visible')
  })

  it('View report loads data and populates KPIs (or shows empty state)', () => {
    cy.intercept('POST', '**/loadSR').as('loadSR')
    cy.contains('#SRDiv button', 'View report').click()
    cy.wait('@loadSR').its('response.statusCode').should('eq', 200)
    cy.get('@loadSR').then(({ response }) => {
      expect(response.body).to.have.property('status')
      if (response.body.status === 'SUCCESS' && (response.body.collection || []).length) {
        // rows rendered + KPI cards revealed with numeric content
        cy.get('#tableSellReport tbody tr').its('length').should('be.gte', 1)
        cy.get('#srKpis').should('be.visible')
        cy.get('#srkInvoices').invoke('text').should('match', /\d/)
        cy.get('#srkGross').invoke('text').should('not.eq', '—')
      } else {
        // no sales this period → friendly empty message, table stays empty
        cy.get('#tableSellReport tbody tr').should('have.length.lte', 1)
      }
    })
  })
})

// ─── 7. API Endpoints ────────────────────────────────────────────────────────

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

// ─── 8. Invoice Numbering (slice 22) ─────────────────────────────────────────
// A new sale must get a system-generated per-org invoice number (INV-######) and
// successive sales must increment it.

describe('Sell Section — Invoice Numbering (slice 22)', () => {
  let productId   // M4b (slice 91): now sold productId-native (monolith SellDTO carries productId through the proxy)
  const ts = Date.now()
  const iname = `InvItem_${ts}`

  // pull the invoice number out of the addSell response — it may arrive in the
  // GenericResponse `object` field or embedded in the success `message`.
  const invNo = (body) => {
    const fromObj = (body && typeof body.object === 'string') ? body.object : null
    if (fromObj && /^INV-\d{6}$/.test(fromObj)) return fromObj
    const m = ((fromObj || '') + ' ' + (body && body.message || '')).match(/INV-\d{6}/)
    return m ? m[0] : null
  }

  const sellOnce = () => cy.request({
    method: 'POST', url: '/addSell',
    body: {
      customer: { name: `InvCust_${ts}`, contact: `032${ts.toString().slice(-8)}`, paidAmount: 100, dueAmount: 0 },
      sales: [{ productId, quantity: 1, sellRate: 100, totalAmount: 100, netAmount: 100 }],
    },
    headers: { 'Content-Type': 'application/json' }, failOnStatusCode: false,
  })

  before(() => {
    cy.loginAsBusiness()
    // M4b (slice 91): seed via the catalog Product master, then sell PRODUCTID-NATIVE — the monolith SellDTO now
    // carries productId through the /addSell proxy, so the saga uses it directly (no itemId→ItemCatalogMap lookup).
    cy.seedProduct({ name: iname, sku: `INV-${ts}`, sellingPrice: 100, purchaseRate: 50, stock: 50 })
      .then(({ productId: pid }) => { productId = pid })
  })

  beforeEach(() => { cy.loginAsBusiness() })

  it('addSell returns a system-generated INV-###### invoice number', () => {
    if (!productId) return cy.log('No productId — skipping invoice test')
    sellOnce().then((res) => {
      expect(res.status).to.eq(200)
      expect(res.body.status).to.eq('SUCCESS')
      expect(invNo(res.body), `invoice number in ${JSON.stringify(res.body)}`).to.match(/^INV-\d{6}$/)
    })
  })

  it('a second sale gets the next sequential invoice number', () => {
    if (!productId) return cy.log('No productId — skipping')
    sellOnce().then((r1) => {
      const n1 = parseInt((invNo(r1.body) || 'INV-000000').slice(4), 10)
      sellOnce().then((r2) => {
        const n2 = parseInt((invNo(r2.body) || 'INV-000000').slice(4), 10)
        expect(n2, `seq went ${n1} -> ${n2}`).to.eq(n1 + 1)
      })
    })
  })
})
