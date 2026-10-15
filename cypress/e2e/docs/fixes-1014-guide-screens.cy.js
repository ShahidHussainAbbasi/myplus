/**
 * Test Book section 30 — fixes closed on 14 Oct, as REAL manual test cases. Every action is PERFORMED, ASSERTED and
 * PHOTOGRAPHED (support/guide-capture.js); docs/guides/build-testbook-section.js builds the section from this run.
 *
 *   R1  a sale return is asked for in TABLETS, not "0.25" of a box (U13)
 *   B1  a supplier bill keyed twice is HELD and asked about; confirming saves it (DOC-INT)
 *   N1  a school notice leaves a per-recipient delivery record (notifications Part A) — the record has no screen,
 *       so its reading step is the console request the record's own proxy answers
 *
 * TENANTS. R1 + B1 on demo.business@ — its ORG-WIDE tax and loose markup are pinned (off / 0) for the run and put back
 * after, because other specs and people flip them. N1 on owner.education@ with a staff member made for the case.
 * Every case ends with its own clean-up; after() repeats the ones that matter if a case failed half-way.
 *
 * Run headed (one case: --env '{"guideOnly":"R1"}'):
 *   npx cypress run --headed --browser chrome --spec cypress/e2e/docs/fixes-1014-guide-screens.cy.js --config trashAssetsBeforeRuns=false
 */
import { guideCapture } from '../../support/guide-capture'

const g = guideCapture({ outDir: 'cypress/guide-out/fixes-1014', section: 'Fixes walked 14 Oct', keepShots: true })
const RUN = String(Date.now()).slice(-6)
const uniq = () => `${Date.now()}${Math.floor(Math.random() * 1000)}`
const BUSINESS = 'demo.business@myplus.com'
const SCHOOL = 'owner.education@myplus.com'

// ── R1 set-up: a box of 40 tablets that may be split, in stock, ten tablets sold ────────────────────────────────────
const PACK = 40
const PRICE = 311.60
const packProduct = (name) =>
  cy.request({
    method: 'POST', url: '/addProduct', headers: { 'Content-Type': 'application/json' }, failOnStatusCode: false,
    body: { name, sku: `TB30${uniq()}`, sellingPrice: PRICE, taxRate: 0, unit: 'box', packSize: PACK, looseUnit: 'tablet',
      looseUnitPlural: 'tablets', allowLoose: true, defaultSellUnit: 'PACK' },
  }).then((r) => {
    expect(r.body && r.body.success, JSON.stringify(r.body).slice(0, 200)).to.eq(true)
    return cy.markNoExpiry(r.body.data.id).then(() => r.body.data.id)
  })
const stockIn = (productId, boxes) =>
  cy.request({ method: 'POST', url: '/addPurchase', form: true, failOnStatusCode: false,
    body: { productId, quantity: boxes, 'stock.batchNo': `TB30B${uniq()}`, 'stock.bpurchaseRate': 100, 'stock.bsellRate': PRICE,
      totalAmount: boxes * 100, netAmount: boxes * 100, purchaseInvoiceNo: `TB30-${uniq()}` } })
    .then((r) => expect(r.body.status, `stock in: ${JSON.stringify(r.body).slice(0, 200)}`).to.eq('SUCCESS'))
const sellLoose = (productId, pieces, total, name) =>
  cy.request({ method: 'POST', url: '/addSell', headers: { 'Content-Type': 'application/json' }, failOnStatusCode: false,
    body: { customer: { name, contact: '03009999999' }, sales: [{ productId, soldUnit: 'LOOSE', soldQuantity: pieces }],
      tenders: [{ method: 'CASH', amount: total }], paidAmount: total, dueAmount: 0, grandTotal: total,
      idempotencyKey: `tb30-${uniq()}` } })
    .then((r) => expect(r.body.status, `the sale: ${JSON.stringify(r.body).slice(0, 220)}`).to.eq('SUCCESS'))

/** Find the sale on the grid by its customer and open its Return dialog. */
const openReturnFor = (customer) => {
  cy.visitSaleScreen()
  cy.get('#tableSell tbody tr', { timeout: 30000 }).should('have.length.greaterThan', 0)
  cy.get('#tableSell_filter input').clear().type(customer)
  return cy.contains('#tableSell tbody tr', customer, { timeout: 30000 })
}
const returnOnScreen = (customer, tablets) => {
  openReturnFor(customer).within(() => cy.get('button[onclick="openSaleReturn(this)"]').click())
  cy.get('#saleReturnDialog').should('be.visible')
  cy.get('#srQty').clear().type(String(tablets))
  cy.intercept('POST', '**/saleReturn').as('ret')
  cy.get('#srSubmit').click()
  cy.wait('@ret').its('response.body.status').should('eq', 'SUCCESS')
  // the screen then OFFERS the credit note — a shop that does not print one says Cancel
  cy.get('.uiC-card', { timeout: 15000 }).should('be.visible')
  cy.get('.uiC-cancel').click()
}

// ── B1 set-up: a supplier and a product of this run ───────────────────────────────────────────────────────────────
let vendorId
let vendorName
let productA
const enterLine = (bill) => {
  cy.openPurchaseSection('purchaseDiv')
  cy.get('#newPurchase').click()
  cy.get('#PurchaseModal').should('have.class', 'open')
  cy.settled('#purchaseInvoiceNo')
  cy.get('#purchaseVenderDD option', { timeout: 15000 }).contains(vendorName)
    .then(($o) => cy.get('#purchaseVenderDD').select($o.val(), { force: true }))
  cy.get('#purchaseInvoiceNo').clear().type(bill)
  cy.intercept('GET', '/productStock*').as('prefill')
  // read the id when the command RUNS — productA is set by a set-up step that has not run yet when this is queued
  cy.then(() => cy.get('#purchaseItemDD').select(String(productA), { force: true }))
  cy.wait('@prefill', { timeout: 15000 })
  cy.get('#purchaseQuantity').clear().type('2')
  cy.get('#purchasePurchaseRate').clear().type('10')
  cy.get('#purchaseSellRate').clear().type('15')
}
const billRows = (bill) => cy.request('/getUserPurchase').then((r) =>
  ((r.body && (r.body.collection || r.body.data)) || []).filter((p) => p.purchaseInvoiceNo === bill))

// ── N1 helpers ───────────────────────────────────────────────────────────────────────────────────────────────────
const deliveries = (email) => cy.request(`/getNotificationDeliveries?recipient=${encodeURIComponent(email)}&limit=100`)
  .then((r) => { const b = r.body; return Array.isArray(b) ? b : (b && (b.collection || b.data)) || [] })
const waitForRows = (email, n, tries = 25) => deliveries(email).then((rows) => {
  if (rows.length >= n) return cy.wrap(rows, { log: false })
  if (tries <= 0) throw new Error(`still ${rows.length} delivery rows for ${email}`)
  return cy.wait(1000, { log: false }).then(() => waitForRows(email, n, tries - 1))
})

describe('Test Book 30 — fixes walked on 14 Oct (captured)', () => {
  let taxBefore = null
  let markupBefore = null
  const voidLater = []          // purchase ids B1 saved — voided in its clean-up, and again in after() if it failed
  let staffEmail = null         // N1's staff member — deleted in its clean-up, and again in after()

  beforeEach(() => cy.viewport(1366, 900))
  afterEach(g.write)

  before(() => {
    cy.loginAsBusiness()
    cy.snapshotTaxSetting().then((s) => { taxBefore = s; cy.setTaxSetting({ ...s, enabled: false }) })
    cy.snapshotBusinessConfig('pos.sale.looseMarkupPct').then((v) => { markupBefore = v; cy.setBusinessConfig('pos.sale.looseMarkupPct', 0) })
  })

  after(() => {
    cy.loginAsBusiness()
    voidLater.forEach((id) => cy.request({ method: 'POST', url: '/voidPurchase', form: true, failOnStatusCode: false,
      body: { purchaseId: id, reason: 'guide cleanup' } }))
    if (markupBefore != null) cy.setBusinessConfig('pos.sale.looseMarkupPct', markupBefore)
    cy.restoreTaxSetting(taxBefore)
    if (staffEmail) {
      cy.loginAsEduOwner()
      cy.request('/getUserStaff').then((r) => {
        const s = ((r.body && (r.body.collection || r.body.data)) || []).find((x) => x.email === staffEmail)
        if (s) cy.request({ method: 'POST', url: '/deleteStaff', form: true, failOnStatusCode: false, body: { checked: s.id || s.staffId } })
      })
    }
  })

  // ── R1 ───────────────────────────────────────────────────────────────────────────────────────────────────
  g.caseIt('R1', 'A sale return is asked for in tablets', () => {
    const name = `TB30 Paracetamol ${RUN}`
    const customer = `TB30_R1_${RUN}`
    g.testCase('R1', 'A sale return is asked for in tablets, not as “0.25” of a box', {
      tenant: BUSINESS, role: 'Cashier or owner (the Sale screen’s Return button)',
      purpose: 'Ten tablets were sold from a box of forty, and the Return dialog asked a pharmacist to return “0.25”. Fractions of a box are where the wrong number gets typed. Now the grid and the dialog read in tablets, and the server does the conversion.',
      prereq: [`Signed in as ${BUSINESS}.`, 'The shop’s tax switched off and its loose markup at 0 for the case — the run pins both and puts them back afterwards.'],
      data: [`Product **${name}**: a box of **40** tablets at **311.60**, splittable`, '5 boxes in stock', `A sale of **10 tablets** (77.90) to **${customer}**`],
      rollback: 'Cleanup returns the other 7 tablets on screen, so the sale line closes and the stock is back where it started. The product and the sale stay in the history (sales are never deleted).',
    })
    cy.loginAsBusiness()
    packProduct(name).then((productId) => { stockIn(productId, 5); sellLoose(productId, 10, 77.90, customer) })

    const a1 = g.act(`Open **Sale**, and type **${customer}** into the grid’s **Search** box.`,
      ['The sale’s row reads **10 tablets** — not **0.25**.'])
    openReturnFor(customer).within(() => cy.get('#sellItems').should('contain.text', '10').and('contain.text', 'tablet'))
    g.snap(a1, 'grid-in-tablets')

    const a2 = g.act('On that row, press **Return**.',
      ['The dialog states what was sold in tablets — **10** — with the shelf figure **0.25** (box) beside it.', 'The quantity box is filled with **10**.'])
    openReturnFor(customer).within(() => cy.get('button[onclick="openSaleReturn(this)"]').click())
    cy.get('#saleReturnDialog').should('be.visible')
    cy.get('#srSold').should('contain.text', '10').and('contain.text', '0.25')
    cy.get('#srQty').should('have.value', '10')
    g.snap(a2, 'dialog-in-tablets', '#saleReturnDialog')

    const a3 = g.act('Change the quantity to **3** and press the return button. When the screen offers the credit note, press **Cancel**.',
      ['The return is accepted.', 'The grid row now reads **7 tablets** — the line shrank with the return; it does not go on claiming ten.'])
    cy.get('#srQty').clear().type('3')
    cy.intercept('POST', '**/saleReturn').as('ret')
    cy.get('#srSubmit').click()
    cy.wait('@ret').its('response.body.status').should('eq', 'SUCCESS')
    cy.get('.uiC-card', { timeout: 15000 }).should('be.visible')
    cy.get('.uiC-cancel').click()
    openReturnFor(customer).within(() => cy.get('#sellItems').should('contain.text', '7').and('contain.text', 'tablet'))
    g.snap(a3, 'seven-left')

    const c1 = g.act('Press **Return** on the row again, keep the **7** it offers, press the return button, and **Cancel** the credit-note offer.',
      ['The return of the last 7 tablets is accepted — all ten have now come back.'], { cleanup: true })
    returnOnScreen(customer, 7)
    g.snap(c1, 'fully-returned')
  })

  // ── B1 ───────────────────────────────────────────────────────────────────────────────────────────────────
  g.caseIt('B1', 'A supplier bill keyed twice is held and asked about', () => {
    const bill = `TB30-B1-${RUN}`
    g.testCase('B1', 'A supplier bill keyed twice is HELD and asked about; confirming saves it', {
      tenant: BUSINESS, role: 'Anyone who records purchases',
      purpose: 'The same supplier bill keyed twice used to be saved twice — stock and the supplier’s balance doubled, silently. Now the second entry is held with a question; the rare real repeat is still possible, because a person decides.',
      prereq: [`Signed in as ${BUSINESS}.`],
      data: [`Supplier **TB30Vendor_${RUN}**, product **TB30Item_${RUN}** (set-up below)`, `Bill number **${bill}**, 2 at 10, selling price 15`],
      rollback: 'Cleanup voids both lines (Purchase → the bill’s row → Void, reason “guide cleanup”) — a void reverses the stock and the supplier’s payable; nothing is deleted.',
    })
    cy.loginAsBusiness()
    vendorName = `TB30Vendor_${RUN}`
    cy.ensureCompany().then((companyId) => {
      cy.request({ method: 'POST', url: '/addVender', form: true, failOnStatusCode: false, body: { name: vendorName, mobile: '03001234567', companyId } })
      cy.request('/getUserVender').then((r) => {
        const v = (r.body.collection || r.body.data || []).find((x) => x.name === vendorName)
        expect(v, 'the supplier is readable back').to.exist
        vendorId = v.id || v.venderId
      })
    })
    cy.seedProduct({ name: `TB30Item_${RUN}`, sellingPrice: 15 }).then(({ productId }) => { productA = productId })
    cy.intercept('POST', '/addPurchase').as('save')

    const a1 = g.act(`Open **Purchase → New Purchase**. Choose the supplier, type bill number **${bill}**, choose the product, quantity **2**, price **10**, selling price **15**, and press **Save & Close**.`,
      ['The purchase is saved and the form closes.'])
    enterLine(bill)
    cy.get('#addPurchase').click()
    cy.wait('@save').its('response.body.status').should('eq', 'SUCCESS')
    cy.get('#PurchaseModal').should('not.have.class', 'open')
    g.snap(a1, 'first-saved')

    const a2 = g.act('Open **New Purchase** again and key the **same** line — same supplier, same bill number, same product — and press **Save & Close**.',
      ['Nothing is saved yet. A dialog titled **Bill already entered** asks whether this really is a second line on that bill.'])
    enterLine(bill)
    cy.get('#addPurchase').click()
    cy.wait('@save').its('response.body.status').should('eq', 'CONFIRM')
    cy.get('.uiC-card').should('be.visible')
    cy.get('#uiC-title').should('contain', 'Bill already entered')
    billRows(bill).then((rows) => expect(rows.length, 'still only the first line').to.eq(1))
    g.snap(a2, 'held')

    const a3 = g.act('Press the dialog’s confirm button.',
      ['The repeat is saved: the bill now has **two** lines. (Cancel would have saved nothing.)'])
    cy.get('[data-ui-confirm="ok"]').click()
    cy.wait('@save').its('response.body.status').should('eq', 'SUCCESS')
    billRows(bill).then((rows) => {
      expect(rows.length, 'the confirmed repeat saved').to.eq(2)
      rows.forEach((p) => voidLater.push(p.purchaseId || p.id))
    })
    g.snap(a3, 'saved-after-confirm')

    const c1 = g.act('Void both lines of the bill (Purchase → the bill’s row → **Void**, reason **guide cleanup**). Recorded with the same request the Void button sends — the button shows only to roles that may void.',
      ['Each line reads **VOID**; the stock-in and the supplier’s payable are reversed.'], { cleanup: true, via: 'run' })
    cy.then(() => {
      voidLater.splice(0).forEach((id) => cy.request({ method: 'POST', url: '/voidPurchase', form: true, body: { purchaseId: id, reason: 'guide cleanup' } })
        .its('body.status').should('eq', 'SUCCESS'))
    })
    billRows(bill).then((rows) => rows.forEach((p) => expect(p.status, `line ${p.purchaseId} voided`).to.eq('VOID')))
    g.snap(c1, 'voided')
  })

  // ── N1 ───────────────────────────────────────────────────────────────────────────────────────────────────
  g.caseIt('N1', 'A school notice leaves a delivery record', () => {
    staffEmail = `tb30.n1.${RUN}@example.test`
    const heading = `TB30 notice ${RUN}`
    g.testCase('N1', 'A school notice leaves a per-recipient delivery record', {
      tenant: SCHOOL, role: 'Owner (System Alerts)',
      purpose: 'Notices used to vanish after sending — no record of who was told, or whether the mail server took it. Now every recipient gets a delivery row with its outcome. “SENT” means the mail server ACCEPTED it, not that it reached an inbox.',
      prereq: [`Signed in as ${SCHOOL}.`, 'Mailpit running (development e-mail lands there, never in a real inbox).'],
      data: [`A staff member with e-mail **${staffEmail}** (set-up below)`, `Notice heading **${heading}**, to **Staff**`],
      rollback: 'Cleanup deletes the staff member. The delivery rows stay — they are the history this slice exists to keep.',
    })
    cy.loginAsEduOwner()
    cy.postForm('/addStaff', { name: `TB30 Staff ${RUN}`, email: staffEmail, status: 'ACTIVE' })

    const a1 = g.act(`Open **Communication → System Alerts**. Choose consumers **Employees**, type the heading **${heading}** and a message, and press **Send**.`,
      ['A box reads **Queued for N of N recipient(s)** — one for every member of staff with an e-mail address.'])
    cy.visit('/educationDashboard')
    cy.get('#communicationType').select('AlertsDiv', { force: true })   // the alerts screen hangs off Communication, as in the sched2 guide
    cy.get('#AlertsDiv').should('be.visible')
    cy.get('#alertHeading').clear().type(heading)
    cy.get('#alertMessage').clear().type('School closed on Friday.')
    cy.get('#alertConsumers').select(['Employees'], { force: true })
    g.snap(a1, 'alert-form', '#AlertsDiv')
    cy.intercept('POST', '**/sendAlerts').as('send')
    cy.get('#sendAlerts').click()
    // the box shows the server's message verbatim (education.js sendAlert -> alert(res.message))
    cy.wait('@send').its('response.body').then((b) => {
      expect(b.status).to.eq('SUCCESS')
      expect(b.message, 'the confirmation').to.match(/Queued for [1-9]\d* of \d+ recipient/)
      a1.expect.push(`On this run it said **${b.message}**.`)
    })

    const code = `$.get(serverContext + 'getNotificationDeliveries?recipient=${staffEmail}&limit=100', r => console.table(r.collection || r.data || r))`
    const a2 = g.act(`The record has no screen yet. Press **F12 → Console** and run:\n\`${code}\``,
      ['A row for **this staff member** comes back, with status **SENT** (or **PENDING** for a moment while the relay delivers) and a sent time.'],
      { via: 'console', code })
    waitForRows(staffEmail, 1).then((rows) => {
      expect(rows[0].recipient, 'the row names the person').to.eq(staffEmail)
      expect(['SENT', 'PENDING', 'FAILED'], 'a known outcome').to.include(rows[0].status)
    })
    g.runInPage(code, 'GET', 'getNotificationDeliveries').then((b) => {
      const rows = Array.isArray(b) ? b : (b.collection || b.data || [])
      expect(rows.some((x) => x.recipient === staffEmail), 'the console command returns the row').to.eq(true)
    })
    g.snap(a2, 'record')

    const c1 = g.act('Delete the staff member (School → Staff → tick the row → **Delete**). Recorded with the same request the Delete button sends.',
      ['The staff member is gone from the list. The delivery row stays as history.'], { cleanup: true, via: 'run' })
    cy.request('/getUserStaff').then((r) => {
      const s = ((r.body && (r.body.collection || r.body.data)) || []).find((x) => x.email === staffEmail)
      expect(s, 'the staff member exists to delete').to.exist
      cy.request({ method: 'POST', url: '/deleteStaff', form: true, body: { checked: s.id || s.staffId } })
    })
    cy.request('/getUserStaff').then((r) => {
      expect(((r.body && (r.body.collection || r.body.data)) || []).some((x) => x.email === staffEmail), 'deleted').to.eq(false)
      staffEmail = null
    })
    g.snap(c1, 'staff-deleted')
  })
})
