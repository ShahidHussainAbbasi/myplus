/**
 * EX-2f — expense settings (E5): how far back an expense may be dated, and the form's default "Paid from".
 *
 * Design: microservices/docs/slices/ex-2f-expense-settings.md §4.
 * Runs on the WELFARE tenant. after() resets both settings to their defaults through the same service.
 * Dates are checked against the server's refusal, not the screen alone.
 */

const GW = 'http://localhost:8765'
const PW = 'Demo@2025!'
const KEY = 'org.cap.expenseManagement'
const OWNER = 'owner.welfare@myplus.com', USER = 'user.welfare@myplus.com'
const BACK = 'expense.voucher.backdateDays', PAID = 'expense.voucher.defaultPaidFrom'
const run = String(Date.now()).slice(-6)

const signIn = (email, fresh) => cy.loginAs(email, PW, '/getWelfareConfig', fresh ? 'ex2f-' + Date.now() : undefined)
const token = (email) => cy.request({ method: 'POST', url: `${GW}/api/auth/login`, body: { email, password: PW } }).its('body.data.accessToken')
const hdr = (t) => ({ Authorization: `Bearer ${t}`, 'Content-Type': 'application/json' })
const daysAgo = (n) => { const d = new Date(); d.setDate(d.getDate() - n); return localIsoDate(d) }
const rentOf = (t) => cy.request({ url: `${GW}/api/expense/categories`, headers: hdr(t) }).then((r) => (r.body.data || []).find((c) => c.accountCode === '6000'))
const record = (t, catId, date, tag) => cy.request({ method: 'POST', url: `${GW}/api/expense/vouchers?post=true`, failOnStatusCode: false,
  headers: { ...hdr(t), 'Idempotency-Key': `ex2f-${run}-${tag}` },
  body: { voucherDate: date, paidFrom: 'CASH', payeeName: `EX2F ${run} ${tag}`, lines: [{ categoryId: catId, amount: 1 }] } }).its('body')
const reset = (t, key) => cy.request({ method: 'POST', url: `${GW}/api/expense/settings/reset?key=${key}`, headers: hdr(t), failOnStatusCode: false })
const openExpenses = () => {
  cy.visit('/welfareDashboard'); cy.waitForAppReady()
  cy.get('#navExpenses').click({ force: true })
  cy.get('#expCategory option', { timeout: 20000 }).should('have.length.greaterThan', 1)
}

describe('EX-2f — expense settings', () => {
  const made = []

  before(() => {
    signIn(OWNER)
    cy.request({ method: 'POST', url: '/saveModuleSwitch', form: true, body: { key: KEY, enabled: 'true' } }).its('body.success').should('eq', true)
    token(OWNER).then((t) => { reset(t, BACK); reset(t, PAID) })
  })

  after(() => {
    token(OWNER).then((t) => {
      reset(t, BACK); reset(t, PAID)
      made.forEach((id) => cy.request({ method: 'POST', url: `${GW}/api/expense/vouchers/${id}/void`, headers: hdr(t), body: { reason: 'EX-2f gate' }, failOnStatusCode: false }))
    })
    signIn(OWNER)
    cy.request({ method: 'POST', url: '/resetModuleSwitch', form: true, body: { key: KEY }, failOnStatusCode: false })
  })

  it('⭐ 1 — defaults: 30 days back is allowed, 31 is refused in words (was a hidden 365); the form starts on Cash', () => {
    token(OWNER).then((t) => {
      cy.request({ url: `${GW}/api/expense/settings`, headers: hdr(t) }).its('body.data').then((rows) => {
        const m = Object.fromEntries(rows.map((r) => [r.key, r.value]))
        expect(m[BACK]).to.eq('30')
        expect(m[PAID]).to.eq('CASH')
      })
      rentOf(t).then((c) => {
        record(t, c.id, daysAgo(30), 'd30').then((b) => { expect(b.success, b.message).to.eq(true); made.push(b.data.id) })
        record(t, c.id, daysAgo(31), 'd31').then((b) => {
          expect(b.success).to.eq(false)
          expect(b.message).to.contain('at most 30 days back')
        })
      })
    })
    signIn(OWNER, true)
    openExpenses()
    cy.get('#expPaidFrom').should('have.value', 'CASH')
  })

  it('⭐ 2 — the owner narrows it to 5 days on the Settings panel: 6 days back is refused; a negative window is refused in words', () => {
    signIn(OWNER, true)
    openExpenses()
    cy.get('[data-cy=expense-settings-open]').click()
    cy.get('[data-cy=set-backdate]').should('have.value', '30').clear().type('-1')
    cy.get('[data-cy=set-backdate-save]').click()
    cy.get('#expSetMsg').should('contain', 'between 0 and 3650 days')
    cy.get('[data-cy=set-backdate]').clear().type('5')
    cy.get('[data-cy=set-backdate-save]').click()
    cy.get('#expSetMsg').should('contain', 'Setting saved')
    token(OWNER).then((t) => rentOf(t).then((c) => {
      record(t, c.id, daysAgo(6), 'd6').then((b) => { expect(b.success).to.eq(false); expect(b.message).to.contain('at most 5 days back') })
      record(t, c.id, daysAgo(5), 'd5').then((b) => { expect(b.success, b.message).to.eq(true); made.push(b.data.id) })
    }))
  })

  it('⭐ 3 — "Paid from" default set to Bank REACHES the form: on opening and again after a save', () => {
    signIn(OWNER, true)
    openExpenses()
    cy.get('[data-cy=expense-settings-open]').click()
    cy.get('[data-cy=set-paid-from]').select('BANK')
    cy.get('[data-cy=set-paid-from-save]').click()
    cy.get('#expSetMsg').should('contain', 'Setting saved')
    signIn(OWNER, true)
    openExpenses()
    cy.get('#expPaidFrom').should('have.value', 'BANK')
    cy.get('#expCategory option').eq(1).then(($o) => cy.get('#expCategory').select($o.val(), { force: true }))
    cy.get('#expAmount').type('2')
    cy.get('#expPaidFrom').select('CASH', { force: true })
    cy.get('#expPayee').type(`EX2F ${run} form`)
    cy.get('[data-cy=save-expense]').click()
    cy.contains('#tableExpense tbody tr', `EX2F ${run} form`, { timeout: 20000 }).should('contain', 'Cash')
      .invoke('attr', 'data-id').then((id) => made.push(id))
    cy.get('#expPaidFrom').should('have.value', 'BANK')        // the next expense starts from the default again
  })

  it('⭐ 4 — security: a user gets the default on their form but no Settings, and cannot change one', () => {
    signIn(USER, true)
    openExpenses()
    cy.get('#expPaidFrom').should('have.value', 'BANK')
    cy.get('[data-cy=expense-settings-open]').should('not.exist')
    cy.request({ method: 'POST', url: '/expense/settings', form: true, body: { key: BACK, value: '3650' }, failOnStatusCode: false })
      .then((r) => expect(r.status >= 400 || r.body.success === false, JSON.stringify(r.body).slice(0, 160)).to.eq(true))
    token(OWNER).then((t) => cy.request({ url: `${GW}/api/expense/settings`, headers: hdr(t) }).its('body.data')
      .then((rows) => expect(rows.find((r) => r.key === BACK).value, 'unchanged').to.eq('5')))
  })
})
