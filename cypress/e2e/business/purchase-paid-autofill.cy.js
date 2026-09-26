/**
 * PUR-PAID-1 — Paid shows, and follows, the line's bill on a NEW purchase.
 *
 * Design: microservices/docs/slices/pur-paid-1-autofill.md
 *
 * The server has always read a BLANK Paid as "paid in full" (PurchaseService: paid = bill). What the user saw was an
 * EMPTY box that looks unpaid, and a typed part-payment that silently created a due. So the box now shows the bill,
 * and while it is still the till's own figure it is SENT BLANK — the server records its own exact bill, so this
 * display can never disagree with the books.
 *
 * The expected bill is computed from the tenant's REAL tax setting (not a stub): the server adds input tax from the
 * same setting, and a stub would let the screen and the books disagree while the spec stayed green.
 *
 * Run headed.
 */

const STAMP = Date.now()
let productId, productId2, vendorName, taxRate = 0

const bill = (qty, rate) => {
  const net = Math.round(qty * rate * 100) / 100
  return Math.round((net + Math.round(net * taxRate) / 100) * 100) / 100
}

function openFreshPurchaseModal() {
  cy.intercept('GET', '**/getTaxSetting').as('tax')
  cy.openPurchaseSection('purchaseDiv')
  cy.get('#newPurchase').click()
  cy.get('#PurchaseModal').should('have.class', 'open')
  cy.wait('@tax')
  cy.settled('#purchaseInvoiceNo')   // see purchase-rapid-entry.cy.js: the modal slides while pickers upgrade
}

function fillHeader(invoiceNo) {
  cy.get('#purchaseVenderDD option', { timeout: 15000 })
    .contains(vendorName)
    .then(($o) => cy.get('#purchaseVenderDD').select($o.val(), { force: true }))
  cy.get('#purchaseInvoiceNo').clear().type(invoiceNo)
}

function fillLine(qty, rate, pid = productId) {
  cy.intercept('GET', '/productStock*').as('prefill')
  cy.get('#purchaseItemDD').select(String(pid), { force: true })
  cy.wait('@prefill', { timeout: 15000 })
  cy.get('#purchaseQuantity').clear().type(String(qty))
  cy.get('#purchasePurchaseRate').clear().type(String(rate))
  cy.get('#purchaseSellRate').clear().type(String(rate + 10))
}

/** The stored row, read back from the server — the books, not the screen. */
function storedLine(inv) {
  return cy.request({ url: '/getUserPurchase', failOnStatusCode: false }).then((res) => {
    const rows = res.body.collection || res.body.data || []
    const mine = rows.filter((p) => p.purchaseInvoiceNo === inv)
    expect(mine.length, `one row saved under ${inv}`).to.eq(1)
    return mine[0]
  })
}

describe('PUR-PAID-1 — Paid follows the line bill on a new purchase', () => {
  before(() => {
    cy.loginAsBusiness()
    vendorName = `PP1Vendor_${STAMP}`
    cy.ensureCompany().then((companyId) => {
      cy.request({
        method: 'POST', url: '/addVender', form: true,
        body: { name: vendorName, mobile: '03001234567', companyId }, failOnStatusCode: false,
      }).then((r) => {
        expect(r.status, 'seed vendor').to.eq(200)
        expect(r.body.status, JSON.stringify(r.body)).to.be.oneOf(['SUCCESS', 'FOUND'])
      })
    })
    cy.seedProduct({ name: `PP1Item_${STAMP}`, sellingPrice: 60, stock: 5 }).then((p) => { productId = p.productId })
    // Line 2 of case 4 must be a DIFFERENT product: the same product + the same (blank) batch on one bill is the
    // same line to DuplicateBillLine (DOC-INT), which rightly refuses it — the first draft of this spec hit that.
    cy.seedProduct({ name: `PP1Item2_${STAMP}`, sellingPrice: 40, stock: 5 }).then((p) => { productId2 = p.productId })
    cy.request('/getTaxSetting').then((r) => {
      const s = (r.body && r.body.object) || {}
      taxRate = s.inputTaxEnabled === true ? (Number(s.defaultRate) || 0) : 0
      cy.log(`input tax on this tenant: ${taxRate}%`)
    })
  })

  beforeEach(() => { cy.loginAsBusiness() })

  it('1. a new line shows its bill in Paid, sends it BLANK, and is stored fully paid', () => {
    const inv = `PP1-AUTO-${STAMP}`
    cy.intercept('POST', '/addPurchase').as('save')
    openFreshPurchaseModal()
    cy.get('#purchasePaid').should('have.value', '')          // nothing to pay yet
    fillHeader(inv)
    fillLine(2, 50)
    cy.get('#purchasePaid').should('have.value', bill(2, 50).toFixed(2))
    cy.get('#purchasePaidHint').should('not.be.visible')

    // it FOLLOWS the line while it is still the till's figure
    cy.get('#purchaseQuantity').clear().type('3')
    cy.get('#purchasePaid').should('have.value', bill(3, 50).toFixed(2))
    cy.get('#purchaseQuantity').clear().type('2')
    cy.get('#purchasePaid').should('have.value', bill(2, 50).toFixed(2))

    cy.get('#addPurchase').click()
    cy.wait('@save').then((x) => {
      expect(x.response.statusCode).to.eq(200)
      const body = String(x.request.body)
      expect(body, 'paidAmount is sent BLANK — the server records its own bill').to.match(/(^|&)paidAmount=(&|$)/)
    })
    storedLine(inv).then((row) => {
      // The grid DTO carries paidAmount and totalAmount, NOT dueAmount (due = paid − bill is computed by
      // PurchaseService). Asserting a field the endpoint never returns — `dueAmount || 0` — passes on anything.
      expect(Number(row.totalAmount), 'line total').to.eq(100)
      expect(Number(row.paidAmount), 'stored paid = the bill: nothing due').to.eq(bill(2, 50))
    })
  })

  it('2. a typed part-payment is kept, shows "Due on this line", and is stored as a due', () => {
    const inv = `PP1-PART-${STAMP}`
    const due = bill(2, 50) - 40
    cy.intercept('POST', '/addPurchase').as('save')
    openFreshPurchaseModal()
    fillHeader(inv)
    fillLine(2, 50)
    cy.get('#purchasePaid').clear().type('40')
    cy.get('#purchasePaid').should('have.value', '40')        // typing is never rewritten under the user
    cy.get('#purchasePaidHint').should('be.visible').and('have.attr', 'data-kind', 'due')
      .and('contain', due.toFixed(2))
    cy.get('#purchasePaid').blur()
    cy.get('#purchasePaid').should('have.value', '40')        // leaving it does not snap back either
    // the line changing afterwards does not overwrite the user's amount — only the hint moves
    cy.get('#purchaseQuantity').clear().type('3')
    cy.get('#purchasePaid').should('have.value', '40')
    cy.get('#purchasePaidHint').should('contain', (bill(3, 50) - 40).toFixed(2))
    cy.get('#purchaseQuantity').clear().type('2')

    cy.get('#addPurchase').click()
    cy.wait('@save').then((x) => {
      expect(String(x.request.body), 'the typed amount is sent').to.match(/(^|&)paidAmount=40(&|$)/)
    })
    storedLine(inv).then((row) => {
      expect(Number(row.totalAmount), 'line total').to.eq(100)
      expect(Number(row.paidAmount), 'stored paid — the typed 40, so the rest is due').to.eq(40)
      expect(bill(2, 50) - Number(row.paidAmount), 'due on this line').to.eq(due)
    })
  })

  it('3. emptying Paid returns the line to auto — it refills with the bill', () => {
    openFreshPurchaseModal()
    fillHeader(`PP1-EMPTY-${STAMP}`)
    fillLine(2, 50)
    cy.get('#purchasePaid').clear().type('40').blur()
    cy.get('#purchasePaidHint').should('be.visible')
    cy.get('#purchasePaid').clear().blur()
    cy.get('#purchasePaid').should('have.value', bill(2, 50).toFixed(2))
    cy.get('#purchasePaidHint').should('not.be.visible')
    // and auto again means it follows the line again
    cy.get('#purchaseQuantity').clear().type('4')
    cy.get('#purchasePaid').should('have.value', bill(4, 50).toFixed(2))
  })

  it('4. Save & Add Another: the next line is auto again — a typed payment is never carried over', () => {
    const inv = `PP1-NEXT-${STAMP}`
    cy.intercept('POST', '/addPurchase').as('save')
    openFreshPurchaseModal()
    fillHeader(inv)
    fillLine(2, 50)
    cy.get('#purchasePaid').clear().type('40').blur()
    cy.intercept('GET', '**/getUserPurchase*').as('grid')
    cy.get('#addPurchaseAnother').click()
    cy.wait('@save').its('response.statusCode').should('eq', 200)
    cy.wait('@grid', { timeout: 20000 })
    cy.get('#PurchaseModal').should('have.class', 'open')
    cy.get('#purchasePaid').should('have.value', '')
    cy.get('#purchasePaidHint').should('not.be.visible')

    fillLine(1, 30, productId2)
    cy.get('#purchasePaid').should('have.value', bill(1, 30).toFixed(2))
    cy.get('#addPurchase').click()
    cy.wait('@save').then((x) => {
      expect(String(x.request.body), 'second line sent blank').to.match(/(^|&)paidAmount=(&|$)/)
      expect(x.response.body.status, JSON.stringify(x.response.body)).to.eq('SUCCESS')
    })
    cy.request({ url: '/getUserPurchase', failOnStatusCode: false }).then((res) => {
      const rows = (res.body.collection || res.body.data || []).filter((p) => p.purchaseInvoiceNo === inv)
      expect(rows.length, 'two lines on the bill').to.eq(2)
      const paids = rows.map((r) => Number(r.paidAmount)).sort((a, b) => a - b)
      expect(paids, 'line 1 kept its 40; line 2 paid in full').to.deep.eq([40, bill(1, 30)].sort((a, b) => a - b))
    })
  })
})
