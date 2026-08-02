/**
 * B2B Phase 2 — contract & tiered pricing (= OMS B1, customer requirement #10).
 *
 * Today a product has ONE price, so charging a trade customer their agreed rate means the cashier types it
 * in from memory — unauditable, and nothing records WHY they paid 92. This slice resolves a price from the
 * tenant's rules and carries the REASON with it.
 *
 * Two invariants the suite exists to protect:
 *   1. a tenant with NO rules prices exactly as it does today (every live shop)
 *   2. rules NEVER stack — the most specific live rule wins, alone
 *
 * Design: microservices/docs/slices/b2b-P2-pricing.md
 *
 * Run headed:
 *   npx cypress run --spec cypress/e2e/business/pricing.cy.js --headed --no-exit --config screenshotOnRunFailure=false
 */
const uniq = () => `${Date.now()}${Math.floor(Math.random() * 1000)}`

const list = (body) => {
  for (const key of ['collection', 'data', 'object']) {
    if (Array.isArray(body && body[key])) return body[key]
  }
  return Array.isArray(body) ? body : []
}

const rules = () => cy.request('/priceRules').then((r) => {
  const body = typeof r.body === 'string' ? JSON.parse(r.body || '[]') : r.body
  return cy.wrap(list(body))
})

const saveRule = (rule) =>
  cy.request({ method: 'POST', url: '/savePriceRule', headers: { 'Content-Type': 'application/json' },
    body: rule, failOnStatusCode: false })
    .then((r) => {
      expect(r.status, `savePriceRule ${JSON.stringify(rule)} -> ${JSON.stringify(r.body)}`).to.eq(200)
      expect(r.body && r.body.id, 'the saved rule comes back with an id').to.exist
      return cy.wrap(r.body)
    })

const deleteRule = (id) =>
  cy.request({ method: 'POST', url: '/deletePriceRule', headers: { 'Content-Type': 'application/json' },
    body: { id }, failOnStatusCode: false })

/** Remove every rule this spec created, so a re-run starts clean and other specs are unaffected. */
const cleanRules = () => rules().then((all) => all.forEach((r) => deleteRule(r.id)))

const customer = (type) => {
  const name = 'PR_' + type + '_' + uniq()
  return cy.request({ method: 'POST', url: '/addCustomer', form: true, failOnStatusCode: false,
    body: { name, contact: 'C' + uniq(), customerType: type } })
    .then((r) => {
      expect(r.body.status, `addCustomer: ${JSON.stringify(r.body)}`).to.eq('SUCCESS')
      return cy.request('/getUserCustomer')
    })
    .then((r) => {
      const c = list(r.body).find((x) => x.name === name)
      expect(c, 'customer created').to.exist
      return cy.wrap(c)
    })
}

/** Sell one unit WITHOUT sending a rate, so the server's resolved price is what lands on the invoice. */
const sellOne = (cust, productId) =>
  cy.request({ method: 'POST', url: '/addSell', headers: { 'Content-Type': 'application/json' },
    failOnStatusCode: false,
    body: {
      customer: { customerId: cust.customerId, name: cust.name, contact: cust.contact,
                  customerType: cust.customerType },
      sales: [{ productId, quantity: 1 }],
      tenders: [], paidAmount: 0,
      idempotencyKey: 'cy-price-' + uniq(),
    } })

const receiptOf = (invoiceNo) =>
  cy.request('/getReceipt?invoiceNo=' + encodeURIComponent(invoiceNo))
    .then((r) => r.body.object || r.body.data)

describe('B2B P2 — contract & tiered pricing (#10)', () => {

  beforeEach(() => { cy.loginAsOwner() })
  after(() => { cy.loginAsOwner(); cleanRules() })

  // ── the regression guard ─────────────────────────────────────────────────────

  it('with NO rules, a sale prices exactly as it does today', () => {
    // Every live tenant is in this state. If this fails, the slice is a regression.
    cleanRules()
    cy.seedProduct({ name: 'NoRule_' + uniq(), sellingPrice: 100, stock: 10 }).then(({ productId }) => {
      customer('WHOLESALE').then((c) => {
        sellOne(c, productId).then((s) => {
          expect(s.body.status, JSON.stringify(s.body)).to.eq('SUCCESS')
          receiptOf(s.body.object).then((inv) => {
            const line = (inv.sales || [])[0]
            expect(Number(line.sellRate), 'catalog price, untouched').to.be.closeTo(100, 0.01)
          })
        })
      })
    })
  })

  // ── tier pricing ─────────────────────────────────────────────────────────────

  it('a WHOLESALE tier rule discounts a wholesale customer but NOT a walk-in', () => {
    cleanRules()
    cy.seedProduct({ name: 'Tier_' + uniq(), sellingPrice: 100, stock: 20 }).then(({ productId }) => {
      saveRule({ scope: 'TYPE', customerType: 'WHOLESALE', target: 'PRODUCT', productId,
                 mode: 'PERCENT', value: 12 })

      customer('WHOLESALE').then((w) => {
        sellOne(w, productId).then((s) => {
          expect(s.body.status, JSON.stringify(s.body)).to.eq('SUCCESS')
          receiptOf(s.body.object).then((inv) => {
            expect(Number((inv.sales || [])[0].sellRate), '12% off 100').to.be.closeTo(88, 0.01)
          })
        })
      })

      customer('WALK_IN').then((w) => {
        sellOne(w, productId).then((s) => {
          receiptOf(s.body.object).then((inv) => {
            expect(Number((inv.sales || [])[0].sellRate), 'a walk-in pays catalog').to.be.closeTo(100, 0.01)
          })
        })
      })
    })
  })

  it('a customer contract price beats the tier rule for that customer', () => {
    cleanRules()
    cy.seedProduct({ name: 'Contract_' + uniq(), sellingPrice: 100, stock: 20 }).then(({ productId }) => {
      saveRule({ scope: 'TYPE', customerType: 'WHOLESALE', target: 'PRODUCT', productId,
                 mode: 'PERCENT', value: 12 })
      customer('WHOLESALE').then((c) => {
        saveRule({ scope: 'CUSTOMER', customerId: c.customerId, target: 'PRODUCT', productId,
                   mode: 'FIXED', value: 92 })
        sellOne(c, productId).then((s) => {
          receiptOf(s.body.object).then((inv) => {
            expect(Number((inv.sales || [])[0].sellRate),
              'the contract wins — 92, not the tier 88 and not catalog 100').to.be.closeTo(92, 0.01)
          })
        })
      })
    })
  })

  it('rules NEVER stack — two applicable rules yield ONE price', () => {
    cleanRules()
    cy.seedProduct({ name: 'Stack_' + uniq(), sellingPrice: 100, stock: 20 }).then(({ productId }) => {
      customer('WHOLESALE').then((c) => {
        saveRule({ scope: 'TYPE', customerType: 'WHOLESALE', target: 'PRODUCT', productId,
                   mode: 'PERCENT', value: 10 })
        saveRule({ scope: 'CUSTOMER', customerId: c.customerId, target: 'PRODUCT', productId,
                   mode: 'PERCENT', value: 5 })
        sellOne(c, productId).then((s) => {
          receiptOf(s.body.object).then((inv) => {
            const rate = Number((inv.sales || [])[0].sellRate)
            expect(rate, 'the customer rule alone: 5% off 100').to.be.closeTo(95, 0.01)
            expect(rate, 'NOT compounded to 85.50').to.not.be.closeTo(85.5, 0.01)
          })
        })
      })
    })
  })

  // ── dates ────────────────────────────────────────────────────────────────────

  it('an expired rule falls back to catalog, with no error', () => {
    cleanRules()
    cy.seedProduct({ name: 'Expired_' + uniq(), sellingPrice: 100, stock: 10 }).then(({ productId }) => {
      saveRule({ scope: 'TYPE', customerType: 'WHOLESALE', target: 'PRODUCT', productId,
                 mode: 'PERCENT', value: 30, startsOn: '2020-01-01', endsOn: '2020-12-31' })
      customer('WHOLESALE').then((c) => {
        sellOne(c, productId).then((s) => {
          expect(s.body.status, 'an expired rule is not an error').to.eq('SUCCESS')
          receiptOf(s.body.object).then((inv) => {
            expect(Number((inv.sales || [])[0].sellRate)).to.be.closeTo(100, 0.01)
          })
        })
      })
    })
  })

  // ── validation ───────────────────────────────────────────────────────────────

  describe('a rule that could never fire is refused at creation', () => {
    it('rejects a >100% discount, a missing target, and a backwards date range', () => {
      const bad = (rule, why) =>
        cy.request({ method: 'POST', url: '/savePriceRule', headers: { 'Content-Type': 'application/json' },
          body: rule, failOnStatusCode: false })
          .then((r) => {
            const ok = r.status === 200 && r.body && r.body.id
            expect(Boolean(ok), `${why}: ${JSON.stringify(r.body)}`).to.eq(false)
          })

      bad({ scope: 'TYPE', customerType: 'WHOLESALE', target: 'PRODUCT', productId: 1,
            mode: 'PERCENT', value: 150 }, 'a 150% discount would be a negative price')
      bad({ scope: 'TYPE', customerType: 'WHOLESALE', target: 'PRODUCT',
            mode: 'PERCENT', value: 10 }, 'PRODUCT target with no productId can never match')
      bad({ scope: 'CUSTOMER', target: 'PRODUCT', productId: 1,
            mode: 'FIXED', value: 5 }, 'CUSTOMER scope with no customerId can never match')
    })
  })

  // ── tenancy ──────────────────────────────────────────────────────────────────

  it('another tenant cannot see or use this org\'s negotiated rates', () => {
    cleanRules()
    cy.seedProduct({ name: 'Tenant_' + uniq(), sellingPrice: 100, stock: 10 }).then(({ productId }) => {
      saveRule({ scope: 'TYPE', customerType: 'WHOLESALE', target: 'PRODUCT', productId,
                 mode: 'PERCENT', value: 40 }).then((mine) => {
        // A different tenant must not see the rule at all — a competitor's contract rates are commercially
        // sensitive, so this is a disclosure test, not only an authorisation one.
        cy.loginAs('demo.education@myplus.com', 'Demo@2025!', '/educationDashboard')
        cy.request({ url: '/priceRules', failOnStatusCode: false }).then((r) => {
          const body = typeof r.body === 'string' ? JSON.parse(r.body || '[]') : r.body
          const leaked = list(body).find((x) => x.id === mine.id)
          expect(leaked, 'another tenant cannot see this rule').to.not.exist
        })
      })
    })
  })

  // ── the reason ───────────────────────────────────────────────────────────────

  it('a rule-priced line records WHY, and the margin warning names it', () => {
    cleanRules()
    // Priced below cost on purpose: the P0 margin guard must fire AND say a rule was involved, or the
    // shopkeeper hunts for a cashier error that does not exist.
    cy.request({ method: 'POST', url: '/saveBusinessConfig', form: true,
      body: { key: 'pos.sale.marginPolicy', value: 'warn' }, failOnStatusCode: false })
    cy.request({ method: 'POST', url: '/saveBusinessConfig', form: true,
      body: { key: 'pos.sale.creditLimitPolicy', value: 'off' }, failOnStatusCode: false })

    cy.seedProduct({ name: 'Reason_' + uniq(), sellingPrice: 100, stock: 20 }).then(({ productId }) => {
      cy.request({ method: 'POST', url: '/addPurchase', form: true, failOnStatusCode: false,
        body: { productId, quantity: 10, purchaseRate: 90, 'stock.bpurchaseRate': 90,
                'stock.bsellRate': 100, totalAmount: 900, netAmount: 900, paidAmount: 900,
                purchaseInvoiceNo: 'PR-' + uniq() } })
        .then((p) => expect(p.body.status, JSON.stringify(p.body)).to.eq('SUCCESS'))

      saveRule({ scope: 'TYPE', customerType: 'WHOLESALE', target: 'PRODUCT', productId,
                 mode: 'PERCENT', value: 30 })   // 70 against a cost of 90
      customer('WHOLESALE').then((c) => {
        sellOne(c, productId).then((s) => {
          expect(s.body.status, JSON.stringify(s.body)).to.eq('SUCCESS')
          expect(String(s.body.message), 'the margin warning still fires').to.match(/no profit/i)
          expect(String(s.body.message), 'and names the rule, so nobody hunts a phantom typo')
            .to.match(/price rule applied/i)
        })
      })
    })
  })
})
