/**
 * FP-4b — the per-tenant payables switch: operator-only, refused unless business = finance, and the supplier aging /
 * statement / CSV follow it.
 *
 * Design: microservices/docs/slices/fp-4-payables-reads-switch.md §4 (rulings 4 and 5).
 *
 * <h3>⚠ Server state</h3>
 * The switch is a row per tenant. after() puts owner.business back on BUSINESS (and the expense capability back to
 * its seeded OFF), whatever happened in between — the next suite must not inherit a tenant reading from finance.
 */

const GW = 'http://localhost:8765'
const PW = 'Demo@2025!'
const OWNER = 'owner.business@myplus.com'
const DEMO = 'demo.business@myplus.com'
const CAP = 'expenseManagement'

const hdr = (t, extra = {}) => ({ Authorization: `Bearer ${t}`, 'Content-Type': 'application/json', ...extra })
const ownerToken = () => cy.request({ method: 'POST', url: `${GW}/api/auth/login`, body: { email: OWNER, password: PW } })
  .its('body.data.accessToken')
const key = () => `fp4b-${Date.now()}-${Math.random().toString(36).slice(2, 8)}`
const today = () => localIsoDate()

const status = (orgId) => cy.request(`/platform/payablesSource?organizationId=${orgId}`).then((r) => {
  expect(r.body.status, JSON.stringify(r.body).slice(0, 300)).to.eq('SUCCESS')
  return r.body.object
})
const flip = (orgId, source, reason) =>
  cy.request({ method: 'POST', url: '/platform/payablesSource', form: true, failOnStatusCode: false,
    body: { organizationId: orgId, source, reason } }).its('body')

/** Σ aging per supplier, and the answering source. */
const aging = () => cy.request('/vendorAging').then((r) => {
  expect(r.body.status, JSON.stringify(r.body).slice(0, 200)).to.eq('SUCCESS')
  const by = {}
  ;(r.body.collection || []).forEach((row) => { by[row.partyId] = Math.round(Number(row.total) * 100) / 100 })
  return { by, source: (r.body.object || {}).source, advances: (r.body.object || {}).advances || [] }
})

describe('FP-4b — where supplier figures are read from', () => {
  let ownerOrg = null
  let demoOrg = null
  let vendorId = null
  let billNo = null

  before(() => {
    cy.loginAsOperator()
    cy.orgOf(OWNER).then((o) => { ownerOrg = o.id })
    cy.orgOf(DEMO).then((o) => { demoOrg = o.id })
  })

  after(() => {
    cy.loginAsOperator()
    cy.then(() => { if (ownerOrg) flip(ownerOrg, 'BUSINESS', 'FP-4b gate cleanup') })
    cy.loginAsOwner()
    cy.request({ method: 'POST', url: '/resetBusinessConfig', form: true, body: { key: 'org.cap.' + CAP } })
  })

  it('1 — an owner can neither read nor flip the switch (console route AND the service itself)', () => {
    cy.loginAsOwner()
    cy.request({ url: `/platform/payablesSource?organizationId=${ownerOrg}`, failOnStatusCode: false })
      .then((r) => expect(r.status === 403 || (r.body && r.body.status !== 'SUCCESS'), `console: ${r.status}`).to.eq(true))
    ownerToken().then((t) => {
      cy.request({ method: 'POST', url: `${GW}/api/business/payables-source?organizationId=${ownerOrg}&source=FINANCE&reason=mine`,
        headers: hdr(t), failOnStatusCode: false }).then((r) => {
        expect(r.status, 'the service refuses a tenant, not only the console').to.eq(403)
      })
    })
  })

  it('2 — the operator sees both reconciliations; a tenant whose figures disagree is refused; a reason is required', () => {
    cy.loginAsOperator()
    status(ownerOrg).then((d) => {
      expect(d.source).to.eq('BUSINESS')
      expect(Number(d.difference), 'owner.business: business = finance').to.eq(0)
      expect(d).to.have.property('glDifference')
    })
    status(demoOrg).then((d) => {
      expect(Number(d.difference), 'demo.business carries the known 100').to.not.eq(0)
      flip(demoOrg, 'FINANCE', 'should be refused').then((b) => {
        expect(b.status).to.eq('ERROR')
        expect(b.message).to.match(/disagree by/i)
      })
    })
    flip(ownerOrg, 'FINANCE', ' ').then((b) => {
      expect(b.status).to.eq('ERROR')
      expect(b.message).to.match(/Say why/i)
    })
    status(ownerOrg).its('source').should('eq', 'BUSINESS')
  })

  it('3 — owner.business on FINANCE: aging = business aging plus bills, advances beside it, the statement and CSV show a bill', () => {
    // a supplier with an expense bill, so the switch has something business cannot show
    cy.loginAsOwner()
    cy.setCapability(CAP, true)
    const stamp = Date.now()
    cy.request({ method: 'POST', url: '/addCompany', form: true, body: { name: 'FP4bCo_' + stamp, email: `fp4b${stamp}@t.com` } })
    cy.request('/getUserCompany').then((cr) => {
      const company = (cr.body.collection || cr.body.data || []).find((c) => c.name === 'FP4bCo_' + stamp)
      cy.request({ method: 'POST', url: '/addVender', form: true,
        body: { name: 'FP4bVEN_' + stamp, companyId: company.id, mobile: '0303' + String(stamp).slice(-7), email: `fp4bv${stamp}@t.com` } })
      cy.request('/getUserVender').then((lr) => {
        vendorId = (lr.body.collection || lr.body.data || []).find((x) => x.name === 'FP4bVEN_' + stamp).id
      })
    })
    ownerToken().then((t) => {
      cy.request({ url: `${GW}/api/expense/categories`, headers: hdr(t) }).then((cr) => {
        const cat = (cr.body.data || []).find((c) => c.active !== false) || cr.body.data[0]
        cy.request({ method: 'POST', url: `${GW}/api/expense/vouchers?post=true`, headers: hdr(t, { 'Idempotency-Key': key() }),
          body: { voucherDate: today(), paidFrom: 'AP', supplierId: vendorId, lines: [{ categoryId: cat.id, amount: 65 }] } })
          .then((r) => { expect(r.body.success, JSON.stringify(r.body)).to.eq(true); billNo = r.body.data.voucherNo })
      })
    })
    // wait until finance holds the bill (the outbox delivers after commit)
    const held = (n = 20) => ownerToken().then((t) => cy.request({ url: `${GW}/api/finance/payables/statement?partyType=VENDOR&partyId=${vendorId}`, headers: hdr(t) })
      .then((r) => (r.body.some((l) => l.docNo === billNo) || n <= 0) ? true : (cy.wait(1000), held(n - 1))))
    held()

    cy.loginAsOwner()
    aging().then((before) => {
      expect(before.source).to.eq('BUSINESS')
      expect(before.by[vendorId], 'business does not know the bill').to.be.undefined
      cy.loginAsOperator()
      flip(ownerOrg, 'FINANCE', 'FP-4b gate: parity reached').then((b) => {
        expect(b.status, JSON.stringify(b)).to.eq('SUCCESS')
        expect(b.object.source).to.eq('FINANCE')
      })
      cy.loginAsOwner()
      aging().then((after) => {
        expect(after.source).to.eq('FINANCE')
        expect(after.by[vendorId], 'the bill is in the aging').to.eq(65)
        // every supplier business already showed keeps its figure (no bills against them) or grows by its bills
        Object.keys(before.by).forEach((id) => expect(after.by[id], `supplier ${id}`).to.be.at.least(before.by[id]))
        expect(after.advances, 'advances travel beside the rows').to.be.an('array')
      })
      cy.request('/vendorStatement?venderId=' + vendorId).then((sr) => {
        expect(sr.body.status).to.eq('SUCCESS')
        const lines = sr.body.collection || []
        expect(lines.some((l) => l.docNo === billNo && Number(l.debit) === 65), 'the bill on the statement').to.eq(true)
      })
      cy.request('/vendorStatement.csv?venderId=' + vendorId).its('body').should('contain', billNo)
      // the screen: the Payables Aging dialog renders finance's rows
      cy.visit('/businessDashboard')
      cy.waitForAppReady()
      cy.window().then((win) => win.openAging('VENDOR'))
      cy.get('#AgingDialogBody table', { timeout: 15000 }).should('contain', 'FP4bVEN_')
    })
  })

  it('4 — back to BUSINESS (always allowed): the bill leaves the statement and the aging', () => {
    cy.loginAsOperator()
    flip(ownerOrg, 'BUSINESS', 'FP-4b gate: rollback').then((b) => {
      expect(b.status, JSON.stringify(b)).to.eq('SUCCESS')
      expect(b.object.source).to.eq('BUSINESS')
    })
    cy.loginAsOwner()
    aging().then((a) => {
      expect(a.source).to.eq('BUSINESS')
      expect(a.by[vendorId]).to.be.undefined
    })
    cy.request('/vendorStatement?venderId=' + vendorId).then((sr) => {
      expect((sr.body.collection || []).some((l) => l.docNo === billNo), 'business does not list bills').to.eq(false)
    })
  })
})
