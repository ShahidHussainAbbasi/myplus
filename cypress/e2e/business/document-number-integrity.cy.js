/**
 * DOC-INT — document-number integrity. Design: microservices/docs/slices/doc-int-numbers-and-bills.md
 *
 * ⭐ WRITTEN BEFORE THE IMPLEMENTATION (slice cadence), so the cases state the requirement rather than describe
 * what got built. Against the code as it stood when this was written:
 *   RED   — 1, 2, 4 (its ' b1 ' half), 7, and 8 (8 is a race: red when two receipts collide, which count+1 allows
 *           but does not guarantee on any one run; after the fix it is green on EVERY run)
 *   GREEN — 3, 5, 6 and the first half of 4. These are the REGRESSIONS: a multi-line bill, a second batch, a
 *           blank bill number and a same-key retry must behave exactly as before, or the guard broke real work.
 *
 * Requires: monolith, gateway, business, catalog, inventory, finance. Run headed:
 *   npx cypress run --headed --browser chrome --spec cypress/e2e/business/document-number-integrity.cy.js
 */
const uniq = () => `${Date.now()}${Math.floor(Math.random() * 1e4)}`
const STAMP = uniq()

let vendorId, vendorName, productA, productB

/**
 * Every purchase row carrying one bill number.
 *
 * ⚠ ASSERTS THE READ BEFORE COUNTING. A gateway 503 answers an envelope with no rows, and treating that as zero
 * would report "expected 0 to equal 1" and send the reader hunting through the guard for a defect that is not there.
 */
const rowsFor = (bill) =>
  cy.request({ url: '/getUserPurchase', failOnStatusCode: false }).then((r) => {
    expect(r.status, 'the purchase list read itself succeeded').to.eq(200)
    const rows = r.body && (r.body.collection || r.body.data)
    expect(rows, `purchase list returned rows (body: ${JSON.stringify(r.body).slice(0, 160)})`).to.be.an('array')
    return rows.filter((p) => p.purchaseInvoiceNo === bill)
  })

/**
 * One purchase LINE through the real endpoint (monolith → business).
 *
 * Paid in full (paidAmount omitted → the server defaults it to the bill), so the supplier credit-limit prompt —
 * which shares the CONFIRM envelope — cannot be what a case is looking at.
 */
const buy = (fields) =>
  cy.request({
    method: 'POST', url: '/addPurchase', form: true, failOnStatusCode: false,
    body: {
      venderId: vendorId, quantity: 2, purchaseRate: 10,
      'stock.bpurchaseRate': 10, 'stock.bsellRate': 15, totalAmount: 20, netAmount: 20,
      ...fields,
    },
  }).then((r) => r.body)

const expectSaved = (body, what) => expect(body.status, `${what}: ${JSON.stringify(body)}`).to.eq('SUCCESS')

const expectHeld = (body, bill, what) => {
  expect(body.status, `${what}: ${JSON.stringify(body)}`).to.eq('CONFIRM')
  expect(body.message, 'the prompt names the bill, so the operator knows WHICH bill').to.contain(bill)
  // The server names the flag that answers this prompt. Were it the credit-limit flag, confirming a duplicate
  // bill would silently acknowledge a credit-limit breach on the resubmit.
  expect(body.object && body.object.ack, 'the acknowledgement this prompt asks for').to.eq('duplicateBillAcknowledged')
}

describe('DOC-INT — document-number integrity', () => {
  before(() => {
    cy.loginAsBusiness()
    vendorName = `DocIntVendor_${STAMP}`
    // SEED, never assert-or-skip: the spec owns its fixtures, so an empty tenant cannot pass it vacuously.
    // companyId is required — addVender ends with an unguarded getReferenceById(companyId).
    cy.ensureCompany().then((companyId) => {
      cy.request({
        method: 'POST', url: '/addVender', form: true, failOnStatusCode: false,
        body: { name: vendorName, mobile: '03001234567', companyId },
      })
      cy.request('/getUserVender').then((r) => {
        const mine = (r.body.collection || r.body.data || []).find((v) => v.name === vendorName)
        expect(mine, `seeded vendor ${vendorName} is readable back`).to.exist
        vendorId = mine.id || mine.venderId
        expect(vendorId, 'the vendor has an id').to.exist
      })
    })
    cy.seedProduct({ name: `DocIntA_${STAMP}`, sellingPrice: 15 }).then(({ productId }) => { productA = productId })
    cy.seedProduct({ name: `DocIntB_${STAMP}`, sellingPrice: 15 }).then(({ productId }) => { productB = productId })
  })

  beforeEach(() => { cy.loginAsBusiness() })   // testIsolation clears the session between tests

  // ── C — a re-keyed supplier bill line ─────────────────────────────────────────────────────────────────

  it('⭐ 1 — the same bill + product posted twice: the second is HELD, and nothing is written', () => {
    const bill = `DI1-${uniq()}`
    buy({ productId: productA, purchaseInvoiceNo: bill }).then((b) => expectSaved(b, 'first line'))
    buy({ productId: productA, purchaseInvoiceNo: bill }).then((b) => expectHeld(b, bill, 'the same line again'))
    rowsFor(bill).then((rows) => expect(rows.length, 'held, not written — still one row').to.eq(1))
  })

  it('⭐ 2 — confirmed, the repeat IS saved (a person decided; the rare legitimate repeat exists)', () => {
    const bill = `DI2-${uniq()}`
    buy({ productId: productA, purchaseInvoiceNo: bill }).then((b) => expectSaved(b, 'first line'))
    buy({ productId: productA, purchaseInvoiceNo: bill }).then((b) => expectHeld(b, bill, 'repeat, unconfirmed'))
    buy({ productId: productA, purchaseInvoiceNo: bill, duplicateBillAcknowledged: true })
      .then((b) => expectSaved(b, 'repeat, confirmed'))
    rowsFor(bill).then((rows) => expect(rows.length, 'two rows after an explicit confirm').to.eq(2))
  })

  it('REGRESSION 3 — a multi-line bill still saves every line without a prompt', () => {
    const bill = `DI3-${uniq()}`
    buy({ productId: productA, purchaseInvoiceNo: bill }).then((b) => expectSaved(b, 'line 1 (product A)'))
    buy({ productId: productB, purchaseInvoiceNo: bill }).then((b) => expectSaved(b, 'line 2 (product B)'))
    rowsFor(bill).then((rows) => expect(rows.length, 'two lines on one bill').to.eq(2))
  })

  it('REGRESSION 4 — one product in two BATCHES on one bill is two lines, not a duplicate', () => {
    const bill = `DI4-${uniq()}`
    buy({ productId: productA, purchaseInvoiceNo: bill, 'stock.batchNo': 'B1' }).then((b) => expectSaved(b, 'batch B1'))
    buy({ productId: productA, purchaseInvoiceNo: bill, 'stock.batchNo': 'B2' }).then((b) => expectSaved(b, 'batch B2'))
    // ...but the SAME batch typed differently is still the same line.
    buy({ productId: productA, purchaseInvoiceNo: bill, 'stock.batchNo': ' b1 ' })
      .then((b) => expectHeld(b, bill, 'batch " b1 " is batch B1'))
    rowsFor(bill).then((rows) => expect(rows.length, 'B1 + B2, the re-keyed B1 held').to.eq(2))
  })

  it('REGRESSION 5 — a purchase with NO bill number is never checked (there is nothing to match on)', () => {
    cy.seedProduct({ name: `DocIntC_${uniq()}`, sellingPrice: 15 }).then(({ productId }) => {
      buy({ productId, purchaseInvoiceNo: '' }).then((b) => expectSaved(b, 'blank bill #1'))
      buy({ productId, purchaseInvoiceNo: '' }).then((b) => expectSaved(b, 'blank bill #2'))
      cy.request({ url: '/getUserPurchase', failOnStatusCode: false }).then((r) => {
        expect(r.status).to.eq(200)
        const rows = (r.body.collection || r.body.data || []).filter((p) => p.productId === productId)
        expect(rows.length, 'both blank-bill purchases saved').to.eq(2)
      })
    })
  })

  it('REGRESSION 6 — a same-key RETRY replays the first save; it is not asked about', () => {
    const bill = `DI6-${uniq()}`
    const idempotencyKey = `di6-${uniq()}`
    buy({ productId: productA, purchaseInvoiceNo: bill, idempotencyKey }).then((b) => expectSaved(b, 'first send'))
    buy({ productId: productA, purchaseInvoiceNo: bill, idempotencyKey }).then((b) => expectSaved(b, 'retry, same key'))
    rowsFor(bill).then((rows) => expect(rows.length, 'a retry is the same purchase').to.eq(1))
  })

  it('⭐ 7 — the purchase FORM asks, and confirming sends ONLY the duplicate-bill acknowledgement', () => {
    const bill = `DI7-${uniq()}`
    cy.intercept('POST', '/addPurchase').as('save')

    // The same line, keyed through the real form — the path a shop actually uses.
    const enterLine = () => {
      cy.openPurchaseSection('purchaseDiv')
      cy.get('#newPurchase').click()
      cy.get('#PurchaseModal').should('have.class', 'open')
      cy.settled('#purchaseInvoiceNo')   // the modal slides while bootstrap-select rebuilds — wait for it to stop
      cy.get('#purchaseVenderDD option', { timeout: 15000 }).contains(vendorName)
        .then(($o) => cy.get('#purchaseVenderDD').select($o.val(), { force: true }))
      cy.get('#purchaseInvoiceNo').clear().type(bill)
      cy.intercept('GET', '/productStock*').as('prefill')
      cy.get('#purchaseItemDD').select(String(productA), { force: true })
      cy.wait('@prefill', { timeout: 15000 })
      cy.get('#purchaseQuantity').clear().type('2')
      cy.get('#purchasePurchaseRate').clear().type('10')
      cy.get('#purchaseSellRate').clear().type('15')
      cy.get('#addPurchase').click()
    }

    enterLine()
    cy.wait('@save').its('response.body.status').should('eq', 'SUCCESS')
    cy.get('#PurchaseModal').should('not.have.class', 'open')

    enterLine()
    cy.wait('@save').its('response.body.status').should('eq', 'CONFIRM')
    cy.get('.uiC-card').should('be.visible')
    cy.get('#uiC-title').should('contain', 'Bill already entered')
    cy.get('[data-ui-confirm="ok"]').click()

    cy.wait('@save').then(({ request, response }) => {
      const sent = String(request.body)
      expect(sent, 'the resubmit carries the duplicate-bill acknowledgement').to.contain('duplicateBillAcknowledged=true')
      expect(sent, '...and does NOT also acknowledge a credit-limit breach').not.to.contain('creditAcknowledged')
      expect(response.body.status, JSON.stringify(response.body)).to.eq('SUCCESS')
    })
    rowsFor(bill).then((rows) => expect(rows.length, 'the confirmed repeat saved').to.eq(2))
  })

  // ── B — receipt numbers ──────────────────────────────────────────────────────────────────────────────

  it('⭐ 8 — six CONCURRENT receipts get six DISTINCT receipt numbers', () => {
    /*
     * count + 1 hands two receipts the same number whenever both count before either commits. Six customers,
     * one receipt each, all in flight at once. Different customers on purpose: the business-side locks are per
     * customer, so the ONLY place these six meet is finance's numbering — which is the thing under test.
     */
    const tag = uniq()
    const names = [0, 1, 2, 3, 4, 5].map((i) => `DocIntCust_${tag}_${i}`)
    names.forEach((name) => {
      cy.request({
        method: 'POST', url: '/addCustomer', form: true, failOnStatusCode: false,
        body: { name, contact: '03' + Math.floor(Math.random() * 1e8), customerType: 'WHOLESALE' },
      }).then((r) => expect(r.body.status, `addCustomer ${name}: ${JSON.stringify(r.body)}`).to.eq('SUCCESS'))
    })

    const ids = []
    cy.request('/getUserCustomer?q=-1').then((r) => {
      const rows = r.body.collection || r.body.data || []
      names.forEach((name) => {
        const mine = rows.find((c) => c.name === name)
        expect(mine, `customer ${name} is readable back`).to.exist
        ids.push(mine.customerId || mine.id)
      })
    })

    // The app's own transport, so the requests carry the session exactly as a till's would.
    cy.visit('/businessDashboard')
    cy.window({ timeout: 30000 }).its('jQuery').should('exist')

    cy.window().then((w) => {
      w.__rcpt = { nos: [], failures: [] }
      ids.forEach((customerId, i) => {
        w.$.ajax({
          type: 'POST', url: '/receivePayment', dataType: 'json',
          // dedupe:false — these six bodies differ anyway, but the flag states the intent: they must RACE.
          dedupe: false,
          data: { customerId, amount: 1, method: 'CASH', reference: `DI8-${tag}-${i}`, idempotencyKey: `di8-${tag}-${i}` },
          success: (r) => (r && r.status === 'SUCCESS')
            ? w.__rcpt.nos.push(r.object && r.object.receiptNo)
            : w.__rcpt.failures.push(JSON.stringify(r).slice(0, 200)),
          error: (x) => w.__rcpt.failures.push(x && x.status),
        })
      })
    })

    // A RETRYING wait, never a cross-realm promise handed to cy.then (DUP-1 trap 1).
    cy.window({ timeout: 30000 }).should((w) => {
      expect(w.__rcpt.nos.length + w.__rcpt.failures.length, 'all six receipts settled').to.eq(6)
    })

    cy.window().then((w) => {
      expect(w.__rcpt.failures, 'none of the six was refused').to.deep.eq([])
      const nos = w.__rcpt.nos
      // A null number means the ledger write failed and SubledgerService logged it — that is not a pass.
      nos.forEach((n) => expect(n, 'finance issued a receipt number').to.match(/^RCPT-\d{6,}$/))
      expect(new Set(nos).size, `distinct receipt numbers (got ${nos.join(', ')})`).to.eq(6)
    })
  })
})
