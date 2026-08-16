/**
 * B2B P2-UI (#10) — the Price Rules screen.
 *
 * WHY THIS GATE EXISTS: Phase 2 shipped a working rule engine and a CRUD API, and was marked green on a
 * backend gate that called the API directly. There was no screen, so the owner the feature was built for
 * could not author a rule at all. A backend gate cannot catch that — only this one can.
 *
 * THE GUARANTEES THIS GATE PROTECTS:
 *   1. an owner can reach the screen from the sidebar and author a rule with no API client
 *   2. the table is ordered by the RESOLVER's precedence, and says which rule is the specific one
 *   3. an overlapping rule is labelled "Overridden by #n" — the silent-no-op that makes owners think the
 *      system ignores them is the single most likely support call this screen prevents
 *   4. a rule created THROUGH THE UI is the rule the pricing engine actually applies to a sale
 *   5. every string is translated — only the ui.js.* prefix reaches the browser
 *
 * Design: microservices/docs/slices/b2b-P2-pricing.md  (§ P2-UI)
 *
 * Run headed:
 *   npx cypress run --spec cypress/e2e/business/price-rules-screen.cy.js --headed --no-exit --config screenshotOnRunFailure=false
 */
const uniq = () => `${Date.now()}${Math.floor(Math.random() * 1000)}`

const list = (body) => {
  for (const key of ['collection', 'data', 'object']) {
    if (Array.isArray(body && body[key])) return body[key]
  }
  return Array.isArray(body) ? body : []
}

const customer = (name, customerType) =>
  cy.request({
    method: 'POST', url: '/addCustomer', form: true, failOnStatusCode: false,
    body: { name, contact: 'C' + uniq(), customerType: customerType || 'RETAILER' },
  })
    .then((r) => {
      expect(r.body.status, JSON.stringify(r.body)).to.eq('SUCCESS')
      return cy.request('/getUserCustomer')
    })
    .then((r) => {
      const c = list(r.body).find((x) => x.name === name)
      expect(c, 'customer created').to.exist
      return cy.wrap(c)
    })

/** Remove every rule so one spec's fixtures cannot change another's prices. */
const clearPriceRules = () =>
  cy.request({ url: '/priceRules', failOnStatusCode: false }).then((r) => {
    const rules = typeof r.body === 'string' ? JSON.parse(r.body || '[]') : (r.body || [])
    rules.forEach((rule) => {
      cy.request({
        method: 'POST', url: '/deletePriceRule', failOnStatusCode: false,
        headers: { 'Content-Type': 'application/json' }, body: { id: rule.id },
      })
    })
  })

/** Open the screen the way an owner does — through the sidebar, not by calling a function. */
const openPriceRules = () => {
  cy.visit('/businessDashboard')
  cy.get('#snavSettings .snav-btn').click({ force: true })
  cy.get('#navPriceRules').click({ force: true })
  cy.get('#PriceRuleDiv', { timeout: 10000 }).should('be.visible')
}

/**
 * Fill and submit the inline editor.
 *
 * Every `.select()` here passes `{ force: true }`: `searchable-selects.js` converts effectively every
 * <select> on the page into a bootstrap-select (it skips only nav headings, `[data-no-search]`,
 * DataTables-owned and `_length` selects), which sets the real element to display:none and renders a
 * button in its place. Cypress will not act on the hidden native element without force. Forcing is the
 * right call because the intent is to SET A VALUE; a case asserting the control is visible to a human
 * must instead assert on `.next('.bootstrap-select')`.
 */
const authorRule = ({ customerId, customerType, productId, mode, value, priority }) => {
  cy.get('#prScope').select(customerId ? 'CUSTOMER' : 'TYPE', { force: true })
  if (customerId) {
    // The pickers load over the network — wait for THIS option rather than for "some options",
    // so a stale list cannot satisfy the wait.
    cy.get(`#prCustomerId option[value="${customerId}"]`, { timeout: 10000 }).should('exist')
    cy.get('#prCustomerId').select(String(customerId), { force: true })
  } else {
    cy.get('#prCustomerType').select(customerType, { force: true })
  }
  cy.get('#prTarget').select('PRODUCT', { force: true })
  cy.get(`#prProductId option[value="${productId}"]`, { timeout: 10000 }).should('exist')
  cy.get('#prProductId').select(String(productId), { force: true })
  cy.get('#prMode').select(mode, { force: true })
  cy.get('#prValue').clear().type(String(value))
  if (priority != null) cy.get('#prPriority').clear().type(String(priority))
  cy.contains('#PriceRuleForm button', /add rule|save rule/i).click()
}

describe('B2B P2-UI — the Price Rules screen (#10)', () => {

  beforeEach(() => {
    cy.loginAsOwner()
    clearPriceRules()
  })

  it('the owner can reach the screen from the sidebar', () => {
    openPriceRules()
    cy.get('#tablePriceRule').should('exist')
    cy.get('#PriceRuleForm').should('exist')
  })

  it('an empty tenant is told what that MEANS, not just that the table is empty', () => {
    // "No rules" and "every customer pays catalog price" are the same fact; only the second is useful.
    openPriceRules()
    cy.get('#tablePriceRule tbody').should('contain.text', 'catalog price')
  })

  it('THE point of the slice: a rule authored in the UI prices a real sale', () => {
    const name = 'PR_UI_' + uniq()
    cy.seedProduct({ name: 'PRProd_' + uniq(), sellingPrice: 200, stock: 20 }).then((p) => {
      customer(name).then((c) => {
        openPriceRules()
        authorRule({ customerId: c.customerId, productId: p.productId, mode: 'FIXED', value: 150 })

        // it is listed, as the most specific kind of rule
        cy.get('#tablePriceRule tbody tr').should('have.length', 1)
        cy.get('#tablePriceRule tbody tr').first().should('contain.text', name)

        // and the ENGINE honours it — the loop a backend-only gate could not close.
        // No sellRate is submitted, so the server prices the line from the rule.
        cy.request({
          method: 'POST', url: '/addSell', headers: { 'Content-Type': 'application/json' },
          failOnStatusCode: false,
          body: {
            customer: { customerId: c.customerId, name: c.name, contact: c.contact },
            sales: [{ productId: p.productId, quantity: 1 }],
            tenders: [{ method: 'CASH', amount: 150 }], paidAmount: 150, grandTotal: 150,
            idempotencyKey: 'cy-prui-' + uniq(),
          },
        }).then((s) => {
          expect(s.body.status, JSON.stringify(s.body)).to.eq('SUCCESS')
          // Read back by INVOICE NUMBER — that is /getReceipt. /getSellInvoice keys on sellId and would
          // simply not bind, which is what made this assertion read a null object.
          cy.request('/getReceipt?invoiceNo=' + encodeURIComponent(s.body.object)).then((r) => {
            const inv = r.body.object || r.body.data
            expect(inv, `receipt for ${s.body.object}: ${JSON.stringify(r.body)}`).to.exist
            const line = (inv.sales || [])[0]
            expect(line, 'the invoice has a line').to.exist
            expect(Number(line.sellRate), 'the UI-authored contract price, not the 200 catalog price')
              .to.be.closeTo(150, 0.01)
          })
        })
      })
    })
  })

  it('the table is ordered by the resolver precedence and names the specificity', () => {
    const name = 'PR_Ord_' + uniq()
    cy.seedProduct({ name: 'POrd_' + uniq(), sellingPrice: 100, stock: 10 }).then((p) => {
      customer(name).then((c) => {
        openPriceRules()
        // a TIER rule first, then a more specific CUSTOMER rule — inserted in the WRONG order on purpose
        authorRule({ customerType: 'RETAILER', productId: p.productId, mode: 'PERCENT', value: 10 })
        cy.get('#tablePriceRule tbody tr').should('have.length', 1)
        authorRule({ customerId: c.customerId, productId: p.productId, mode: 'FIXED', value: 80 })
        cy.get('#tablePriceRule tbody tr').should('have.length', 2)

        // The customer rule must be listed FIRST — insertion order must not decide what the owner reads.
        cy.get('#tablePriceRule tbody tr').first().should('contain.text', name)
        cy.get('#tablePriceRule tbody tr').first().should('contain.text', '1.')
        cy.get('#tablePriceRule tbody tr').eq(1).should('contain.text', 'Retailer')
      })
    })
  })

  it('an overlapping rule is labelled overridden — the silent no-op made visible', () => {
    const name = 'PR_Dup_' + uniq()
    cy.seedProduct({ name: 'PDup_' + uniq(), sellingPrice: 100, stock: 10 }).then((p) => {
      customer(name).then((c) => {
        openPriceRules()
        // Same customer, same product, twice. Only the higher priority can ever apply.
        authorRule({ customerId: c.customerId, productId: p.productId, mode: 'FIXED', value: 90, priority: 1 })
        cy.get('#tablePriceRule tbody tr').should('have.length', 1)
        authorRule({ customerId: c.customerId, productId: p.productId, mode: 'FIXED', value: 80, priority: 5 })
        cy.get('#tablePriceRule tbody tr').should('have.length', 2)

        // the winner (priority 5) is first and carries no warning; the loser says so plainly
        cy.get('#tablePriceRule tbody tr').first().should('not.contain.text', 'Overridden')
        cy.get('#tablePriceRule tbody tr').eq(1).should('contain.text', 'Overridden')
      })
    })
  })

  it('edit round-trips a rule back into the form, and delete removes it', () => {
    const name = 'PR_Ed_' + uniq()
    cy.seedProduct({ name: 'PEd_' + uniq(), sellingPrice: 100, stock: 10 }).then((p) => {
      customer(name).then((c) => {
        openPriceRules()
        authorRule({ customerId: c.customerId, productId: p.productId, mode: 'PERCENT', value: 15 })
        cy.get('#tablePriceRule tbody tr').should('have.length', 1)

        cy.get('#tablePriceRule tbody tr').first().contains('button', /edit/i).click()
        cy.get('#prId').should('not.have.value', '')
        cy.get('#prMode').should('have.value', 'PERCENT')
        cy.get('#prValue').should('have.value', '15')
        cy.get('#prCustomerId').should('have.value', String(c.customerId))

        // editing must UPDATE, never create a second rule
        cy.get('#prValue').clear().type('20')
        cy.contains('#PriceRuleForm button', /save rule/i).click()
        cy.get('#tablePriceRule tbody tr').should('have.length', 1)
        cy.get('#tablePriceRule tbody tr').first().should('contain.text', '20')

        cy.get('#tablePriceRule tbody tr').first().contains('button', /delete/i).click()
        cy.get('[data-ui-confirm="ok"]').click()
        cy.get('#tablePriceRule tbody').should('contain.text', 'catalog price')
      })
    })
  })

  it('a percentage outside 0-100 is refused before it can reach the server', () => {
    cy.seedProduct({ name: 'PBad_' + uniq(), sellingPrice: 100 }).then((p) => {
      customer('PR_Bad_' + uniq()).then((c) => {
        openPriceRules()
        authorRule({ customerId: c.customerId, productId: p.productId, mode: 'PERCENT', value: 120 })
        cy.get('#prError').should('not.have.text', '')
        cy.get('#tablePriceRule tbody').should('contain.text', 'catalog price')   // nothing was saved
      })
    })
  })

  it('every string on the screen is translated', () => {
    cy.visit('/businessDashboard')
    cy.window().then((w) => {
      ;['ui.js.customerProduct', 'ui.js.overridden', 'ui.js.noPriceRulesYet', 'ui.js.addRule',
        'ui.js.percentRange', 'ui.js.live', 'ui.js.offCatalog']
        .forEach((k) => expect(w.t(k), `${k} resolves — only ui.js.* reaches the browser`).to.not.eq(k))
    })
  })
})
