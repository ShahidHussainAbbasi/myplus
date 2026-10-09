/**
 * EX-8b — the duplicate warning: the same payee, date and amount already recorded in the business is named before the
 * save, every time Save is pressed. Cancel saves nothing; Confirm saves it (two identical taxi fares on a day are real).
 * A voided expense, another amount, another day or another payee is not a duplicate. A colleague's counts: the case it
 * exists for is two people recording the same bill.
 *
 * Design: microservices/docs/slices/ex-8b-duplicate-warning.md §4. School tenant (owner/user.education).
 */

const GW = 'http://localhost:8765'
const PW = 'Demo@2025!'
const MGMT = 'org.cap.expenseManagement'
const OWNER = 'owner.education@myplus.com', USER = 'user.education@myplus.com'
const run = String(Date.now()).slice(-6)

const signIn = (email) => cy.loginAs(email, PW, '/getDashboardData', 'ex8b-' + Date.now())
const token = (email) => cy.request({ method: 'POST', url: `${GW}/api/auth/login`, body: { email, password: PW } }).its('body.data.accessToken')
const hdr = (t, extra = {}) => ({ Authorization: `Bearer ${t}`, 'Content-Type': 'application/json', ...extra })
const key = () => `ex8b-${Date.now()}-${Math.random().toString(36).slice(2, 8)}`
const TODAY = () => localIsoDate(new Date())
const dups = (t, amount, payee, date = TODAY()) => cy.request({ url: `${GW}/api/expense/vouchers/duplicates?date=${date}&amount=${amount}&payee=${encodeURIComponent(payee)}`,
  headers: hdr(t) }).its('body.data')
const openExpenses = () => {
  cy.visit('/educationDashboard'); cy.waitForAppReady()
  cy.get('#snavFee').then(($d) => { if (!$d.hasClass('snav-open')) cy.get('#snavFee .snav-btn').click() })
  cy.get('#navExpenses').should('be.visible').click()
  cy.get('#expCategory option', { timeout: 20000 }).should('have.length.greaterThan', 1)
}
const fill = (amount, payee) => {
  cy.get('#expCategory option').contains('Rent').then(($o) => cy.get('#expCategory').select($o.val(), { force: true }))
  cy.get('#expAmount').clear().type(String(amount))
  cy.get('#expPaidFrom').select('CASH', { force: true })
  cy.get('#expPayee').clear().type(payee)
}
const rows = (payee) => cy.get('#tableExpense tbody tr.expense-row').filter(`:contains("${payee}")`)
const voucherOf = (t, payee) => cy.request({ url: `${GW}/api/expense/vouchers?size=200`, headers: hdr(t) }).its('body.data.content')
  .then((l) => l.filter((v) => v.payeeName === payee && v.status === 'POSTED'))

describe('EX-8b — the duplicate warning', () => {
  const payee = `EX8B K-Electric ${run}`

  before(() => {
    token(OWNER).then((t) => cy.request({ method: 'POST', url: `${GW}/api/auth/settings?key=${MGMT}&value=true`, headers: { Authorization: `Bearer ${t}` } })
      .then((r) => expect(r.body.success, JSON.stringify(r.body)).to.eq(true)))
  })

  after(() => {
    token(OWNER).then((t) => {
      cy.request({ url: `${GW}/api/expense/vouchers?size=200`, headers: hdr(t) }).its('body.data.content').then((l) =>
        l.filter((v) => (v.payeeName || '').includes(run) && v.status === 'POSTED').forEach((v) =>
          cy.request({ method: 'POST', url: `${GW}/api/expense/vouchers/${v.id}/void`, headers: hdr(t), body: { reason: 'EX-8b gate' }, failOnStatusCode: false })))
      cy.request({ method: 'POST', url: `${GW}/api/auth/settings/reset?key=${MGMT}`, headers: { Authorization: `Bearer ${t}` }, failOnStatusCode: false })
    })
  })

  it('⭐ 1 — the same payee, day and amount again: named before saving; Cancel saves nothing; Confirm saves it', () => {
    signIn(OWNER)
    openExpenses()
    fill(40, payee)
    cy.get('[data-cy=save-expense]').click()
    cy.contains('#tableExpense tbody tr.expense-row', payee, { timeout: 25000 }).should('exist')
    token(OWNER).then((t) => voucherOf(t, payee).then((l) => {
      const first = l[0].voucherNo
      fill(40, `  ${payee.toLowerCase()} `)                   // the same payee, typed differently
      cy.get('[data-cy=save-expense]').click()
      cy.get('.uiC-card', { timeout: 20000 }).should('contain', 'already recorded').and('contain', first)
      cy.get('.uiC-cancel').click()
      cy.wait(1500)
      rows(payee).should('have.length', 1)
      cy.get('#expPayee').should('not.have.value', '')                // the form keeps what was typed
      cy.get('[data-cy=save-expense]').click()
      cy.get('.uiC-card', { timeout: 20000 }).should('contain', first)  // asked again, every time
      cy.get('[data-ui-confirm="ok"]').click()
      cy.get('#expMsg').should('contain', 'Expense saved')
      voucherOf(t, payee).its('length').should('eq', 1)               // the second was saved under the typed spelling
      dups(t, 40, payee).its('length').should('eq', 2)
    }))
  })

  it('⭐ 2 — not a duplicate: another amount, another day, another payee, or a voided expense', () => {
    token(OWNER).then((t) => {
      dups(t, 41, payee).should('have.length', 0)
      const y = new Date(); y.setDate(y.getDate() - 1)
      dups(t, 40, payee, localIsoDate(y)).should('have.length', 0)
      dups(t, 40, `${payee} Ltd`).should('have.length', 0)
      cy.request({ url: `${GW}/api/expense/vouchers?size=200`, headers: hdr(t) }).its('body.data.content').then((l) => {
        l.filter((v) => (v.payeeName || '').trim().toLowerCase() === payee.toLowerCase() && v.status === 'POSTED').forEach((v) =>
          cy.request({ method: 'POST', url: `${GW}/api/expense/vouchers/${v.id}/void`, headers: hdr(t), body: { reason: 'EX-8b gate' } }))
      })
      dups(t, 40, payee).should('have.length', 0)
    })
  })

  it('⭐ 3 — a colleague\'s expense counts (the same bill recorded twice by two people)', () => {
    const shared = `EX8B water ${run}`
    token(USER).then((u) => cy.request({ url: `${GW}/api/expense/categories`, headers: hdr(u) }).its('body.data').then((cats) =>
      cy.request({ method: 'POST', url: `${GW}/api/expense/vouchers?post=true`, headers: hdr(u, { 'Idempotency-Key': key() }),
        body: { voucherDate: TODAY(), paidFrom: 'CASH', payeeName: shared, lines: [{ categoryId: cats.find((c) => c.accountCode === '6000' && c.active).id, amount: 18 }] } })
        .its('body.success').should('eq', true)))
    signIn(OWNER)
    openExpenses()
    fill(18, shared)
    cy.get('[data-cy=save-expense]').click()
    cy.get('.uiC-card', { timeout: 20000 }).should('contain', 'already recorded').and('contain', 'EXP-')
    cy.get('.uiC-cancel').click()
  })

  it('4 — no payee, no question: the save goes straight through', () => {
    signIn(OWNER)
    openExpenses()
    cy.get('#expCategory option').contains('Rent').then(($o) => cy.get('#expCategory').select($o.val(), { force: true }))
    cy.get('#expAmount').clear().type('2')
    cy.get('#expPaidFrom').select('CASH', { force: true })
    cy.get('#expPayee').clear()
    cy.intercept('GET', '**/expense/vouchers/duplicates*').as('dup')
    cy.get('[data-cy=save-expense]').click()
    cy.get('#expMsg').should('contain', 'Expense saved')
    cy.get('@dup.all').should('have.length', 0)
    token(OWNER).then((t) => cy.request({ url: `${GW}/api/expense/vouchers?size=5`, headers: hdr(t) }).its('body.data.content.0').then((v) => {
      expect(v.payeeName || '').to.eq('')
      cy.request({ method: 'POST', url: `${GW}/api/expense/vouchers/${v.id}/void`, headers: hdr(t), body: { reason: 'EX-8b gate' }, failOnStatusCode: false })
    }))
  })
})
