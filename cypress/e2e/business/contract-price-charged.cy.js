/**
 * B2B P2-UI (#10) — a contract price must actually be CHARGED, not just computed.
 *
 * THE BUG THIS GATE LOCKS DOWN. Server-side the submitted rate wins over a matched rule — deliberately, so a
 * cashier's override beats a rule (`price-override.cy.js` protects that). But the sell screen prefilled the
 * rate box from the CATALOG price, so on every real sale a rule matched, `priceReason` was recorded, and the
 * customer was charged catalog anyway. P2's gate missed it by posting {productId, quantity} with NO sellRate
 * — the one path that takes the server's fallback branch. It proved the engine, never the till.
 *
 * THE GUARANTEES THIS GATE PROTECTS:
 *   1. picking a product for a contract customer puts the CONTRACT price in the rate box, not the catalog one
 *   2. a sale composed ON THE SCREEN charges that price — the whole point
 *   3. choosing the customer AFTER adding items re-prices the cart ("scan first, ask who's buying second")
 *   4. a cashier's typed override still wins — the fix must not take the till away from the person at it
 *   5. a walk-in with no account is untouched: catalog price, no quote, today's behaviour exactly
 *
 * Design: microservices/docs/slices/b2b-P2-pricing.md  (§4, §6)
 *
 * Run headed:
 *   npx cypress run --spec cypress/e2e/business/contract-price-charged.cy.js --headed --no-exit --config screenshotOnRunFailure=false
 */
const uniq = () => `${Date.now()}${Math.floor(Math.random() * 1000)}`

const CATALOG = 200
const CONTRACT = 150

const list = (body) => {
  for (const key of ['collection', 'data', 'object']) {
    if (Array.isArray(body && body[key])) return body[key]
  }
  return Array.isArray(body) ? body : []
}

const customer = (name) =>
  cy.request({
    method: 'POST', url: '/addCustomer', form: true, failOnStatusCode: false,
    body: { name, contact: 'C' + uniq(), customerType: 'RETAILER' },
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

const clearPriceRules = () =>
  cy.request({ url: '/priceRules', failOnStatusCode: false }).then((r) => {
    const rules = typeof r.body === 'string' ? JSON.parse(r.body || '[]') : (r.body || [])
    rules.forEach((rule) =>
      cy.request({
        method: 'POST', url: '/deletePriceRule', failOnStatusCode: false,
        headers: { 'Content-Type': 'application/json' }, body: { id: rule.id },
      }))
  })

/** A contract price for one customer on one product. */
const contractRule = (customerId, productId, value) =>
  cy.request({
    method: 'POST', url: '/savePriceRule', headers: { 'Content-Type': 'application/json' },
    failOnStatusCode: false,
    body: {
      scope: 'CUSTOMER', customerId, target: 'PRODUCT', productId,
      mode: 'FIXED', value, priority: 0, active: true,
    },
  }).then((r) => {
    expect(r.status, JSON.stringify(r.body)).to.eq(200)
    expect(r.body && r.body.id, 'rule saved').to.exist
  })

/** Compose one line on the sell screen exactly as a cashier does. */
const pickProduct = (productId) => {
  // Wait for THIS option, not merely for "some options" — a stale list would satisfy the weaker wait.
  cy.get(`#sellItemDD option[value="${productId}"]`, { timeout: 10000 }).should('exist')
  cy.get('#sellItemDD').select(String(productId), { force: true })
  cy.get('#sellSellRate').should('not.have.value', '')
}

const selectCustomer = (customerId) => {
  cy.get(`#sellCustomerDD option[value="${customerId}"]`, { timeout: 10000 }).should('exist')
  cy.get('#sellCustomerDD').select(String(customerId), { force: true })
}

describe('B2B P2-UI — the contract price is the price charged (#10)', () => {

  beforeEach(() => {
    cy.loginAsOwner()
    clearPriceRules()
  })

  it('picking a product for a contract customer shows the CONTRACT price, and says why', () => {
    const name = 'CP_Show_' + uniq()
    cy.seedProduct({ name: 'CPShow_' + uniq(), sellingPrice: CATALOG, stock: 20 }).then((p) => {
      customer(name).then((c) => {
        contractRule(c.customerId, p.productId, CONTRACT)

        cy.openSellSection('sellDiv')
        selectCustomer(c.customerId)
        pickProduct(p.productId)

        // The box held 200 the moment the product was picked; the quote replaces it with 150.
        cy.get('#sellSellRate', { timeout: 10000 }).should('have.value', String(CONTRACT))
        // And the cashier can explain it — a price nobody can justify is the problem rules exist to avoid.
        cy.get('#sellPriceReason').should('be.visible').invoke('text').should('match', /contract/i)
      })
    })
  })

  it('THE fix: a sale composed on the screen CHARGES the contract price', () => {
    const name = 'CP_Charge_' + uniq()
    cy.seedProduct({ name: 'CPChg_' + uniq(), sellingPrice: CATALOG, stock: 20 }).then((p) => {
      customer(name).then((c) => {
        contractRule(c.customerId, p.productId, CONTRACT)

        cy.openSellSection('sellDiv')
        selectCustomer(c.customerId)
        pickProduct(p.productId)
        cy.get('#sellSellRate').should('have.value', String(CONTRACT))
        cy.get('#sellItems').clear().type('2')
        cy.get('#addInviceItem').click()

        // the cart carries the contract price, not the catalog one.
        // Guard against DataTables' empty-table placeholder, which is a <tr> with ONE cell.
        cy.get('#tablesi tbody tr:first td').should('have.length.greaterThan', 1)
        cy.window().then((w) => {
          const line = w.data.find((d) => String(d.productId) === String(p.productId))
          expect(line, 'the line is in the cart').to.exist
          expect(Number(line.sellRate), 'the cart line carries the contract price').to.be.closeTo(CONTRACT, 0.01)
          expect(Number(line.totalAmount), 'and its total moved with it (150 x 2)')
            .to.be.closeTo(CONTRACT * 2, 0.01)
        })
      })
    })
  })

  it('choosing the customer AFTER adding items re-prices the cart', () => {
    // "Scan first, ask who is buying second" is an ordinary counter habit. Before the fix it charged catalog.
    const name = 'CP_After_' + uniq()
    cy.seedProduct({ name: 'CPAft_' + uniq(), sellingPrice: CATALOG, stock: 20 }).then((p) => {
      customer(name).then((c) => {
        contractRule(c.customerId, p.productId, CONTRACT)

        cy.openSellSection('sellDiv')
        pickProduct(p.productId)                                    // no customer yet → catalog price
        cy.get('#sellSellRate').should('have.value', String(CATALOG))
        cy.get('#addInviceItem').click()

        selectCustomer(c.customerId)                                // now the buyer is known

        cy.window().should((w) => {
          const line = w.data.find((d) => String(d.productId) === String(p.productId))
          expect(line, 'the line is still in the cart').to.exist
          expect(Number(line.sellRate), 'and has been re-priced').to.be.closeTo(CONTRACT, 0.01)
        })
      })
    })
  })

  it("a cashier's typed override still wins — the till is not taken away from them", () => {
    const name = 'CP_Ovr_' + uniq()
    const TYPED = 175
    cy.seedProduct({ name: 'CPOvr_' + uniq(), sellingPrice: CATALOG, stock: 20 }).then((p) => {
      customer(name).then((c) => {
        contractRule(c.customerId, p.productId, CONTRACT)

        cy.openSellSection('sellDiv')
        selectCustomer(c.customerId)
        pickProduct(p.productId)
        cy.get('#sellSellRate').should('have.value', String(CONTRACT))

        cy.get('#sellSellRate').clear().type(String(TYPED)).blur()
        cy.get('#addInviceItem').click()

        cy.window().then((w) => {
          const line = w.data.find((d) => String(d.productId) === String(p.productId))
          expect(Number(line.sellRate), 'the typed rate survived').to.be.closeTo(TYPED, 0.01)
          expect(line.autoRate, 'and is marked NOT re-priceable').to.eq(null)
        })

        // re-selecting the customer must not quietly undo the override
        selectCustomer(c.customerId)
        cy.wait(500)
        cy.window().then((w) => {
          const line = w.data.find((d) => String(d.productId) === String(p.productId))
          expect(Number(line.sellRate), 'still the cashier\'s price').to.be.closeTo(TYPED, 0.01)
        })
      })
    })
  })

  it('a walk-in with no account is untouched — catalog price, exactly as before', () => {
    cy.seedProduct({ name: 'CPWalk_' + uniq(), sellingPrice: CATALOG, stock: 20 }).then((p) => {
      cy.openSellSection('sellDiv')
      pickProduct(p.productId)
      cy.get('#sellSellRate').should('have.value', String(CATALOG))
      cy.get('#sellPriceReason').should('not.be.visible')
    })
  })

  it('the quote endpoint answers only for the caller and never trusts a submitted price', () => {
    const name = 'CP_Api_' + uniq()
    cy.seedProduct({ name: 'CPApi_' + uniq(), sellingPrice: CATALOG, stock: 5 }).then((p) => {
      customer(name).then((c) => {
        contractRule(c.customerId, p.productId, CONTRACT)
        cy.request({
          method: 'POST', url: '/priceQuote', headers: { 'Content-Type': 'application/json' },
          failOnStatusCode: false,
          body: {
            customerId: c.customerId, customerType: 'RETAILER',
            // A hostile client claims a price. It must be ignored — the answer comes from the rules.
            lines: [{ productId: p.productId, quantity: 1, unitPrice: 1 }],
          },
        }).then((r) => {
          expect(r.status).to.eq(200)
          const line = (r.body.lines || [])[0]
          expect(line, 'a line came back').to.exist
          expect(Number(line.unitPrice), 'the RULE price, not the claimed 1').to.be.closeTo(CONTRACT, 0.01)
          expect(line.ruleId, 'and it names the rule that set it').to.exist
        })
      })
    })
  })
})
