/**
 * E11 — the daily payables check confirms EXPENSE BILLS against expense-service, and a bill whose journal never
 * landed is not owed in the ledger.
 *
 * Design: microservices/docs/slices/fp-6b-expense-bill-parity.md §4.
 * Tenant: owner.payables (reserved for payables gates). The run is the operator's "check now" — the same call the daily
 * job makes. Case 3 plants a stale finance document directly in finance's database (fault injection, the way the
 * FP-6a gate plants a GL-only journal): it is the only way to make the subledger disagree with expense-service on
 * purpose, which is exactly what the check must catch and repair.
 */

const GW = 'http://localhost:8765'
const PW = 'Demo@2025!'
const PAYABLES = 'owner.payables@myplus.com'
const CAP = 'expenseManagement'
const stamp = Date.now()

const token = (email) => cy.request({ method: 'POST', url: `${GW}/api/auth/login`, body: { email, password: PW } }).its('body.data.accessToken')
const hdr = (t, extra = {}) => ({ Authorization: `Bearer ${t}`, 'Content-Type': 'application/json', ...extra })
const recon = (t) => cy.request({ url: `${GW}/api/finance/payables/reconciliation`, headers: hdr(t) }).its('body')
const run = (org) => cy.request({ method: 'POST', url: '/platform/payablesReconciliation/run', form: true, body: { organizationId: org } })
  .its('body').then((b) => { expect(b.status, JSON.stringify(b.message)).to.eq('SUCCESS'); return b.object || b.data })
const lock = (t, through) => cy.request({ method: 'POST', url: `${GW}/api/finance/gl/period-lock${through ? `?lockedThrough=${through}` : ''}`, headers: hdr(t) })
const yesterday = () => { const d = new Date(); d.setDate(d.getDate() - 1); return localIsoDate(d) }
const voucher = (t, id) => cy.request({ url: `${GW}/api/expense/vouchers/${id}`, headers: hdr(t) }).its('body.data')
const settled = (t, id, n = 20) => voucher(t, id).then((v) => (v.postingStatus !== 'PENDING' || n <= 0) ? v : (cy.wait(1000), settled(t, id, n - 1)))
/** finance's EXPENSE_BILL figure for this tenant, polled until it equals `want` (the payable outbox delivers after commit). */
const billsNet = (t, want, n = 20) => recon(t).then((r) => {
  const v = Number(r.expenseBillNet)
  return (want === undefined || Math.abs(v - want) < 0.005 || n <= 0) ? v : (cy.wait(1000), billsNet(t, want, n - 1))
})
/** SQL through stdin — no quoting of the statement inside a shell string. */
const sql = (q) => cy.exec(`echo "${q}" | docker exec -i myplus-mysql sh -c 'mysql -uroot -p"$MYSQL_ROOT_PASSWORD" -N 2>/dev/null'`)

describe('E11 — expense bills in the daily payables check', () => {
  let org, t, supplierId, category, failedBill, billNo

  before(() => {
    cy.loginAsOperator()
    cy.orgOf(PAYABLES).then((o) => { org = o.id })
    cy.loginAs(PAYABLES, PW, '/getBusinessDashboardStats')
    cy.setCapability(CAP, true)
    cy.request({ method: 'POST', url: '/addCompany', form: true, body: { name: 'E11Co_' + stamp, email: `e11${stamp}@t.com` } })
    cy.request('/getUserCompany').then((cr) => {
      const company = (cr.body.collection || cr.body.data || []).find((c) => c.name === 'E11Co_' + stamp)
      cy.request({ method: 'POST', url: '/addVender', form: true,
        body: { name: 'E11VEN_' + stamp, companyId: company.id, mobile: '0303' + String(stamp).slice(-7), email: `e11v${stamp}@t.com` } })
      cy.request('/getUserVender').then((lr) => { supplierId = (lr.body.collection || lr.body.data || []).find((x) => x.name === 'E11VEN_' + stamp).id })
    })
    token(PAYABLES).then((x) => {
      t = x
      lock(t, null)
      cy.request({ url: `${GW}/api/expense/categories`, headers: hdr(t) }).then((r) => { category = r.body.data.find((c) => c.active && c.accountCode === '6300') })
    })
    cy.loginAsOperator()
    cy.then(() => run(org))     // settle whatever earlier gates left
  })

  after(() => {
    token(PAYABLES).then((x) => {
      lock(x, null)
      if (failedBill) cy.request({ method: 'POST', url: `${GW}/api/expense/vouchers/${failedBill}/void`, headers: hdr(x), body: { reason: 'E11 gate' }, failOnStatusCode: false })
    })
    cy.loginAs(PAYABLES, PW, '/getBusinessDashboardStats')
    cy.request({ method: 'POST', url: '/resetBusinessConfig', form: true, body: { key: 'org.cap.' + CAP } })
  })

  it('⭐ 1 — a bill refused by a closed period is NOT owed in the ledger, and the check aligns nothing for it', () => {
    billsNet(t).then((before) => {
      cy.then(() => lock(t, yesterday()))
      cy.then(() => cy.request({ method: 'POST', url: `${GW}/api/expense/vouchers?post=true`, headers: hdr(t, { 'Idempotency-Key': 'e11-' + stamp }),
        body: { voucherDate: yesterday(), paidFrom: 'AP', supplierId, lines: [{ categoryId: category.id, amount: 55 }] } }))
        .its('body.data').then((v) => { failedBill = v.id; billNo = v.voucherNo })
      cy.then(() => settled(t, failedBill)).its('postingStatus').should('eq', 'FAILED')
      cy.wait(3000)
      billsNet(t, before).should('eq', before)                           // finance does not hold it open
      recon(t).its('difference').then(Number).should('eq', 0)          // GL 2000 = the ledger
      // (the day's row keeps the differences it was FIRST found with — FP-6a — so a run is judged by its live figures)
      recon(t).its('glAccountsPayable').then(Number).then((gl) => {
        cy.loginAsOperator()
        cy.then(() => run(org)).then((d) => {
          expect(Number(d.financeExpense), 'expense-service and finance agree on the bills').to.eq(Number(d.expenseOwed))
        })
        recon(t).its('glAccountsPayable').then(Number).should('eq', gl)    // nothing aligned for the refused bill
        recon(t).its('difference').then(Number).should('eq', 0)
      })
    })
  })

  it('⭐ 2 — posted again once the period is reopened: now in the books AND owed in the ledger; the check agrees', () => {
    billsNet(t).then((before) => {
      cy.then(() => lock(t, null))
      cy.then(() => cy.request({ method: 'POST', url: `${GW}/api/expense/vouchers/${failedBill}/post-again`, headers: hdr(t) })).its('body.success').should('eq', true)
      cy.then(() => settled(t, failedBill)).its('postingStatus').should('eq', 'POSTED_GL')
      billsNet(t, before + 55).should('eq', before + 55)
      recon(t).its('difference').then(Number).should('eq', 0)
      cy.loginAsOperator()
      cy.then(() => run(org)).then((d) => {
        expect(Number(d.financeExpense), 'finance = expense-service').to.eq(Number(d.expenseOwed))
      })
    })
  })

  it('⭐ 3 — finance holding a stale bill (planted) is FOUND, the bills are re-sent, and the books are not moved onto it', () => {
    recon(t).its('glAccountsPayable').then(Number).as('gl2000')
    billsNet(t).then((good) => {
      // the fault: finance's document for this bill says 20 paid that expense-service never recorded
      sql(`UPDATE myplusdb_finance.payable_doc SET paid = 20, status = 'OPEN' WHERE organization_id = ${org} AND source = 'EXPENSE_BILL' AND source_ref = '${failedBill}'`)
      billsNet(t).should('eq', good - 20)
      recon(t).its('difference').then(Number).should('eq', 20)            // GL ahead of the (wrong) ledger by 20
      cy.loginAsOperator()
      cy.then(() => run(org)).then((d) => {
        expect(d.clean, 'a day that needed a repair is not clean').to.eq(false)
        expect(Number(d.billsResent), 'bills re-sent').to.be.greaterThan(0)
      })
      billsNet(t, good).should('eq', good)                                // the document is right again
      recon(t).its('difference').then(Number).should('eq', 0)
      cy.get('@gl2000').then((gl) => recon(t).its('glAccountsPayable').then(Number).should('eq', gl))   // GL 2000 NOT moved
    })
  })
})
