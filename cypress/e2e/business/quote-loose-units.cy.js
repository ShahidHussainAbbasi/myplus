/**
 * U14 — a sales quote can be made in loose units, and an accepted quote converts to exactly what was accepted.
 *
 * Design: microservices/docs/slices/u14-loose-units-in-quotes.md
 *
 * Why: 122 of 218 dev quotes belong to PHARMA tenants, who could only quote "0.25 of a box at 311.60" — the figure U13
 * removed from the Sale Return screen. And the user's ruling (2026-09-17): if the loose markup changes between the
 * customer accepting a quote and it being converted, the INVOICE charges the accepted total.
 *
 * ⚠ WRITTEN UNDER A BUILD FREEZE AND NOT YET RUN. It needs business-service (V66 + the service) and the monolith (the
 * quote form and document) rebuilt, and it must be run once BEFORE that rebuild to prove it fails.
 *
 * ⚠ SERVER-WIDE STATE: case 3 changes pos.sale.looseMarkupPct. It snapshots the value first and restores it in
 * after(), because Cypress abandons the rest of a failing test — a trailing restore would never run on the run that
 * matters (the permission-sets lesson, 2026-09-17).
 *
 * Run headed, solo:
 *   npx cypress run --headed --browser chrome --spec cypress/e2e/business/quote-loose-units.cy.js
 */

const uniq = () => `${Date.now()}${Math.floor(Math.random() * 1000)}`

const PACK = 40
const PRICE = 311.60

const packProduct = (name, allowLoose = true) =>
  cy.request({
    method: 'POST', url: '/addProduct', headers: { 'Content-Type': 'application/json' }, failOnStatusCode: false,
    body: {
      name, sku: `U14${uniq()}`, sellingPrice: PRICE, taxRate: 0, unit: 'box',
      packSize: PACK, looseUnit: 'tablet', looseUnitPlural: 'tablets', allowLoose, defaultSellUnit: 'PACK',
    },
  }).then((r) => {
    expect(r.body && r.body.success, JSON.stringify(r.body).slice(0, 200)).to.eq(true)
    return r.body.data.id
  })

const stockIn = (productId, boxes) =>
  cy.request({
    method: 'POST', url: '/addPurchase', form: true, failOnStatusCode: false,
    body: { productId, quantity: boxes, 'stock.batchNo': `U14B${uniq()}`,
      'stock.bpurchaseRate': 100, 'stock.bsellRate': PRICE,
      totalAmount: boxes * 100, netAmount: boxes * 100, purchaseInvoiceNo: `U14-${uniq()}` },
  }).then((r) => expect(r.body.status, `stock in: ${JSON.stringify(r.body).slice(0, 200)}`).to.eq('SUCCESS'))

/**
 * A FRESH trade customer with a credit limit — the pattern b2b-quote-to-order.cy.js proved. Conversion puts the sale
 * on account, so an arbitrary existing customer could be refused by credit policy and fail this spec for a reason that
 * has nothing to do with loose units (existence is not eligibility).
 */
const customer = () => {
  const name = `U14Cust_${uniq()}`
  return cy.request({
    method: 'POST', url: '/addCustomer', form: true, failOnStatusCode: false,
    body: { name, contact: `03${Math.floor(Math.random() * 100000000)}`, customerType: 'WHOLESALE', creditLimit: 100000 },
  }).then((r) => {
    expect(r.body.status, `addCustomer: ${JSON.stringify(r.body).slice(0, 200)}`).to.eq('SUCCESS')
    return cy.request('/getUserCustomer').then((list) => {
      const mine = (list.body.collection || []).find((c) => c.name === name)
      expect(mine, `customer ${name} readable back`).to.exist
      return cy.wrap(mine.customerId)
    })
  })
}

const addQuote = (customerId, lines) =>
  cy.request({
    method: 'POST', url: '/addQuote', headers: { 'Content-Type': 'application/json' }, failOnStatusCode: false,
    body: { customerId, lines },
  })

/** Quote lifecycle actions — form-posted with the quote id, as the monolith relays them. */
const act = (url, id) =>
  cy.request({ method: 'POST', url: `/${url}`, form: true, failOnStatusCode: false, body: { id } })

/** The shop's loose markup — /saveBusinessConfig, as sell-loose-return.cy.js sets it. */
const setMarkup = (value) =>
  cy.request({ method: 'POST', url: '/saveBusinessConfig', form: true, failOnStatusCode: false,
    body: { key: 'pos.sale.looseMarkupPct', value } })
    .then((r) => expect(r.body && r.body.success, `saveBusinessConfig markup=${value}`).to.eq(true))

describe('U14 — loose units in a sales quote', () => {
  let markupBefore = null

  before(() => {
    cy.loginAsBusiness()
    // /getBusinessConfig answers {data:[{key,value,isDefault}]}. Snapshot the markup so after() restores it exactly.
    cy.request({ url: '/getBusinessConfig', failOnStatusCode: false }).then((r) => {
      const rows = (r.body && r.body.data) || []
      const row = rows.find((x) => x.key === 'pos.sale.looseMarkupPct')
      markupBefore = row ? row.value : null
    })
  })

  after(() => {
    cy.loginAsBusiness()
    setMarkup(markupBefore == null ? '0' : String(markupBefore))
  })

  beforeEach(() => cy.loginAsBusiness())

  it('⭐⭐ 1 — 10 tablets are quoted as 10 tablets at 77.90, 0.25 of a box underneath', () => {
    setMarkup('0')
    packProduct(`U14 quote ${uniq()}`).then((productId) => {
      customer().then((customerId) => {
        addQuote(customerId, [{ productId, productName: 'U14', soldUnit: 'LOOSE', soldQuantity: 10, unitPrice: PRICE }])
          .then((r) => {
            expect(r.body.status, `the quote: ${JSON.stringify(r.body).slice(0, 250)}`).to.eq('SUCCESS')
            const line = r.body.object.lines[0]
            expect(line.soldUnit).to.eq('LOOSE')
            expect(Number(line.soldQuantity), 'ten tablets, as offered').to.eq(10)
            expect(Number(line.soldRate), 'per tablet').to.eq(7.79)
            expect(Number(line.quantity), 'a quarter of a box on the shelf').to.be.closeTo(0.25, 0.0001)
            expect(Number(line.lineTotal), 'the same total the till would charge').to.eq(77.90)
          })
      })
    })
  })

  it('⭐ 2 — a product that may not be split is refused WITH its reason', () => {
    packProduct(`U14 sealed ${uniq()}`, false).then((productId) => {
      customer().then((customerId) => {
        addQuote(customerId, [{ productId, productName: 'U14', soldUnit: 'LOOSE', soldQuantity: 10, unitPrice: PRICE }])
          .then((r) => {
            expect(r.body.status).to.eq('FAILED')
            expect(String(r.body.message || ''), 'not the bare "Could not create the quote."')
              .to.match(/not sold by the piece/i)
          })
      })
    })
  })

  it('⭐⭐ 3 — THE RULING: markup raised after acceptance, the invoice still charges the accepted 77.90', () => {
    setMarkup('0')
    packProduct(`U14 bind ${uniq()}`).then((productId) => {
      stockIn(productId, 2)
      customer().then((customerId) => {
        addQuote(customerId, [{ productId, productName: 'U14', soldUnit: 'LOOSE', soldQuantity: 10, unitPrice: PRICE }])
          .then((r) => {
            expect(r.body.status, JSON.stringify(r.body).slice(0, 200)).to.eq('SUCCESS')
            const quoteId = r.body.object.id

            act('sendQuote', quoteId).then((s) => expect(s.body.status, JSON.stringify(s.body)).to.eq('SUCCESS'))
            act('acceptQuote', quoteId).then((a) => expect(a.body.status, JSON.stringify(a.body)).to.eq('SUCCESS'))

            // After acceptance the owner raises the loose markup to 25%.
            setMarkup('25')

            act('convertQuote', quoteId)
              .then((c) => {
                expect(c.body.status, `conversion: ${JSON.stringify(c.body).slice(0, 250)}`).to.eq('SUCCESS')
                cy.request('/getUserSell?q=-1').then((s) => {
                  const rows = (s.body && (s.body.collection || s.body.data)) || []
                  const inv = rows.filter((x) => String(x.productId) === String(productId))[0] || null
                  expect(inv, 'the converted invoice line exists').to.not.be.null
                  expect(Number(inv.soldQuantity), 'ten tablets invoiced').to.eq(10)
                  expect(Number(inv.netAmount),
                    'the ACCEPTED total — today\'s 25% markup would have charged more').to.eq(77.90)
                })
              })
          })
      })
    })
  })
})
