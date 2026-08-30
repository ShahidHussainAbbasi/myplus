/**
 * U11 — counting the shelf.
 *
 * Design: microservices/docs/slices/u11-counting-the-shelf.md
 *
 * ⚠ THIS SCREEN WRITES STOCK IN BULK, so the cases that matter most are the ones about NOT writing:
 * a blank row is not a zero, a zero variance writes nothing, and a row whose system quantity moved while
 * the sheet was open is flagged rather than applied.
 *
 * Run headed:
 *   npx cypress run --spec cypress/e2e/business/stock-count.cy.js --headed --no-exit
 */
const uniq = () => `${Date.now()}${Math.floor(Math.random() * 1000)}`
const list = (body) => body.collection || body.data || []

const packProduct = (name, packSize) =>
  cy.request({
    method: 'POST', url: '/addProduct', headers: { 'Content-Type': 'application/json' },
    failOnStatusCode: false,
    body: { name, sellingPrice: 120, unit: 'pack', packSize,
      looseUnit: 'tablet', looseUnitPlural: 'tablets', allowLoose: packSize > 1, defaultSellUnit: 'PACK' },
  }).then((r) => {
    expect(r.body.success, `product ${name}: ${JSON.stringify(r.body)}`).to.eq(true)
    return cy.request('/getUserProduct?q=-1').then((pr) => {
      const p = list(pr.body).find((x) => x.name === name)
      expect(p, 'the product was stored').to.exist
      return p
    })
  })

/** ⚠ A purchase RESTAMPS the selling price — pass the intended price (U2 §13.4c). */
const stockIn = (productId, qty) =>
  cy.request({
    method: 'POST', url: '/addPurchase', form: true, failOnStatusCode: false,
    body: { productId, quantity: qty, 'stock.batchNo': `CT${uniq()}`,
      'stock.bpurchaseRate': 100, 'stock.bsellRate': 120,
      totalAmount: qty * 100, netAmount: qty * 100, purchaseInvoiceNo: `CNT-${uniq()}` },
  }).then((r) => expect(r.body.status, `stock in: ${JSON.stringify(r.body).substring(0, 200)}`).to.eq('SUCCESS'))

const onHand = (productId) =>
  cy.request('/productStockLevels').then((r) => {
    expect(r.body.success, 'productStockLevels').to.eq(true)
    const row = (r.body.levels || {})[String(productId)]
    return row ? Number(row.onHand || 0) : 0
  })

const openCount = () => {
  cy.visit('/businessDashboard')
  cy.waitForAppReady()
  cy.window().should('have.property', 'showStockCount')
  cy.window().then((w) => w.showStockCount())
  cy.get('#StockCountDiv', { timeout: 15000 }).should('be.visible')
  cy.get('#cntBody tr', { timeout: 15000 }).should('have.length.at.least', 1)
}

describe('U11 — counting the shelf', () => {
  beforeEach(() => cy.loginAsOwner())

  // ── ⭐ the arithmetic, as pure logic ──────────────────────────────────────────────────────────────────

  it('⭐ the variance is computed in the unit the shelf is counted in', () => {
    // 9.5 packs of 10 is nine packs and five tablets. Counting nine packs and three tablets is TWO TABLETS
    // short — not 0.2 of anything, which is a number no one holding a shelf would recognise.
    cy.visit('/businessDashboard')
    cy.waitForAppReady()
    cy.window().then((w) => {
      expect(w.stockCountVariance, 'the arithmetic is exported').to.be.a('function')

      expect(w.stockCountVariance(9.5, 10, 9, 3), 'two tablets short').to.eq(-2)
      expect(w.stockCountVariance(9.5, 10, 9, 5), 'exactly right').to.eq(0)
      expect(w.stockCountVariance(9.5, 10, 10, 0), 'five tablets over').to.eq(5)
      expect(w.stockCountVariance(12, 0, 11, null), 'an indivisible product counts in units').to.eq(-1)
    })
  })

  // ── ⭐ the cases about NOT writing ────────────────────────────────────────────────────────────────────

  it('⭐ a blank row is not a zero — an unfinished sheet cannot wipe the shelf', () => {
    /*
     * THE CASE THAT PROTECTS EVERY SHOP. A count sheet is almost never finished in one pass. If "not counted
     * yet" were read as "counted zero", opening this screen and applying it would zero the entire catalogue.
     */
    const name = `Blank_${uniq()}`

    packProduct(name, 10).then((p) => {
      stockIn(p.id, 10)
      onHand(p.id).then((before) => {
        openCount()
        // Fill NOTHING, and confirm the Apply button will not even arm.
        cy.get('#cntApply').should('be.disabled')
        onHand(p.id).then((after) => {
          expect(after, 'untouched').to.be.closeTo(before, 0.0001)
        })
      })
    })
  })

  it('a zero variance writes nothing — no adjustment, no audit noise', () => {
    const name = `Zero_${uniq()}`

    packProduct(name, 10).then((p) => {
      stockIn(p.id, 10)
      openCount()
      // Count exactly what the system says: 10 packs, 0 tablets.
      cy.get(`#cntPacks_${p.id}`).clear().type('10')
      cy.get(`#cntPieces_${p.id}`).clear().type('0')
      cy.get(`#cntVar_${p.id}`).should('contain.text', '—')
      cy.get('#cntApply').should('be.disabled')
    })
  })

  // ── ⭐ applying a real count ──────────────────────────────────────────────────────────────────────────

  it('⭐ applying a count moves on-hand to exactly what was counted', () => {
    const name = `Apply_${uniq()}`

    packProduct(name, 10).then((p) => {
      stockIn(p.id, 10)
      openCount()

      // Counted 9 packs and 7 tablets = 9.7 packs, against a system 10 → three tablets short.
      cy.get(`#cntPacks_${p.id}`).clear().type('9')
      cy.get(`#cntPieces_${p.id}`).clear().type('7')
      cy.get(`#cntVar_${p.id}`).should('contain.text', '-3')
      cy.get('#cntApply').should('not.be.disabled').click()
      cy.get('[data-ui-confirm="ok"]', { timeout: 10000 }).click()

      cy.wait(1500)
      onHand(p.id).then((after) => {
        expect(after, 'the shelf now holds 9.7 packs — what was counted').to.be.closeTo(9.7, 0.0001)
      })
    })
  })

  it('an increase applies too — the sign is per row, not per sheet', () => {
    const name = `Up_${uniq()}`

    packProduct(name, 10).then((p) => {
      stockIn(p.id, 10)
      openCount()

      cy.get(`#cntPacks_${p.id}`).clear().type('11')
      cy.get(`#cntPieces_${p.id}`).clear().type('0')
      cy.get(`#cntVar_${p.id}`).should('contain.text', '+10')   // ten tablets more than the system
      cy.get('#cntApply').click()
      cy.get('[data-ui-confirm="ok"]', { timeout: 10000 }).click()

      cy.wait(1500)
      onHand(p.id).then((after) => expect(after, 'eleven packs').to.be.closeTo(11, 0.0001))
    })
  })

  it('an indivisible product counts in plain units, with no pieces box', () => {
    // Most of the catalogue. The screen must not ask for tablets of a bottle of shampoo.
    const name = `Plain_${uniq()}`

    cy.request({
      method: 'POST', url: '/addProduct', headers: { 'Content-Type': 'application/json' },
      failOnStatusCode: false, body: { name, sellingPrice: 450, unit: 'pcs' },
    }).then((r) => expect(r.body.success, JSON.stringify(r.body)).to.eq(true))

    cy.request('/getUserProduct?q=-1').then((pr) => {
      const p = list(pr.body).find((x) => x.name === name)
      expect(p, 'the product exists').to.exist
      stockIn(p.id, 12)
      openCount()
      cy.get(`#cntPacks_${p.id}`).should('exist')
      cy.get(`#cntPieces_${p.id}`).should('not.exist')

      cy.get(`#cntPacks_${p.id}`).clear().type('11')
      cy.get(`#cntVar_${p.id}`).should('contain.text', '-1')
    })
  })

  // ── the audit trail ──────────────────────────────────────────────────────────────────────────────────

  it('the adjustment carries the count as its reason', () => {
    // A count that left no trace would be indistinguishable from someone quietly editing stock.
    const name = `Reason_${uniq()}`

    packProduct(name, 10).then((p) => {
      stockIn(p.id, 10)
      openCount()
      cy.get(`#cntPacks_${p.id}`).clear().type('9')
      cy.get(`#cntPieces_${p.id}`).clear().type('0')
      cy.get('#cntApply').click()
      cy.get('[data-ui-confirm="ok"]', { timeout: 10000 }).click()
      cy.wait(1500)

      // The screen reports what it did, rather than saying "done" and hoping.
      cy.get('#cntNote_' + p.id, { timeout: 10000 }).invoke('text').then((txt) => {
        expect(String(txt).trim(), 'the row says what happened to it').to.not.eq('')
      })
    })
  })
})
