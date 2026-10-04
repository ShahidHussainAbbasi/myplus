/**
 * FP-4c — finance stamps each supplier's open expense bills (and advance) onto business's supplier; a tenant on
 * FINANCE sees purchases + bills in the supplier list, the purchase dropdown and the credit limit — and the Pay button
 * still settles purchases only (FP-5).
 *
 * Design: microservices/docs/slices/fp-4-payables-reads-switch.md §4c (rulings 1 and 2).
 *
 * <h3>⚠ Server state, all restored in after()</h3>
 * owner.business back on BUSINESS; the purchase credit policy back to its seeded default; Expense management back OFF.
 */

const GW = 'http://localhost:8765'
const PW = 'Demo@2025!'
const OWNER = 'owner.business@myplus.com'
const CAP = 'expenseManagement'
const POLICY = 'pos.purchase.creditLimitPolicy'

const hdr = (t, extra = {}) => ({ Authorization: `Bearer ${t}`, 'Content-Type': 'application/json', ...extra })
const ownerToken = () => cy.request({ method: 'POST', url: `${GW}/api/auth/login`, body: { email: OWNER, password: PW } })
  .its('body.data.accessToken')
const key = () => `fp4c-${Date.now()}-${Math.random().toString(36).slice(2, 8)}`
const today = () => localIsoDate()
const flip = (orgId, source, reason) =>
  cy.request({ method: 'POST', url: '/platform/payablesSource', form: true, body: { organizationId: orgId, source, reason } })
    .its('body.status').should('eq', 'SUCCESS')

/** The supplier as the grid receives it. */
const vendorRow = (id) => cy.request('/getUserVender').then((r) => (r.body.collection || []).find((v) => v.id === id))
/** Poll the grid row until a predicate holds (finance stamps after its commit). */
const until = (id, pred, label, n = 25) => vendorRow(id).then((v) => {
  if ((v && pred(v)) || n <= 0) { expect(v && pred(v), `${label}: ${JSON.stringify(v && { due: v.dueAmount, total: v.totalOwed, bills: v.billsOwed, src: v.payablesSource })}`).to.eq(true); return v }
  cy.wait(1000)
  return until(id, pred, label, n - 1)
})

describe('FP-4c — what finance knows, stamped on the supplier', () => {
  let ownerOrg = null
  let vendorId = null
  let productId = null
  const stamp = Date.now()
  const name = 'FP4cVEN_' + stamp

  const creditPurchase = (amount, inv) =>
    cy.request({ method: 'POST', url: '/addPurchase', form: true, failOnStatusCode: false,
      body: { productId, quantity: 1, venderId: vendorId, paidAmount: 0, 'stock.bpurchaseRate': amount, 'stock.bsellRate': amount + 1,
        totalAmount: amount, netAmount: amount, purchaseInvoiceNo: inv } }).its('body')

  before(() => {
    cy.loginAsOperator()
    cy.orgOf(OWNER).then((o) => { ownerOrg = o.id })
    cy.loginAsOwner()
    cy.setCapability(CAP, true)
    cy.request({ method: 'POST', url: '/resetBusinessConfig', form: true, body: { key: POLICY } })
    cy.request({ method: 'POST', url: '/addCompany', form: true, body: { name: 'FP4cCo_' + stamp, email: `fp4c${stamp}@t.com` } })
    cy.request('/getUserCompany').then((cr) => {
      const company = (cr.body.collection || cr.body.data || []).find((c) => c.name === 'FP4cCo_' + stamp)
      cy.request({ method: 'POST', url: '/addVender', form: true,
        body: { name, companyId: company.id, mobile: '0304' + String(stamp).slice(-7), email: `fp4cv${stamp}@t.com`, creditLimit: 1000 } })
      cy.request('/getUserVender').then((lr) => { vendorId = (lr.body.collection || []).find((x) => x.name === name).id })
    })
    cy.seedProduct({ name: 'FP4cP_' + stamp, sellingPrice: 1000, stock: 1 }).then((p) => { productId = p.productId })
    cy.then(() => creditPurchase(600, 'FP4cINV1-' + stamp).its('status').should('eq', 'SUCCESS'))
    ownerToken().then((t) => {
      cy.request({ url: `${GW}/api/expense/categories`, headers: hdr(t) }).then((cr) => {
        const cat = (cr.body.data || []).find((c) => c.active !== false) || cr.body.data[0]
        cy.request({ method: 'POST', url: `${GW}/api/expense/vouchers?post=true`, headers: hdr(t, { 'Idempotency-Key': key() }),
          body: { voucherDate: today(), paidFrom: 'AP', supplierId: vendorId, lines: [{ categoryId: cat.id, amount: 300 }] } })
          .its('body.success').should('eq', true)
      })
    })
  })

  after(() => {
    cy.loginAsOperator()
    cy.then(() => { if (ownerOrg) flip(ownerOrg, 'BUSINESS', 'FP-4c gate cleanup') })
    cy.loginAsOwner()
    cy.request({ method: 'POST', url: '/resetBusinessConfig', form: true, body: { key: POLICY } })
    cy.request({ method: 'POST', url: '/resetBusinessConfig', form: true, body: { key: 'org.cap.' + CAP } })
  })

  it('1 — on BUSINESS the supplier shows purchases only, exactly as before', () => {
    cy.loginAsOwner()
    vendorRow(vendorId).then((v) => {
      expect(v.payablesSource).to.eq('BUSINESS')
      expect(Number(v.dueAmount)).to.eq(600)
      expect(Number(v.totalOwed), 'total = purchases on BUSINESS').to.eq(600)
      expect(Number(v.billsOwed)).to.eq(0)
    })
  })

  it('2 — on FINANCE: the bill is stamped; total = purchases + bills', () => {
    cy.loginAsOperator()
    flip(ownerOrg, 'FINANCE', 'FP-4c gate')
    cy.loginAsOwner()
    until(vendorId, (v) => Number(v.billsOwed) === 300, 'finance stamped the 300 bill').then((v) => {
      expect(v.payablesSource).to.eq('FINANCE')
      expect(Number(v.totalOwed), 'purchases 600 + bills 300').to.eq(900)
      expect(Number(v.dueAmount), 'what Pay Supplier can settle').to.eq(600)
    })
    // the purchase screen's supplier dropdown warns with everything owed
    cy.request('/getUserVenders').its('body').then((html) => {
      const m = new RegExp(`value=${vendorId} data-due="([^"]+)"`).exec(html)
      expect(m, 'the supplier option').to.exist
      expect(Number(m[1]), 'data-due includes the bill').to.eq(900)
    })
  })

  it('3 — a profile edit keeps the stamp (the edit path rebuilds the row from the form)', () => {
    cy.loginAsOwner()
    vendorRow(vendorId).then((v) => {
      cy.request({ method: 'POST', url: '/addVender', form: true,
        body: { id: vendorId, name, companyId: (v.companyIds || '').split(',')[0] || v.companyId, mobile: v.mobile, email: v.email,
          address: 'edited by FP-4c', creditLimit: 1000, dueAmount: 0 } }).its('body.status').should('eq', 'SUCCESS')
    })
    vendorRow(vendorId).then((v) => {
      expect(Number(v.billsOwed), 'stamp survives the edit').to.eq(300)
      expect(Number(v.dueAmount), 'and so does the purchase due').to.eq(600)
    })
  })

  it('4 — credit limit 1,000 under BLOCK: 150 more is refused on FINANCE (900 + 150), allowed on BUSINESS (600 + 150)', () => {
    cy.loginAsOwner()
    cy.request({ method: 'POST', url: '/saveBusinessConfig', form: true, body: { key: POLICY, value: 'block' } })
      .its('body.success').should('eq', true)
    creditPurchase(150, 'FP4cINV2-' + stamp).then((b) => {
      expect(b.status, 'refused on FINANCE').to.not.eq('SUCCESS')
      expect(JSON.stringify(b)).to.match(/credit limit/i)
    })
    cy.loginAsOperator()
    flip(ownerOrg, 'BUSINESS', 'FP-4c gate: compare')
    cy.loginAsOwner()
    creditPurchase(150, 'FP4cINV3-' + stamp).its('status').should('eq', 'SUCCESS')
    vendorRow(vendorId).then((v) => {
      expect(v.payablesSource).to.eq('BUSINESS')
      expect(Number(v.totalOwed), 'BUSINESS: purchases only').to.eq(750)
      expect(Number(v.billsOwed)).to.eq(0)
    })
  })

  it('5 — on screen (FINANCE): the Due cell shows the total with "incl. bills"; since FP-5b Pay carries the TOTAL (it settles bills too)', () => {
    cy.loginAsOperator()
    flip(ownerOrg, 'FINANCE', 'FP-4c gate: screen')
    cy.loginAsOwner()
    until(vendorId, (v) => v.payablesSource === 'FINANCE' && Number(v.totalOwed) === 1050, 'purchases 750 + bills 300')
    cy.visit('/businessDashboard')
    cy.waitForAppReady()
    cy.openSection('VenderDiv')
    cy.get('#VenderDiv input[type="search"]').first().clear().type(name)
    cy.contains('#VenderDiv tr', name, { timeout: 15000 }).within(() => {
      cy.get('[data-cy=vender-due]').should('contain', '1050').and('contain', '300.00')
      cy.get('[data-cy=vender-bills]').should('exist')
      cy.get('.pay-vendor-btn').should('have.attr', 'data-due').and('match', /^1050/)   // FP-5b: one Pay for purchases AND bills
    })
  })
})
