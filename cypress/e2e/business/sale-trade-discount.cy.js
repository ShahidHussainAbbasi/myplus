/**
 * TRADE-DISC-1 — the till charges what the invoice charges.
 *
 * THE DEFECT (INV-000050, reported with its printed receipt): 72.00 of goods, a 2.00 trade discount, Received
 * 72.00. The till showed Total 72 and Change 0; the server netted the discount (grand 70.00) and settled
 * change = tendered − grand, so the receipt printed "Change 2.00" — the discount, reported as change.
 * And the field was never cleared, so the NEXT customer silently inherited the discount.
 *
 * Every case asserts what the defect breaks, on the surface the cashier and the customer see:
 *   0 control   — no discount: nothing new on screen, and the payload carries NO tradeDiscount key
 *   1 screen    — the payable line, Due, Change and F8 all use goods − discount
 *   2 invoice   — tender the payable: stored change 0, grand = payable; the field is empty afterwards
 *   3 park      — the discount leaves with the parked basket and comes back on resume
 *   4 edit      — the box shows the invoice's own discount; clearing it removes it from the invoice
 *   5 cancel/clear — cancel-edit and Clear cart both empty the field
 *
 * Run headed:
 *   npx cypress run --spec cypress/e2e/business/sale-trade-discount.cy.js --headed --browser chrome
 */
const uniq = () => `${Date.now()}`.slice(-8) + Math.floor(Math.random() * 900 + 100)
const list = (b) => (b && (b.collection || b.object || b.data)) || []

const setConfig = (key, value) =>
  cy.request({ method: 'POST', url: '/saveBusinessConfig', form: true, body: { key, value },
    failOnStatusCode: false })
    .then((r) => expect(r.body && r.body.success, `saveBusinessConfig ${key}=${value}`).to.eq(true))

const overlayGone = () => {
  cy.get('body').then(($b) => {
    if ($b.find('#appAjaxOverlay').length) {
      cy.get('#appAjaxOverlay', { timeout: 30000 }).should('not.be.visible')
    }
  })
}

/**
 * Open the till and put `qty` of one product in the cart through the real Add-to-Cart button.
 *
 * ⚠ TWO WAITS, both found by a probe after case 0 went red / green / red on the same code:
 *  - picking a product starts loadStock(), whose ASYNC answer writes the default quantity into an EMPTY box
 *    (business.js ~3217). Clearing and typing before it lands gave "1" + "1" = 11 → a 1,100 bill, a balance,
 *    and the till (correctly) refused the sale before any dialog. So: wait for loadStock to fill the box.
 *  - the confirm dialog only appears once the tenant settings have loaded (window.posConfirmOnComplete); a
 *    press before that posts with NO dialog. So: wait for the flag before any case presses Complete.
 */
const ringUp = (productId, qty = 1, price = 100) => {
  cy.visit('/businessDashboard')
  cy.waitForAppReady()
  cy.window().its('posConfirmOnComplete', { timeout: 15000 }).should('eq', true)
  cy.get('#sellType').select('sellDiv', { force: true })
  cy.get('#sellItemDD', { timeout: 15000 }).select(String(productId), { force: true })
  cy.get('#sellQuantity', { timeout: 15000 }).should(($q) => expect($q.val(), 'loadStock has answered').not.to.eq(''))
  cy.get('#sellQuantity').clear().type(String(qty)).should('have.value', String(qty))
  cy.get('#addInviceItem').click({ force: true })   // sic: the app's id carries the typo
  overlayGone()
  cy.window().its('data').should('have.length', 1)
  cy.get('#sellTotal').should(($t) => expect(Number($t.text()), 'cart total').to.eq(price * qty))
  cy.get('#sellPayMethod').select('CASH', { force: true })
}

/** The invoice really exists and sold THIS product — a SUCCESS status alone once passed with no row written. */
const assertInvoiceSold = (invoiceNo, productId) =>
  receiptOf(invoiceNo).then((inv) => {
    const ids = (inv.sales || []).map((s) => Number(s.productId))
    expect(ids, `${invoiceNo} sold product ${productId}`).to.include(Number(productId))
    return inv
  })

const typeDiscount = (v) => cy.get('#sellTradeDiscount').clear({ force: true }).type(String(v), { force: true })

/** Complete the sale through the real button + confirm dialog; yields the addSell interception. */
const completeSale = () => {
  cy.intercept('POST', '**/addSell').as('sale')
  cy.get('#addSell').click({ force: true })
  cy.confirmSale()
  return cy.wait('@sale', { timeout: 20000 }).then((i) => {
    expect(i.response.body.status, JSON.stringify(i.response.body)).to.eq('SUCCESS')
    return i
  })
}

/** The invoice exactly as the printer receives it. */
const receiptOf = (invoiceNo) =>
  cy.request(`/getReceipt?invoiceNo=${encodeURIComponent(invoiceNo)}`).then((r) => {
    expect(r.body && r.body.status, `getReceipt: ${JSON.stringify(r.body).slice(0, 200)}`).to.eq('SUCCESS')
    return r.body.object
  })

/** /getUserSell is FLAT — each row is a line carrying its customerHistory. */
const lineIdOf = (invoiceNo) =>
  cy.request('/getUserSell').then((r) => {
    const rows = list(r.body).filter((row) => (row.customerHistory || {}).invoiceNo === invoiceNo)
    expect(rows.length, `invoice ${invoiceNo} has lines`).to.be.greaterThan(0)
    return rows[0].sellId || rows[0].sell_id || rows[0].id
  })

describe('TRADE-DISC-1 — the till charges what the invoice charges', () => {
  before(() => {
    cy.loginAsOwner()
    setConfig('pos.sale.confirmOnComplete', 'true')        // pinned: cy.confirmSale() answers it
    setConfig('pos.invoice.tradeDiscountEnabled', 'true')  // the field under test must be on screen
  })
  beforeEach(() => cy.loginAsOwner())
  after(() => {
    // Both are the defaults — leave the till exactly as every other spec expects it.
    cy.loginAsOwner()
    setConfig('pos.sale.confirmOnComplete', 'true')
    setConfig('pos.invoice.tradeDiscountEnabled', 'true')
  })

  it('0 — control: no discount shows nothing new and sends no tradeDiscount', () => {
    cy.seedProduct({ name: `TD0_${uniq()}`, sellingPrice: 100, stock: 5 }).then(({ productId }) => {
      ringUp(productId)
      cy.get('#sellPayableRow').should('not.be.visible')
      cy.get('#sellRec').clear().type('100')
      completeSale().then((i) => {
        expect(i.request.body, 'a sale without a trade discount is byte-identical: no key at all')
          .not.to.have.property('tradeDiscount')
        assertInvoiceSold(i.response.body.object, productId).then((inv) => {
          expect(inv.tradeDiscount == null, 'no discount stored').to.eq(true)
          expect(Number(inv.grandTotal)).to.eq(100)
        })
      })
    })
  })

  it('⭐ 1 — the payable, Due, Change and F8 all take the trade discount off', () => {
    cy.seedProduct({ name: `TD1_${uniq()}`, sellingPrice: 100, stock: 5 }).then(({ productId }) => {
      ringUp(productId)
      cy.get('#sellTotal').should('have.text', '100')      // the column footer keeps meaning "sum of lines"
      typeDiscount(10)
      cy.get('#sellPayableRow').should('be.visible')
      cy.get('#sellPayable').should('have.text', '90.00')

      // Received = the gross 100 → 10.00 of CHANGE is owed back (the defect showed 0 here).
      cy.get('#sellRec').clear().type('100')
      cy.get('#sellCh').should('have.value', '10')

      // Received below the payable → Due is measured against 90, not 100.
      cy.get('#sellRec').clear().type('50')
      cy.get('#sellDueThis').should('have.value', '40.00')

      // F8 tenders the PAYABLE (the defect tendered 100 and the receipt printed 10 of "change").
      cy.get('#sellRec').clear()
      cy.window().then((w) => w.posExactCash())
      cy.get('#sellRec').should('have.value', '90.00')
    })
  })

  it('⭐⭐ 2 — tendering the payable stores change 0 and grand = payable; the field is then empty', () => {
    cy.seedProduct({ name: `TD2_${uniq()}`, sellingPrice: 100, stock: 5 }).then(({ productId }) => {
      ringUp(productId)
      typeDiscount(10)
      cy.get('#sellRec').clear().type('90')
      completeSale().then((i) => {
        expect(i.request.body.tradeDiscount).to.eq(10)
        assertInvoiceSold(i.response.body.object, productId).then((inv) => {
          expect(Number(inv.grandTotal), 'grand total = what the till asked for').to.eq(90)
          expect(Number(inv.tradeDiscount), 'the discount is on the invoice').to.eq(10)
          expect(Number(inv.changeAmount || 0), 'NO phantom change — the INV-000050 defect').to.eq(0)
          expect(Number(inv.tenderedAmount)).to.eq(90)
        })
      })
      // The next customer must not inherit it.
      cy.get('#sellTradeDiscount').should('have.value', '')
      cy.get('#sellPayableRow').should('not.be.visible')
    })
  })

  it('⭐ 3 — park takes the discount with the basket, resume brings it back', () => {
    cy.seedProduct({ name: `TD3_${uniq()}`, sellingPrice: 100, stock: 5 }).then(({ productId }) => {
      ringUp(productId)
      typeDiscount(10)

      cy.intercept('POST', '**/parkSale').as('park')
      cy.window().then((w) => w.parkCurrentSale())
      cy.wait('@park').then((i) => {
        expect(i.response.body.status).to.eq('SUCCESS')
        expect(i.request.body.cart.tradeDiscount, 'the parked basket carries the discount').to.eq(10)
        expect(Number(i.request.body.total), 'the parked list shows what will be paid').to.eq(90)
        cy.get('#sellTradeDiscount').should('have.value', '')   // parked = gone from the till

        const parkedId = i.response.body.object
        cy.intercept('POST', '**/claimParked').as('claim')
        cy.window().then((w) => w.resumeParked(parkedId))
        cy.wait('@claim').its('response.body.status').should('eq', 'SUCCESS')
      })
      cy.window().its('data').should('have.length', 1)
      cy.get('#sellTradeDiscount').should('have.value', '10.00')
      cy.get('#sellPayable').should('have.text', '90.00')
    })
  })

  it('⭐ 4 — edit shows the invoice\'s own discount, and clearing it removes it', () => {
    cy.seedProduct({ name: `TD4_${uniq()}`, sellingPrice: 100, stock: 5 }).then(({ productId }) => {
      ringUp(productId)
      typeDiscount(10)
      cy.get('#sellRec').clear().type('100')          // customer pays 100 → 10 back as REAL change
      completeSale().then((i) => {
        const invoiceNo = i.response.body.object

        // A stale value from some other sale must not survive into the edit.
        typeDiscount(33)
        lineIdOf(invoiceNo).then((sellId) => {
          cy.intercept('GET', '**/getSellInvoice*').as('load')
          cy.window().then((w) => w.loadSellForEdit(sellId))
          cy.wait('@load')
          cy.get('#sellTradeDiscount').should('have.value', '10.00')

          // Remove it and save.
          cy.get('#sellTradeDiscount').clear({ force: true })
          cy.intercept('POST', '**/updateSell').as('upd')
          cy.get('#addSell').click({ force: true })
          cy.wait('@upd', { timeout: 20000 }).then((u) => {
            expect(u.response.body.status, JSON.stringify(u.response.body)).to.eq('SUCCESS')
            expect(u.request.body.tradeDiscount, 'a cleared box travels as an explicit 0').to.eq(0)
          })
          receiptOf(invoiceNo).then((inv) => {
            expect(inv.tradeDiscount == null || Number(inv.tradeDiscount) === 0, 'discount removed').to.eq(true)
            expect(Number(inv.grandTotal)).to.eq(100)
          })
          cy.get('#sellTradeDiscount').should('have.value', '')
        })
      })
    })
  })

  it('5 — Cancel edit and Clear cart both empty the field', () => {
    cy.seedProduct({ name: `TD5_${uniq()}`, sellingPrice: 100, stock: 5 }).then(({ productId }) => {
      ringUp(productId)
      typeDiscount(10)
      cy.get('#sellRec').clear().type('90')
      completeSale().then((i) => {
        lineIdOf(i.response.body.object).then((sellId) => {
          cy.intercept('GET', '**/getSellInvoice*').as('load')
          cy.window().then((w) => w.loadSellForEdit(sellId))
          cy.wait('@load')
          cy.get('#sellTradeDiscount').should('have.value', '10.00')
          cy.get('#cancelSellEdit').click({ force: true })
          cy.get('#sellTradeDiscount').should('have.value', '')
        })
      })

      // Clear cart (F9 / the Clear button both end in resetCart()).
      cy.get('#sellItemDD').select(String(productId), { force: true })
      cy.get('#sellQuantity', { timeout: 15000 }).should(($q) => expect($q.val(), 'loadStock has answered').not.to.eq(''))
      cy.get('#sellQuantity').clear().type('1').should('have.value', '1')
      cy.get('#addInviceItem').click({ force: true })
      cy.window().its('data').should('have.length', 1)
      typeDiscount(7)
      cy.get('#resetSellItem').click({ force: true })
      cy.get('#sellTradeDiscount').should('have.value', '')
      cy.get('#sellPayableRow').should('not.be.visible')
    })
  })
})
