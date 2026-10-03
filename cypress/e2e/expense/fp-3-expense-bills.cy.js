/**
 * FP-3 = EX-4 — expense bills: owed to a supplier, paid later, in finance's one payables subledger.
 *
 * Design: microservices/docs/slices/fp-3-ex-4-expense-bills.md §4.
 *
 * <h3>Money is measured in the TRIAL BALANCE, the subledger in finance's summary</h3>
 * A bill saying "paid 200" is what expense-service believes. Each money case measures the net movement of 2000
 * Accounts Payable, the expense account and 1000 Cash in the live trial balance, and the bill's open amount in
 * finance's payables subledger (source EXPENSE_BILL) — never the voucher's own figures alone.
 *
 * <h3>Capability state</h3>
 * Expense management is switched ON through the monolith session before a gateway token is taken (a token carries
 * the capabilities resolved when it was minted); after() removes the override so owner.business is left as seeded.
 */

const GW = 'http://localhost:8765'
const PW = 'Demo@2025!'
const OWNER = 'owner.business@myplus.com'
const CAP = 'expenseManagement'

const token = (email) =>
  cy.request({ method: 'POST', url: `${GW}/api/auth/login`, headers: { 'Content-Type': 'application/json' },
    body: { email, password: PW }, failOnStatusCode: false })
    .then((r) => { expect(r.status, `login ${email}`).to.eq(200); return r.body.data.accessToken })

const hdr = (t, extra = {}) => ({ Authorization: `Bearer ${t}`, 'Content-Type': 'application/json', ...extra })
const key = () => `fp3-${Date.now()}-${Math.random().toString(36).slice(2, 8)}`
const today = () => new Date().toISOString().slice(0, 10)
const r2 = (n) => Math.round(n * 100) / 100

const netByCode = (t) =>
  cy.request({ url: `${GW}/api/finance/gl/trial-balance`, headers: hdr(t) }).then((r) => {
    expect(r.body.balanced, 'trial balance is balanced').to.eq(true)
    const m = {}
    ;(r.body.rows || []).forEach((row) => { m[row.code] = Number(row.debit || 0) - Number(row.credit || 0) })
    return m
  })
const delta = (before, after, code) => r2((after[code] || 0) - (before[code] || 0))

/** finance's subledger view of ONE bill: open = amount − paid, or null when finance has not got it yet. */
const billInFinance = (t, supplierId) =>
  cy.request({ url: `${GW}/api/finance/payables/summary`, headers: hdr(t) }).then((r) => {
    const row = (r.body.bySupplier || []).find((s) => Number(s.partyId) === Number(supplierId))
    return row ? Number(row.open) : 0
  })
/** Poll finance until this supplier's open bills equal what we expect (the outbox delivers after commit). */
const untilFinanceOpen = (t, supplierId, want, tries = 20) =>
  billInFinance(t, supplierId).then((open) => {
    if (Math.abs(open - want) < 0.005 || tries <= 0) return open
    cy.wait(1000)
    return untilFinanceOpen(t, supplierId, want, tries - 1)
  })

const untilInBooks = (t, id, tries = 20) =>
  cy.request({ url: `${GW}/api/expense/vouchers/${id}`, headers: hdr(t) }).then((r) => {
    const v = r.body.data
    if (v.postingStatus === 'POSTED_GL' || v.postingStatus === 'FAILED' || tries <= 0) return v
    cy.wait(1000)
    return untilInBooks(t, id, tries - 1)
  })

const recordBill = (t, body) =>
  cy.request({ method: 'POST', url: `${GW}/api/expense/vouchers?post=true`, headers: hdr(t, { 'Idempotency-Key': key() }),
    body: { voucherDate: today(), paidFrom: 'AP', ...body }, failOnStatusCode: false })

const pay = (t, id, body, idem) =>
  cy.request({ method: 'POST', url: `${GW}/api/expense/vouchers/${id}/pay`, headers: hdr(t, { 'Idempotency-Key': idem }),
    body, failOnStatusCode: false })

describe('FP-3 — expense bills (gateway-direct)', () => {
  let t = null
  let supplierId = null
  let category = null
  let billId = null

  before(() => {
    cy.loginAsOwner()
    cy.setCapability(CAP, true)
    // a fresh supplier of this business, so its statement and subledger rows are this spec's alone
    const stamp = Date.now()
    cy.request({ method: 'POST', url: '/addCompany', form: true, body: { name: 'FP3Co_' + stamp, email: `fp3${stamp}@t.com` } })
    cy.request('/getUserCompany').then((cr) => {
      const company = (cr.body.collection || cr.body.data || []).find((c) => c.name === 'FP3Co_' + stamp)
      cy.request({ method: 'POST', url: '/addVender', form: true,
        body: { name: 'FP3VEN_' + stamp, companyId: company.id, mobile: '0301' + String(stamp).slice(-7), email: `fp3v${stamp}@t.com` } })
      cy.request('/getUserVender').then((lr) => {
        supplierId = (lr.body.collection || lr.body.data || []).find((x) => x.name === 'FP3VEN_' + stamp).id
      })
    })
    token(OWNER).then((x) => {
      t = x
      cy.request({ url: `${GW}/api/expense/categories`, headers: hdr(t) }).then((r) => {
        category = (r.body.data || []).find((c) => c.active !== false && c.accountCode) || r.body.data[0]
      })
    })
  })

  after(() => {
    cy.loginAsOwner()
    cy.request({ method: 'POST', url: '/resetBusinessConfig', form: true, body: { key: 'org.cap.' + CAP } })
  })

  it('1 — a bill of 500: expense +500, 2000 Accounts Payable credited 500, finance holds it open 500', () => {
    netByCode(t).then((before) => {
      recordBill(t, { supplierId, dueDate: today(), lines: [{ categoryId: category.id, amount: 500, description: 'FP-3 repairs' }] })
        .then((r) => {
          expect(r.body.success, JSON.stringify(r.body)).to.eq(true)
          billId = r.body.data.id
          expect(r.body.data.paidFrom).to.eq('AP')
          expect(r.body.data.supplierId).to.eq(supplierId)
          expect(r.body.data.supplierName, 'the supplier name is business’s label').to.match(/^FP3VEN_/)
          untilInBooks(t, billId).its('postingStatus').should('eq', 'POSTED_GL')
          netByCode(t).then((after) => {
            expect(delta(before, after, category.accountCode), `${category.accountCode} expense`).to.eq(500)
            expect(delta(before, after, '2000'), '2000 Accounts Payable (credit)').to.eq(-500)
            expect(delta(before, after, '1000'), 'no cash moved').to.eq(0)
          })
          untilFinanceOpen(t, supplierId, 500).should('be.closeTo', 500, 0.005)
        })
    })
  })

  it('2 — a supplier that is not this business’s is refused; a supplier on a cash expense is refused', () => {
    recordBill(t, { supplierId: 987654321, lines: [{ categoryId: category.id, amount: 5 }] }).then((r) => {
      expect(r.body.success).to.eq(false)
      expect(r.body.message).to.match(/not found, or is not one of yours/i)
    })
    recordBill(t, { paidFrom: 'CASH', supplierId, lines: [{ categoryId: category.id, amount: 5 }] }).then((r) => {
      expect(r.body.success).to.eq(false)
      expect(r.body.message).to.match(/belong to a bill/i)
    })
  })

  it('3 — paying 200 in cash: 2000 debited 200, 1000 Cash down 200, finance open 300, a PV- number', () => {
    expect(billId, 'the bill from case 1').to.exist
    netByCode(t).then((before) => {
      const idem = key()
      pay(t, billId, { amount: 200, method: 'CASH', paidOn: today() }, idem).then((r) => {
        expect(r.body.success, JSON.stringify(r.body)).to.eq(true)
        expect(r.body.data.status).to.eq('RECORDED')
        expect(r.body.data.receiptNo).to.match(/^PV-\d+/)
        Cypress.env('fp3PayKey', idem)
        Cypress.env('fp3Receipt', r.body.data.receiptNo)
      })
      netByCode(t).then((after) => {
        expect(delta(before, after, '2000'), '2000 Accounts Payable (debit)').to.eq(200)
        expect(delta(before, after, '1000'), '1000 Cash').to.eq(-200)
      })
      cy.request({ url: `${GW}/api/expense/vouchers/${billId}`, headers: hdr(t) }).then((r) => {
        expect(Number(r.body.data.paidAmount)).to.eq(200)
        expect(Number(r.body.data.openAmount)).to.eq(300)
      })
      untilFinanceOpen(t, supplierId, 300).should('be.closeTo', 300, 0.005)
    })
  })

  it('4 — the same payment sent again is the SAME payment: the ledger does not move', () => {
    netByCode(t).then((before) => {
      pay(t, billId, { amount: 200, method: 'CASH', paidOn: today() }, Cypress.env('fp3PayKey')).then((r) => {
        expect(r.body.success, JSON.stringify(r.body)).to.eq(true)
        expect(r.body.data.receiptNo, 'the first payment, answered again').to.eq(Cypress.env('fp3Receipt'))
      })
      netByCode(t).then((after) => {
        expect(delta(before, after, '2000')).to.eq(0)
        expect(delta(before, after, '1000')).to.eq(0)
      })
      cy.request({ url: `${GW}/api/expense/vouchers/${billId}/payments`, headers: hdr(t) })
        .its('body.data').should('have.length', 1)
    })
  })

  it('5 — more than is owed is refused', () => {
    pay(t, billId, { amount: 300.01, method: 'CASH' }, key()).then((r) => {
      expect(r.body.success).to.eq(false)
      expect(r.body.message).to.match(/more than is owed/i)
    })
  })

  it('6 — a paid bill cannot be voided; an unpaid bill voids, reversing 2000 and its document goes VOID', () => {
    cy.request({ method: 'POST', url: `${GW}/api/expense/vouchers/${billId}/void`, headers: hdr(t),
      body: { reason: 'FP-3 gate' }, failOnStatusCode: false }).then((r) => {
      expect(r.body.success).to.eq(false)
      expect(r.body.message).to.match(/Reverse the payments first/i)
    })
    recordBill(t, { supplierId, lines: [{ categoryId: category.id, amount: 70 }] }).then((r) => {
      expect(r.body.success, JSON.stringify(r.body)).to.eq(true)
      const second = r.body.data.id
      untilInBooks(t, second).its('postingStatus').should('eq', 'POSTED_GL')
      untilFinanceOpen(t, supplierId, 370)
      netByCode(t).then((before) => {
        cy.request({ method: 'POST', url: `${GW}/api/expense/vouchers/${second}/void`, headers: hdr(t),
          body: { reason: 'FP-3 gate — entered twice' } }).its('body.success').should('eq', true)
        cy.wait(3000)
        netByCode(t).then((after) => {
          expect(delta(before, after, '2000'), '2000 back').to.eq(70)
          expect(delta(before, after, category.accountCode), 'expense back').to.eq(-70)
        })
        untilFinanceOpen(t, supplierId, 300).should('be.closeTo', 300, 0.005)
      })
    })
  })

  it('7 — the supplier statement in business does not show the bill payment (its bill is not on it)', () => {
    // the exclusion must have something to exclude: finance DOES hold this PV against the supplier
    cy.request({ url: `${GW}/api/finance/payments?partyType=VENDOR&partyId=${supplierId}`, headers: hdr(t) }).then((fr) => {
      const list = Array.isArray(fr.body) ? fr.body : (fr.body.data || [])
      const held = list.find((p) => p.receiptNo === Cypress.env('fp3Receipt'))
      expect(held, 'finance holds the bill payment for this supplier').to.exist
      expect(held.sourceModule).to.eq('EXPENSE')
    })
    cy.loginAsOwner()
    cy.request('/vendorStatement?venderId=' + supplierId).then((sr) => {
      expect(sr.body.status, JSON.stringify(sr.body).slice(0, 200)).to.eq('SUCCESS')
      const lines = sr.body.collection || sr.body.data || []
      const pv = lines.find((l) => String(l.docNo || '').includes(Cypress.env('fp3Receipt')))
      expect(pv, 'no PV line for an expense bill payment').to.be.undefined
    })
  })

  it('9 — on screen: record a bill to the supplier, see what it owes, pay it, see it Paid', () => {
    // a NEW session after the capability switch (the session's capabilities are read when it is made)
    cy.loginAsOwner(undefined, undefined, 'fp3-' + Date.now())
    cy.visit('/businessDashboard')
    cy.waitForAppReady()
    cy.get('#snavTill').then(($d) => { if (!$d.hasClass('snav-open')) cy.get('#snavTill .snav-btn').click() })
    cy.get('#navExpenses').should('be.visible').click()
    cy.get('#expCategory option', { timeout: 20000 }).should('have.length.greaterThan', 1)
    cy.get('#expPaidFrom option[value="AP"]', { timeout: 20000 }).should('exist')
    cy.get('#expBillGroup').should('not.be.visible')
    cy.get('#expCategory').select(String(category.id), { force: true })
    cy.get('#expAmount').type('120')
    cy.get('#expPaidFrom').select('AP', { force: true })
    cy.get('#expBillGroup').should('be.visible')
    // saving without a supplier is refused on the screen
    cy.get('[data-cy=save-expense]').click()
    cy.get('#expMsg').should('contain', 'supplier')
    cy.get('#expSupplier').select(String(supplierId), { force: true })
    // the list shows the PAYEE (not the note), so the row is found by a payee only this case uses
    const note = 'FP3UI-' + Date.now()
    cy.get('#expPayee').type(note)
    cy.get('[data-cy=save-expense]').click()
    cy.get('#expMsg').should('contain', 'Posting to the books')
    cy.contains('#tableExpense tbody tr', note, { timeout: 25000 }).within(() => {
      cy.contains('Bill')
      cy.get('[data-cy=expense-bill-owes]', { timeout: 25000 }).should('contain', '120.00')
      cy.get('[data-cy=pay-bill]', { timeout: 25000 }).click()
    })
    cy.get('[data-cy=expense-pay-panel]').should('be.visible')
    cy.get('#expPayAmount').should('have.value', '120.00')
    cy.get('#expPayMethod').select('BANK', { force: true })
    cy.get('[data-cy=expense-pay-go]').click()
    cy.get('#expMsg', { timeout: 20000 }).should('contain', 'Payment recorded').and('contain', 'PV-')
    cy.contains('#tableExpense tbody tr', note).find('[data-cy=expense-bill-paid]').should('exist')
    cy.contains('#tableExpense tbody tr', note).find('[data-cy=void-expense]').should('not.exist')
  })

  it('8 — business purchases still reconcile exactly: finance PURCHASE net = business Σ supplier due', () => {
    cy.loginAsOwner()
    cy.request('/getUserVender').then((r) => {
      const list = r.body.collection || r.body.data || []
      const business = r2(list.reduce((a, v) => a + Number(v.dueAmount || 0), 0))
      cy.request({ url: `${GW}/api/finance/payables/summary`, headers: hdr(t) }).then((s) => {
        const purchases = Number(((s.body.bySource || {}).PURCHASE || {}).netOwed || 0)
        const bills = Number(((s.body.bySource || {}).EXPENSE_BILL || {}).netOwed || 0)
        expect(purchases, `finance PURCHASE ${purchases} vs business ${business}`).to.be.closeTo(business, 0.005)
        expect(bills, 'bills are visible on their own').to.be.at.least(300)
      })
    })
  })
})
