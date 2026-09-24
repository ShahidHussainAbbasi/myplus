/**
 * CART-1 — the cart grid is drawn from data[] (the cart that is SUBMITTED), so the two can never disagree;
 * and Received follows the bill when a trade discount changes it — only when it held the bill.
 *
 * THE DEFECTS this pins (each read in the code, then asserted where it breaks):
 *   - Del spliced data[] but the ROW stayed whenever the row was already selected (removal hung off a row-click
 *     handler and a global flag). Footer, payable, Change and Due then counted a line that would never be sold.
 *   - two lines of the same product: Del removed the FIRST line from data[] but the CLICKED row from the grid.
 *   - an invoice opened for edit drew NO rows (its totals described an empty cart), and its lines lost the
 *     line discount the server re-prices from (whether that raised the total is asserted in case 5).
 *   - Received stayed at the old bill when a trade discount was entered after it, and the discount came back as
 *     Change on the receipt (INV-000050).
 *
 * Run headed:
 *   npx cypress run --spec cypress/e2e/business/cart-grid-sync.cy.js --headed --browser chrome
 */
const uniq = () => `${Date.now()}`.slice(-8) + Math.floor(Math.random() * 900 + 100)
const list = (b) => (b && (b.collection || b.object || b.data)) || []

const overlayGone = () => {
  cy.get('body').then(($b) => {
    if ($b.find('#appAjaxOverlay').length) cy.get('#appAjaxOverlay', { timeout: 30000 }).should('not.be.visible')
  })
}

const openTill = () => {
  cy.visit('/businessDashboard')
  cy.waitForAppReady()
  cy.window().its('posConfirmOnComplete', { timeout: 15000 }).should('eq', true)
  cy.get('#sellType').select('sellDiv', { force: true })
}

/** Add one line through the real form. Waits for loadStock so the typed quantity is the one used. */
const addLine = (productId, qty, lineDiscount, discountType = '0') => {
  cy.get('#sellItemDD', { timeout: 15000 }).select(String(productId), { force: true })
  cy.get('#sellQuantity', { timeout: 15000 }).should(($q) => expect($q.val(), 'loadStock answered').not.to.eq(''))
  cy.get('#sellQuantity').clear().type(String(qty)).should('have.value', String(qty))
  if (lineDiscount != null) {
    cy.get('#sellDiscountTypeDD').select(discountType, { force: true })   // '0' flat amount, '1' percent
    cy.get('#sellDiscount').clear({ force: true }).type(String(lineDiscount), { force: true })
  }
  cy.get('#addInviceItem').click({ force: true })
  overlayGone()
}

/** The grid and data[] describe the SAME cart: same line count, and the footer is data[]'s bill. */
const assertGridMatchesData = (expectedTotal) => {
  cy.window().then((w) => {
    const rows = w.tablesi.rows().count()
    expect(rows, 'grid rows = data[] lines').to.eq(w.data.length)
  })
  cy.get('#sellTotal').should(($t) => expect(Number($t.text()), 'footer = the bill in data[]').to.eq(expectedTotal))
}

/** The Disc column's footer, read through DataTables' own API (column 4) — never a DOM index that the hidden
 *  column 0 can shift. Retries until it matches. */
const discFooter = (expected) =>
  cy.window().should((w) => {
    expect(w.jQuery(w.tablesi.column(4).footer()).text(), 'Disc footer').to.eq(expected)
  })

const seed = (price) => cy.seedProduct({ name: `CG_${uniq()}`, sellingPrice: price, stock: 20 }).then((p) => p.productId)

describe('CART-1 — grid drawn from data[]; Received follows the bill', () => {
  before(() => {
    cy.loginAsOwner()
    cy.request({ method: 'POST', url: '/saveBusinessConfig', form: true,
      body: { key: 'pos.sale.confirmOnComplete', value: 'true' } })
  })
  beforeEach(() => cy.loginAsOwner())

  it('⭐⭐ 1 — Del on an ALREADY-SELECTED row removes it from the grid AND the totals', () => {
    seed(100).then((a) => seed(50).then((b) => {
      openTill()
      addLine(a, 1)
      addLine(b, 1)
      assertGridMatchesData(150)
      cy.get('#sellTradeDiscount').clear({ force: true }).type('10', { force: true })
      cy.get('#sellPayable').should('have.text', '140.00')

      // Select the second row first — the case the old row-click handler skipped.
      cy.get('#tablesi tbody tr').eq(1).click({ force: true }).should('have.class', 'selected')
      cy.get('#tablesi tbody tr').eq(1).find('#DII').click({ force: true })

      cy.window().its('data').should('have.length', 1)
      assertGridMatchesData(100)
      cy.get('#sellPayable', { timeout: 5000 }).should('have.text', '90.00')   // the payable follows a delete
    }))
  })

  /*
   * Two lines of one product: Del on the SECOND must remove the second. The old UIT(pid) removed the FIRST line
   * for that product from data[] while the grid removed the clicked row — the screen and the submitted cart then
   * held different lines. (It never removed both: forEach+splice skips the next element. An earlier note here
   * claimed it did; the red baseline disproved it, so this case asserts what the defect actually breaks.)
   */
  it('⭐ 2 — two lines of the same product: Del removes THE LINE CLICKED, not the first one', () => {
    seed(40).then((a) => {
      openTill()
      addLine(a, 1)
      addLine(a, 2)
      cy.window().its('data').should('have.length', 2)
      assertGridMatchesData(120)
      cy.get('#tablesi tbody tr').eq(1).find('#DII').click({ force: true })   // the qty-2 line
      cy.window().its('data').should('have.length', 1)
      cy.window().then((w) => expect(Number(w.data[0].quantity), 'the qty-1 line is the one left').to.eq(1))
      assertGridMatchesData(40)
    })
  })

  it('3 — adding a line refreshes the payable line and Change', () => {
    seed(100).then((a) => seed(30).then((b) => {
      openTill()
      addLine(a, 1)
      cy.get('#sellTradeDiscount').clear({ force: true }).type('5', { force: true })
      cy.get('#sellPayable').should('have.text', '95.00')
      cy.get('#sellRec').clear().type('200')
      cy.get('#sellCh').should('have.value', '105')
      addLine(b, 1)
      assertGridMatchesData(130)
      cy.get('#sellPayable').should('have.text', '125.00')
      cy.get('#sellCh').should('have.value', '75')
    }))
  })

  it('⭐⭐ 4 — Received that held the bill follows a trade discount; cash in hand does not; empty stays empty', () => {
    seed(100).then((a) => {
      openTill()
      addLine(a, 1)

      // Exactly the bill (typed) → follows.
      cy.get('#sellRec').clear().type('100')
      cy.get('#sellTradeDiscount').clear({ force: true }).type('10', { force: true })
      cy.get('#sellRec').should('have.value', '90.00')
      cy.get('#sellCh').should('have.value', '0')

      // Via F8 → still exactly the bill → follows a further change.
      cy.window().then((w) => w.posExactCash())
      cy.get('#sellTradeDiscount').clear({ force: true }).type('20', { force: true })
      cy.get('#sellRec').should('have.value', '80.00')

      // Cash in hand (the customer handed over 150) → NOT rewritten; the change grows.
      cy.get('#sellRec').clear().type('150')
      cy.get('#sellTradeDiscount').clear({ force: true }).type('30', { force: true })
      cy.get('#sellRec').should('have.value', '150')
      cy.get('#sellCh').should('have.value', '80')

      // Empty → stays empty: the till must not claim money it was not told about.
      cy.get('#sellRec').clear()
      cy.get('#sellTradeDiscount').clear({ force: true }).type('5', { force: true })
      cy.get('#sellRec').should('have.value', '')
    })
  })

  it('⭐⭐ 5 — edit draws the invoice\'s lines, and saving it unchanged keeps the LINE DISCOUNT (total unchanged)', () => {
    seed(100).then((a) => {
      openTill()
      addLine(a, 2, 10)                                   // 2 × 100 − 10 = 190
      assertGridMatchesData(190)
      cy.get('#sellPayMethod').select('CASH', { force: true })
      cy.get('#sellRec').clear().type('190')
      cy.intercept('POST', '**/addSell').as('sale')
      cy.get('#addSell').click({ force: true })
      cy.confirmSale()
      cy.wait('@sale', { timeout: 20000 }).then((i) => {
        expect(i.response.body.status, JSON.stringify(i.response.body)).to.eq('SUCCESS')
        const invoiceNo = i.response.body.object
        cy.request(`/getReceipt?invoiceNo=${encodeURIComponent(invoiceNo)}`)
          .its('body.object.grandTotal').then((g) => expect(Number(g), 'as sold').to.eq(190))

        cy.request('/getUserSell').then((r) => {
          const row = list(r.body).find((x) => (x.customerHistory || {}).invoiceNo === invoiceNo)
          expect(row, 'the sold line').to.exist
          cy.intercept('GET', '**/getSellInvoice*').as('load')
          cy.window().then((w) => w.loadSellForEdit(row.sellId || row.sell_id || row.id))
          cy.wait('@load')
        })
        // The edit screen shows the invoice — one row, its real bill, nothing more to collect.
        assertGridMatchesData(190)
        cy.get('#sellDueThis').should('have.value', '0.00')

        // Save the edit WITHOUT changing anything.
        cy.intercept('POST', '**/updateSell').as('upd')
        cy.get('#addSell').click({ force: true })
        cy.wait('@upd', { timeout: 20000 }).its('response.body.status').should('eq', 'SUCCESS')
        cy.request(`/getReceipt?invoiceNo=${encodeURIComponent(invoiceNo)}`).then((rr) => {
          const inv = rr.body.object
          expect(Number(inv.grandTotal), '⭐ an untouched edit does not re-price the line at full price').to.eq(190)
          expect(Number(inv.sales[0].discount), 'the line discount survived').to.eq(10)
        })
      })
    })
  })

  /*
   * CART-2 (user: "addInviceItem or DII still not updating the discount, and there should be a total discount of
   * the Disc column"). The line under the cart showed only the TRADE discount, which add/delete never change; and
   * the Disc footer summed label text ("10 (Amt)", "10%") — NaN. Both now come from ONE money rule.
   */
  it('⭐⭐ 7 — Disc footer and the Discount line = line discounts (+ trade), and follow add AND delete', () => {
    seed(100).then((a) => seed(50).then((b) => {
      openTill()
      addLine(a, 1, 10, '0')                    // 100, 10.00 off
      discFooter('10.00')   // Disc footer (col 0 is hidden)
      cy.get('#sellPayableRow').should('be.visible')
      cy.get('#sellDiscountShown').should('have.text', '−10.00')
      cy.get('#sellPayable').should('have.text', '90.00')

      addLine(b, 1, 10, '1')                    // 50, 10% = 5.00 off
      discFooter('15.00')   // money, not "NaN", not "20"
      cy.get('#sellTotal').should('have.text', '135')
      cy.get('#sellDiscountShown').should('have.text', '−15.00')
      cy.get('#sellPayable').should('have.text', '135.00')

      cy.get('#sellTradeDiscount').clear({ force: true }).type('5', { force: true })
      cy.get('#sellDiscountShown').should('have.text', '−20.00')       // lines + trade: ONE figure
      cy.get('#sellPayable').should('have.text', '130.00')             // each discount taken off ONCE

      cy.get('#tablesi tbody tr').eq(0).find('#DII').click({ force: true })   // delete the 10.00-off line
      discFooter('5.00')
      cy.get('#sellDiscountShown').should('have.text', '−10.00')       // 5 on the line + 5 trade
      cy.get('#sellPayable').should('have.text', '40.00')              // 50 − 5 − 5
    }))
  })

  it('6 — a resumed parked sale is drawn from data[] like any other cart', () => {
    seed(100).then((a) => seed(20).then((b) => {
      openTill()
      addLine(a, 1)
      addLine(b, 3)
      assertGridMatchesData(160)
      cy.intercept('POST', '**/parkSale').as('park')
      cy.window().then((w) => w.parkCurrentSale())
      cy.wait('@park').then((i) => {
        assertGridMatchesData(0)
        cy.intercept('POST', '**/claimParked').as('claim')
        cy.window().then((w) => w.resumeParked(i.response.body.object))
        cy.wait('@claim')
      })
      cy.window().its('data').should('have.length', 2)
      assertGridMatchesData(160)
    }))
  })
})
