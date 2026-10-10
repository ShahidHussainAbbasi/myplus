/**
 * EX-9a — past till pay-outs brought into the books, with the owner's consent (R-4): an owner-run, previewed import;
 * each pay-out books once (keyed like EX-3: DRAWER + movement id); one that matches an expense already recorded (same day,
 * same amount) is flagged and left unticked, never booked twice by accident.
 *
 * Design: microservices/docs/slices/ex-9a-till-history-import.md §4. Shop tenant (owner.business). The pay-outs are made
 * with the module OFF (so EX-3 does not book them), exactly as the history this imports was made.
 */

const GW = 'http://localhost:8765'
const PW = 'Demo@2025!'
const OWNER = { email: 'owner.business@myplus.com', check: '/getBusinessDashboardStats' }
const USER = 'user.business@myplus.com'
const CAP = 'org.cap.expenseManagement'
const run = String(Date.now()).slice(-6)
const AMT = [41 + Number(run.slice(-2)) / 100, 52 + Number(run.slice(-2)) / 100, 63 + Number(run.slice(-2)) / 100]

const signIn = (fresh) => cy.loginAs(OWNER.email, PW, OWNER.check, fresh ? 'ex9a-' + Date.now() : undefined)
const token = (email) => cy.request({ method: 'POST', url: `${GW}/api/auth/login`, body: { email, password: PW } }).its('body.data.accessToken')
const hdr = (t, extra = {}) => ({ Authorization: `Bearer ${t}`, 'Content-Type': 'application/json', ...extra })
const r2 = (n) => Math.round(Number(n) * 100) / 100
const closeIfOpen = () => cy.request({ url: '/currentShift', failOnStatusCode: false }).then((r) => {
  if (r.body && r.body.status === 'SUCCESS') cy.request({ method: 'POST', url: '/closeShift', form: true, body: { countedCash: 0 }, failOnStatusCode: false })
})
const payOut = (amount, reason) => cy.request({ method: 'POST', url: '/cashMovement', form: true,
  body: { type: 'PAY_OUT', amount, reason, idempotencyKey: 'ex9a-' + Date.now() + Math.random() } }).its('body.status').should('eq', 'SUCCESS')
const net = () => cy.request('/gl/trialBalance').then((r) => {
  const m = {}
  const body = typeof r.body === 'string' ? JSON.parse(r.body) : r.body
  ;(body.rows || []).forEach((row) => { m[row.code] = Number(row.debit || 0) - Number(row.credit || 0) })
  return m
})
const d = (a, b, code) => r2((b[code] || 0) - (a[code] || 0))
const preview = (t) => cy.request({ url: `${GW}/api/expense/history/till`, headers: hdr(t) }).its('body.data')
const mine = (rows) => rows.filter((x) => (x.reason || '').includes(run))
const rentId = (t) => cy.request({ url: `${GW}/api/expense/categories`, headers: hdr(t) }).its('body.data').then((l) => l.find((c) => c.accountCode === '6000' && c.active).id)
const drawerVoucher = (t, ref, tries = 25) => cy.request({ url: `${GW}/api/expense/vouchers?size=200`, headers: hdr(t) }).its('body.data.content').then((l) => {
  const v = l.find((x) => x.source === 'DRAWER' && String(x.sourceRef) === String(ref))
  if ((v && v.postingStatus === 'POSTED_GL') || tries <= 0) return v
  cy.wait(1000)
  return drawerVoucher(t, ref, tries - 1)
})

describe('EX-9a — past till pay-outs into the books', () => {
  before(() => {
    signIn()
    cy.request({ method: 'POST', url: '/resetModuleSwitch', form: true, body: { key: CAP }, failOnStatusCode: false })
    signIn(true)
    closeIfOpen()
    cy.request({ method: 'POST', url: '/openShift', form: true, body: { openingFloat: 5000 } }).its('body.status').should('eq', 'SUCCESS')
    payOut(AMT[0], `EX9A tea ${run}`)                        // module OFF: the till moves, the books do not (pre-EX-3 history)
    payOut(AMT[1], `EX9A courier ${run}`)
    closeIfOpen()
    cy.request({ method: 'POST', url: '/saveModuleSwitch', form: true, body: { key: CAP, enabled: 'true' } }).its('body.success').should('eq', true)
  })

  after(() => {
    signIn()
    closeIfOpen()
    token(OWNER.email).then((t) => cy.request({ url: `${GW}/api/expense/vouchers?size=200`, headers: hdr(t) }).its('body.data.content').then((l) =>
      l.filter((v) => ((v.payeeName || '').includes(run)) && v.status === 'POSTED' && v.source !== 'DRAWER').forEach((v) =>
        cy.request({ method: 'POST', url: `${GW}/api/expense/vouchers/${v.id}/void`, headers: hdr(t), body: { reason: 'EX-9a gate' }, failOnStatusCode: false }))))
    cy.request({ method: 'POST', url: '/resetModuleSwitch', form: true, body: { key: CAP }, failOnStatusCode: false })
  })

  it('⭐ 1 — preview: the two pay-outs made before the module was on are listed; nothing is in the books yet', () => {
    token(OWNER.email).then((t) => preview(t).then((rows) => {
      const m = mine(rows)
      expect(m.map((x) => r2(x.amount)).sort()).to.deep.eq([r2(AMT[0]), r2(AMT[1])].sort())
      m.forEach((x) => {
        expect(x.date, 'its own date').to.eq(localIsoDate(new Date()))
        expect(x.matches, 'no expense like it yet').to.have.length(0)
      })
    }))
    signIn(true)
    cy.visit('/businessDashboard'); cy.waitForAppReady()
    cy.get('#snavTill').then(($d) => { if (!$d.hasClass('snav-open')) cy.get('#snavTill .snav-btn').click() })
    cy.get('#navExpenses').click()
    cy.get('[data-cy=till-history-open]', { timeout: 20000 }).click()
    // the panel scrolls (a business may have many past pay-outs): each row is brought into view, as a person would
    cy.contains('[data-cy=till-history-row]', `EX9A tea ${run}`, { timeout: 20000 }).scrollIntoView().should('be.visible')
    cy.contains('[data-cy=till-history-row]', `EX9A courier ${run}`).scrollIntoView().should('be.visible')
  })

  it('⭐ 2 — import once: ticked, with Rent, on screen; both in the books on their own date; again books nothing', () => {
    signIn(true)
    cy.visit('/businessDashboard'); cy.waitForAppReady()
    cy.get('#snavTill').then(($d) => { if (!$d.hasClass('snav-open')) cy.get('#snavTill .snav-btn').click() })
    cy.get('#navExpenses').click()
    net().then((tb0) => token(OWNER.email).then((t) => preview(t).then((rows) => {
      const refs = mine(rows).map((x) => x.ref)
      cy.get('[data-cy=till-history-open]', { timeout: 20000 }).click()
      cy.get('[data-cy=till-history-row] [data-cy=till-history-pick]').uncheck({ force: true })   // only this run's rows
      refs.forEach((ref) => cy.get(`[data-cy=till-history-row][data-ref="${ref}"] [data-cy=till-history-pick]`).check({ force: true }))
      cy.get('[data-cy=till-history-category] option').contains('Rent').then(($o) => cy.get('[data-cy=till-history-category]').select($o.val(), { force: true }))
      cy.get('[data-cy=till-history-import]').click()
      cy.get('#expHistMsg', { timeout: 20000 }).should('contain', '2 imported')
      refs.forEach((ref) => drawerVoucher(t, ref).then((v) => {
        expect(v, 'a till expense for pay-out ' + ref).to.exist
        expect(v.postingStatus).to.eq('POSTED_GL')
        expect(v.voucherDate).to.eq(localIsoDate(new Date()))
      }))
      net().then((tb1) => {
        expect(d(tb0, tb1, '6000'), 'Rent').to.eq(r2(AMT[0] + AMT[1]))
        expect(d(tb0, tb1, '1000'), 'the till').to.eq(-r2(AMT[0] + AMT[1]))
      })
      preview(t).then((after) => expect(mine(after), 'they leave the list').to.have.length(0))
      rentId(t).then((cat) => cy.request({ method: 'POST', url: `${GW}/api/expense/history/till/import`, headers: hdr(t),
        body: { categoryId: cat, refs } }).its('body.data').then((res) => {
        expect(res.imported, 'importing again books nothing').to.eq(0)
        expect(res.skipped).to.eq(refs.length)
      }))
    })))
  })

  it('⭐ 3 — a pay-out matching an expense already recorded (same day, same amount) is flagged and unticked', () => {
    token(OWNER.email).then((t) => rentId(t).then((cat) => cy.request({ method: 'POST', url: `${GW}/api/expense/vouchers?post=true`,
      headers: hdr(t, { 'Idempotency-Key': 'ex9a-' + run }), body: { paidFrom: 'CASH', payeeName: `EX9A already ${run}`, lines: [{ categoryId: cat, amount: AMT[2] }] } })
      .its('body.data.voucherNo').then((no) => {
        signIn()
        cy.request({ method: 'POST', url: '/resetModuleSwitch', form: true, body: { key: CAP }, failOnStatusCode: false })
        signIn(true)
        closeIfOpen()
        cy.request({ method: 'POST', url: '/openShift', form: true, body: { openingFloat: 5000 } })
        payOut(AMT[2], `EX9A paid twice ${run}`)
        closeIfOpen()
        cy.request({ method: 'POST', url: '/saveModuleSwitch', form: true, body: { key: CAP, enabled: 'true' } })
        preview(t).then((rows) => {
          const r = mine(rows).find((x) => (x.reason || '').includes('paid twice'))
          expect(r.matches, 'named by the expense it may duplicate').to.include(no)
        })
        signIn(true)
        cy.visit('/businessDashboard'); cy.waitForAppReady()
        cy.get('#snavTill').then(($d) => { if (!$d.hasClass('snav-open')) cy.get('#snavTill .snav-btn').click() })
        cy.get('#navExpenses').click()
        cy.get('[data-cy=till-history-open]', { timeout: 20000 }).click()
        cy.contains('[data-cy=till-history-row]', `EX9A paid twice ${run}`, { timeout: 20000 }).within(() => {
          cy.get('[data-cy=till-history-match]').should('contain', no)
          cy.get('[data-cy=till-history-pick]').should('not.be.checked')
        })
      })))
  })

  it('4 — only an owner or admin; an id that is not an unbooked pay-out of this business is skipped', () => {
    token(USER).then((u) => cy.request({ url: `${GW}/api/expense/history/till`, headers: hdr(u), failOnStatusCode: false }).its('status').should('eq', 403))
    token(OWNER.email).then((t) => rentId(t).then((cat) => cy.request({ method: 'POST', url: `${GW}/api/expense/history/till/import`, headers: hdr(t),
      body: { categoryId: cat, refs: [999999999] } }).its('body.data').then((res) => {
      expect(res.imported).to.eq(0)
      expect(res.skipped).to.eq(1)
    })))
  })
})
