/**
 * EX-8e — the expense report by branch. The branch is taken AUTOMATICALLY (owner's ruling): an expense carries the branch
 * its recorder is working in (the active branch in their sign-in), never one sent by the browser. The report groups by it,
 * with each branch's own name from the module that owns it (a school's branches from education).
 *
 * Design: microservices/docs/slices/ex-8e-report-by-branch.md §4. School tenant: owner.education, and user.education given
 * ONE branch for this spec (so it is their active branch) and none again afterwards.
 */

const GW = 'http://localhost:8765'
const PW = 'Demo@2025!'
const MGMT = 'org.cap.expenseManagement'
const OWNER = 'owner.education@myplus.com', USER = 'user.education@myplus.com', USER_ID = 96
const run = String(Date.now()).slice(-6)

const signIn = (email) => cy.loginAs(email, PW, '/getDashboardData', 'ex8e-' + Date.now())
const token = (email) => cy.request({ method: 'POST', url: `${GW}/api/auth/login`, body: { email, password: PW } }).its('body.data.accessToken')
const hdr = (t, extra = {}) => ({ Authorization: `Bearer ${t}`, 'Content-Type': 'application/json', ...extra })
const key = () => `ex8e-${Date.now()}-${Math.random().toString(36).slice(2, 8)}`
const TODAY = () => localIsoDate(new Date())
const r2 = (n) => Math.round(Number(n) * 100) / 100
const grant = (t, storeIds) => cy.request({ method: 'POST', url: `${GW}/api/auth/org/locations/grant`, headers: hdr(t),
  body: { userId: USER_ID, storeIds, roleAtLocation: 'USER', replace: true } }).its('body.success').should('eq', true)
const report = (t, by) => cy.request({ url: `${GW}/api/expense/reports/summary?from=${TODAY()}&to=${TODAY()}&by=${by}`, headers: hdr(t) }).its('body.data')
const schools = (t) => cy.request({ url: `${GW}/api/expense/tags?source=education`, headers: hdr(t) }).its('body.data')
  .then((l) => l.filter((x) => x.type === 'SCHOOL'))
const rentId = (t) => cy.request({ url: `${GW}/api/expense/categories`, headers: hdr(t) }).its('body.data').then((l) => l.find((c) => c.accountCode === '6000' && c.active).id)
const record = (t, amount, payee, extra = {}) => rentId(t).then((cat) => cy.request({ method: 'POST', url: `${GW}/api/expense/vouchers?post=true`,
  headers: hdr(t, { 'Idempotency-Key': key() }), failOnStatusCode: false,
  body: { voucherDate: TODAY(), paidFrom: 'CASH', payeeName: payee, lines: [{ categoryId: cat, amount }], ...extra } }))

describe('EX-8e — the expense report by branch', () => {
  let branch = null, other = null       // the user's one branch, and a branch of the school they do not hold

  before(() => {
    token(OWNER).then((t) => {
      cy.request({ method: 'POST', url: `${GW}/api/auth/settings?key=${MGMT}&value=true`, headers: { Authorization: `Bearer ${t}` } })
        .then((r) => expect(r.body.success, JSON.stringify(r.body)).to.eq(true))
      schools(t).then((l) => {
        expect(l.length, 'the school has at least two branches').to.be.greaterThan(1)
        branch = l[0]; other = l[1]
        grant(t, [branch.id])
      })
    })
  })

  after(() => {
    token(OWNER).then((t) => {
      grant(t, [])                           // user.education holds no branch again, as before this spec
      cy.request({ url: `${GW}/api/expense/vouchers?size=200`, headers: hdr(t) }).its('body.data.content').then((l) =>
        l.filter((v) => (v.payeeName || '').includes(run) && v.status === 'POSTED').forEach((v) =>
          cy.request({ method: 'POST', url: `${GW}/api/expense/vouchers/${v.id}/void`, headers: hdr(t), body: { reason: 'EX-8e gate' }, failOnStatusCode: false })))
      cy.request({ method: 'POST', url: `${GW}/api/auth/settings/reset?key=${MGMT}`, headers: { Authorization: `Bearer ${t}` }, failOnStatusCode: false })
    })
  })

  it('⭐ 1 — automatic: the user, working in their one branch, records an expense that carries it', () => {
    token(USER).then((u) => record(u, 14, `EX8E user ${run}`).then((r) => {
      expect(r.body.success, JSON.stringify(r.body)).to.eq(true)
      expect(r.body.data.storeId, 'the branch they are working in').to.eq(branch.id)
    }))
  })

  it('⭐ 2 — never from the browser: another branch is refused, their own is accepted; the owner (no branch) records none', () => {
    token(USER).then((u) => {
      record(u, 1, `EX8E other ${run}`, { storeId: other.id }).then((r) => {
        expect(r.body.success).to.eq(false)
        expect(r.body.message).to.contain('branch you are working in')
      })
      record(u, 1, `EX8E foreign ${run}`, { storeId: 999999 }).its('body.success').should('eq', false)
      record(u, 2, `EX8E own ${run}`, { storeId: branch.id }).then((r) => {
        expect(r.body.success, JSON.stringify(r.body)).to.eq(true)
        expect(r.body.data.storeId).to.eq(branch.id)
      })
    })
    token(OWNER).then((t) => record(t, 9, `EX8E owner ${run}`).then((r) => {
      expect(r.body.success, JSON.stringify(r.body)).to.eq(true)
      expect(r.body.data.storeId, 'no branch: the owner holds none').to.eq(null)
    }))
  })

  it('⭐ 3 — the report by branch: named by the school, "No branch" for the rest, and it adds up to the report by category', () => {
    token(OWNER).then((t) => schools(t).then((named) => report(t, 'branch').then((b) => report(t, 'category').then((c) => {
      expect(b.by).to.eq('branch')
      const mine = b.groups.find((g) => g.key === String(branch.id))
      expect(mine, 'the user\'s branch has a row').to.exist
      expect(mine.label, 'named by education').to.eq(branch.label)
      expect(r2(mine.amount), 'at least the user\'s 14 + 2').to.be.at.least(16)
      const none = b.groups.find((g) => g.key === 'none')
      expect(none, 'the owner\'s expense').to.exist
      expect(none.label).to.eq('No branch')
      b.groups.filter((g) => g.key !== 'none' && !named.some((s) => String(s.id) === g.key))
        .forEach((g) => expect(g.label, 'a branch nobody names').to.eq(`Branch #${g.key}`))
      expect(r2(b.total), 'the same total as by category').to.eq(r2(c.total))
      expect(r2(b.groups.reduce((s, g) => s + Number(g.amount), 0))).to.eq(r2(b.total))
    }))))
  })

  it('⭐ 4 — on screen: Report → Group by Branch lists the branch by name', () => {
    signIn(OWNER)
    cy.visit('/educationDashboard'); cy.waitForAppReady()
    cy.get('#snavFee').then(($d) => { if (!$d.hasClass('snav-open')) cy.get('#snavFee .snav-btn').click() })
    cy.get('#navExpenses').should('be.visible').click()
    cy.get('#expCategory option', { timeout: 20000 }).should('have.length.greaterThan', 1)
    cy.get('[data-cy=expense-report-open]').click()
    cy.get('[data-cy=report-by]').select('branch', { force: true })
    cy.get('[data-cy=report-run]').click()
    cy.contains('[data-cy=report-row]', branch.label, { timeout: 20000 }).should('be.visible')
    cy.contains('[data-cy=report-row]', 'No branch').should('be.visible')
  })

  it('5 — the CSV has a Branch column; its rows add up to the report', () => {
    token(OWNER).then((t) => cy.request({ url: `${GW}/api/expense/reports/expenses.csv?from=${TODAY()}&to=${TODAY()}`, headers: hdr(t) }).then((res) => {
      const rows = res.body.trim().split(/\r?\n/)
      expect(rows[0]).to.contain('Branch')
      expect(rows.some((l) => l.includes(`EX8E user ${run}`) && l.includes(branch.label)), 'the user\'s row names the branch').to.eq(true)
      report(t, 'branch').then((b) => expect(r2(rows.slice(1).reduce((s, l) => s + Number(l.split(',').slice(-2)[0]), 0))).to.eq(r2(b.total)))
    }))
  })
})
